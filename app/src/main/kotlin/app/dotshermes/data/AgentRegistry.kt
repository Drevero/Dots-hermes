package app.dotshermes.data

import android.content.Context
import app.dotshermes.data.gateway.GatewayChatRepository
import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.data.model.GatewayConfig
import app.dotshermes.ui.AppearanceStore
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.dot.DotSpecies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Un agent tel que le sélecteur l'affiche : son apparence, sa connexion, ce qui l'occupe. */
data class AgentSummary(
    val id: String,
    val appearance: DotAppearance,
    val connection: ConnectionState,
    /** Adresse de son gateway : chaque agent est un serveur (un profil Hermes) à part. */
    val url: String,
    /** Messages non lus, toutes conversations de l'agent confondues (dot principal et sous-dots). */
    val unread: Int,
    /** Une de ses conversations travaille. */
    val busy: Boolean,
)

/**
 * Les agents Hermes indépendants de l'app : une connexion ([GatewayChatRepository]) et une apparence
 * par agent appairé, et celui qui est affiché.
 *
 * L'isolation vient du serveur : chaque agent est un profil Hermes à part (processus, mémoire,
 * outils, clés), joint avec son propre jeton. Ici, rien n'est partagé entre deux agents : ni
 * socket, ni état de conversation, ni cache de fichiers. Une action (message, feu vert, réponse
 * rapide) part toujours par la connexion de l'agent qu'elle vise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRegistry(
    private val context: Context,
    private val scope: CoroutineScope,
    val store: AgentStore,
    /** Une demande de feu vert a été tranchée (ici ou ailleurs) : sa notification peut partir. */
    private val onApprovalResolved: (agentId: String, approvalId: String) -> Unit,
) {
    private val repos = ConcurrentHashMap<String, GatewayChatRepository>()
    private val appearances = ConcurrentHashMap<String, AppearanceStore>()
    private val watchers = ConcurrentHashMap<String, Job>()

    /** Connexion de repli quand aucun agent n'est appairé : jamais connectée, l'écran d'appairage s'affiche. */
    private val unpaired by lazy { create(UNPAIRED) }

    @Volatile private var foreground = true
    @Volatile private var pushToken: String? = null

    /** Agent en cours d'ajout (connexion en essai, pas encore mémorisé) et celui qu'on affichait avant. */
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()
    @Volatile private var beforePending: String? = null

    private val _activeId = MutableStateFlow(store.lastActive ?: store.agents.value.firstOrNull()?.id)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    init {
        // Un seul agent à l'écran : les autres comptent leurs messages comme non lus.
        scope.launch { _activeId.collect { active -> repos.forEach { (id, repo) -> repo.setShown(id == active) } } }
    }

    val activeRepo: StateFlow<GatewayChatRepository> =
        _activeId.map { id -> id?.let(::repo) ?: unpaired }.stateIn(scope, SharingStarted.Eagerly, _activeId.value?.let(::repo) ?: unpaired)

    /** Apparence de l'agent affiché ; le thème est commun à toute l'app (celui de l'agent historique). */
    val activeAppearance: StateFlow<DotAppearance> =
        combine(_activeId.flatMapLatest { id -> appearance(id ?: AgentStore.PRIMARY_ID).state }, theme.state) { look, global ->
            look.copy(theme = global.theme)
        }.stateIn(scope, SharingStarted.Eagerly, appearance(_activeId.value ?: AgentStore.PRIMARY_ID).state.value)

    /** Le thème (clair, sombre, système) vit dans les préférences de l'agent historique. */
    val theme: AppearanceStore get() = appearance(AgentStore.PRIMARY_ID)

    /** Tous les agents appairés, dans l'ordre d'ajout, avec de quoi dessiner le sélecteur. */
    val summaries: StateFlow<List<AgentSummary>> = store.agents.flatMapLatest { links ->
        if (links.isEmpty()) flowOf(emptyList())
        else combine(links.map { summaryOf(it.id, it.url) }) { it.toList() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun summaryOf(id: String, url: String) = repo(id).let { r ->
        combine(appearance(id).state, r.connection, r.unread, r.chatActivity) { look, connection, unread, activities ->
            AgentSummary(
                id, look, connection, url, unread.values.sum(),
                busy = activities.values.any { it != AgentActivity.Idle && it != AgentActivity.Reacting },
            )
        }
    }

    fun repo(id: String): GatewayChatRepository = repos.getOrPut(id) { create(id) }

    fun appearance(id: String): AppearanceStore = appearances.getOrPut(id) {
        AppearanceStore(context, if (id == AgentStore.PRIMARY_ID) "appearance" else "appearance-$id")
    }

    private fun create(id: String): GatewayChatRepository {
        // L'agent historique garde son cache d'avant ; les autres ont le leur, à part.
        val cache = if (id == AgentStore.PRIMARY_ID) File(context.cacheDir, "voice") else File(context.cacheDir, "agents/$id/voice")
        val repo = GatewayChatRepository(context, scope, cache, onApprovalResolved = { approvalId -> onApprovalResolved(id, approvalId) })
        repo.setForeground(foreground)
        repo.setShown(id == _activeId.value)
        pushToken?.let(repo::setPushToken)
        if (id != UNPAIRED) {
            // Le gateway donne son identifiant (routage des push) et le nom de son profil (nom par défaut).
            watchers[id] = scope.launch {
                repo.serverAgent.collect { agent ->
                    if (agent == null) return@collect
                    if (store.get(id) != null) store.setRemoteId(id, agent.id)
                    appearance(id).adoptServerName(agent.name)
                }
            }
        }
        return repo
    }

    fun select(id: String) {
        if (store.get(id) == null && id != _pending.value) return
        _activeId.value = id
        if (store.get(id) != null) store.setActive(id)
    }

    /** Au lancement : chaque agent appairé se reconnecte, en parallèle. */
    fun connectAll() {
        store.agents.value.forEach { link ->
            val repo = repo(link.id)
            if (repo.connection.value == ConnectionState.Disconnected) scope.launch { repo.connect(link.config) }
        }
    }

    /** Ouvre l'écran d'appairage pour un agent de plus ; [cancelAdd] revient à l'agent d'avant. */
    fun beginAdd() {
        cancelAdd()
        val id = store.newId()
        beforePending = _activeId.value
        _pending.value = id
        _activeId.value = id
    }

    fun cancelAdd() {
        val id = _pending.value ?: return
        _pending.value = null
        drop(id)
        _activeId.value = beforePending?.takeIf { store.get(it) != null } ?: store.agents.value.firstOrNull()?.id
    }

    /**
     * Appaire [config] : l'agent en cours d'ajout, ou le premier s'il n'y en a aucun. Mémorisé
     * seulement si le gateway répond (welcome reçu) ; sinon l'écran montre l'erreur.
     */
    suspend fun connectNew(config: GatewayConfig): Boolean {
        val id = _pending.value ?: store.newId().also {
            beforePending = _activeId.value
            _pending.value = it
            _activeId.value = it
        }
        val repo = repo(id)
        repo.connect(config)
        if (repo.connection.value !is ConnectionState.Connected) return false
        // Même gateway qu'un agent déjà appairé : pas de doublon, on ouvre celui qui existe.
        val remote = repo.serverAgent.value?.id
        store.agents.value.firstOrNull { remote != null && it.remoteId == remote }?.let { existing ->
            _pending.value = null
            drop(id)
            select(existing.id)
            return true
        }
        val first = store.agents.value.isEmpty()
        store.upsert(AgentLink(id, config.url, config.token, repo.serverAgent.value?.id))
        repo.serverAgent.value?.let { appearance(id).adoptServerName(it.name) }
        // À partir du deuxième agent, une autre mascotte que celles déjà prises.
        if (!first) {
            val taken = store.agents.value.filter { it.id != id }.map { appearance(it.id).state.value.species }.toSet()
            appearance(id).initialize(DotSpecies.entries.firstOrNull { it !in taken } ?: DotSpecies.entries[store.agents.value.size % DotSpecies.entries.size])
        }
        _pending.value = null
        select(id)
        return true
    }

    /** Nouvel appairage d'un agent existant (jeton changé, adresse déplacée) : même conversation, mêmes réglages. */
    suspend fun reconnect(id: String, config: GatewayConfig): Boolean {
        val repo = repo(id)
        repo.connect(config)
        if (repo.connection.value !is ConnectionState.Connected) return false
        store.get(id)?.let { store.upsert(it.copy(url = config.url, token = config.token, remoteId = repo.serverAgent.value?.id ?: it.remoteId)) }
        return true
    }

    /** Oublie un agent : sa connexion se ferme, son jeton et son apparence sont effacés de l'appareil. */
    fun remove(id: String) {
        drop(id)
        store.remove(id)
        appearance(id).forget()
        if (_activeId.value == id) {
            val next = store.agents.value.firstOrNull()?.id
            _activeId.value = next
            next?.let(store::setActive)
        }
    }

    private fun drop(id: String) {
        watchers.remove(id)?.cancel()
        repos.remove(id)?.disconnect()
    }

    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        repos.values.forEach { it.setForeground(foreground) }
    }

    fun setPushToken(token: String) {
        pushToken = token
        repos.values.forEach { it.setPushToken(token) }
    }

    private companion object {
        const val UNPAIRED = "unpaired"
    }
}
