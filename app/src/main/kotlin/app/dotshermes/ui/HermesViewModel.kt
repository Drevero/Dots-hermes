package app.dotshermes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dotshermes.BuildConfig
import app.dotshermes.data.ChatRepository
import app.dotshermes.data.GatewayConfigStore
import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.AgentProfile
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.data.model.GatewayConfig
import app.dotshermes.data.model.MessageContent
import app.dotshermes.data.model.ToolState
import app.dotshermes.data.model.SubDot
import app.dotshermes.data.model.SubDotDraft
import app.dotshermes.ui.dots.appearance
import app.dotshermes.ui.dots.orderedDots
import app.dotshermes.ui.chat.ChatRow
import app.dotshermes.ui.chat.buildChatRows
import app.dotshermes.ui.dot.DotAccessory
import app.dotshermes.ui.dot.DotSpecies
import app.dotshermes.ui.theme.ThemeMode
import app.dotshermes.ui.theme.DotPalette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.net.Uri
import app.dotshermes.attach.Attachments
import app.dotshermes.data.model.ActivitySnapshot
import app.dotshermes.data.model.CronAction
import app.dotshermes.data.model.ScreenInput
import app.dotshermes.data.model.ScreenState
import app.dotshermes.data.model.ToolCall
import app.dotshermes.ui.activity.TurnSummary
import app.dotshermes.ui.activity.summarizeTurns
import app.dotshermes.ui.activity.currentTurnTools
import app.dotshermes.ui.activity.isBrowsingNow
import kotlinx.coroutines.flow.map
import java.io.File
import app.dotshermes.voice.Playback
import app.dotshermes.voice.RecordingState
import app.dotshermes.voice.VoicePlayer
import app.dotshermes.voice.VoiceRecorder

/** Le dot dont la conversation est affichée : le principal, ou un sous-dot avec son apparence à lui. */
data class ActiveLook(val appearance: DotAppearance, val subDot: SubDot?)

data class ChatUiState(
    val agentName: String = "Hermes",
    val activity: AgentActivity = AgentActivity.Idle,
    val rows: List<ChatRow> = emptyList(),
    val messageCount: Int = 0,
    /** Salon de groupe : les sous-dots invités dans cette conversation (vide : conversation normale). */
    val room: List<SubDot> = emptyList(),
    /** Ceux qu'on peut inviter : tous les sous-dots, sauf celui dont c'est la conversation. */
    val roomCandidates: List<SubDot> = emptyList(),
    /** Apparence de chaque sous-dot, pour la mascotte au-dessus de ses bulles. */
    val speakerLooks: Map<String, DotAppearance> = emptyMap(),
    /** Salon en plein débat : le sous-dot qui a la parole, annoncé par l'en-tête (« Pebble a la parole »). */
    val speakingName: String? = null,
)

/** Le salon de la conversation affichée, tel que le dépôt le décrit, avant mise en forme. */
private data class RoomInfo(val invited: List<SubDot>, val candidates: List<SubDot>, val speaker: String?, val looks: Map<String, DotAppearance>)

private val DoneWords = listOf("fait", "done", "finished")

class HermesViewModel(
    private val repository: ChatRepository,
    private val appearanceStore: AppearanceStore,
    private val configStore: GatewayConfigStore,
    private val recorder: VoiceRecorder,
    private val player: VoicePlayer,
    private val attachments: Attachments,
) : ViewModel() {

    val screen: StateFlow<ScreenState> = repository.screen
    val activitySnapshot: StateFlow<ActivitySnapshot?> = repository.activitySnapshot

    /** Les derniers échanges et le travail qu'ils ont déclenché, pour la vue Activité. */
    val turns: StateFlow<List<TurnSummary>> = combine(repository.messages, repository.tools) { messages, tools ->
        summarizeTurns(messages, tools)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Outils du tour en cours (le dernier tour qui a des outils), pour « En ce moment ». */
    val liveTools: StateFlow<List<ToolCall>> = combine(repository.messages, repository.tools, repository.activity) { messages, tools, activity ->
        currentTurnTools(messages, tools, busy = activity != AgentActivity.Idle)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Pastille « navigue » : seulement si le tour en cours est sur le navigateur, pas parce qu'une image d'avant existe. */
    val browsing: StateFlow<Boolean> = liveTools.map(::isBrowsingNow)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val recording: StateFlow<RecordingState?> = recorder.state
    val playback: StateFlow<Playback> = player.state

    /** Adresse proposée sur l'écran d'appairage : la dernière utilisée, sinon le serveur Hermes. */
    val initialGatewayUrl: String = configStore.load()?.url ?: DEFAULT_GATEWAY_URL

    init {
        // Appairage mémorisé : on se reconnecte directement au lancement.
        configStore.load()?.let { saved ->
            if (repository.connection.value == ConnectionState.Disconnected) connect(saved.url, saved.token)
        }
    }

    val connection: StateFlow<ConnectionState> = repository.connection
    val profile: StateFlow<AgentProfile> = repository.profile
    val appearance: StateFlow<DotAppearance> = appearanceStore.state

    val dots: StateFlow<List<SubDot>> = repository.dots
    val activeChat: StateFlow<String> = repository.activeChat
    val chatActivity: StateFlow<Map<String, AgentActivity>> = repository.chatActivity
    val unread: StateFlow<Map<String, Int>> = repository.unread

    val activeLook: StateFlow<ActiveLook> =
        combine(appearanceStore.state, repository.dots, repository.activeChat) { main, dots, chat ->
            val dot = dots.firstOrNull { it.id == chat }
            ActiveLook(dot?.appearance(main) ?: main, dot)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ActiveLook(appearanceStore.state.value, null))

    private val roomInfo = combine(repository.room, repository.speaker, repository.dots, repository.activeChat, appearanceStore.state) { invited, speaker, dots, chat, main ->
        val byId = dots.associateBy { it.id }
        RoomInfo(
            invited = invited.mapNotNull(byId::get),
            candidates = orderedDots(dots).filter { it.id != chat },
            speaker = speaker,
            looks = dots.associate { it.id to it.appearance(main) },
        )
    }

    private val thread = combine(repository.messages, repository.tools, repository.hasOlder) { messages, tools, hasOlder -> Triple(messages, tools, hasOlder) }

    val chat: StateFlow<ChatUiState> =
        combine(activeLook, thread, repository.activity, roomInfo) { look, (messages, tools, hasOlder), activity, room ->
            val appearance = look.appearance
            // Un outil en cours prime sur « Réfléchit… » : l'en-tête dit ce que fait vraiment l'agent.
            val running = tools.lastOrNull { it.state == ToolState.Running }
            val shown = if (running != null && activity != AgentActivity.Typing) AgentActivity.Working("${running.label}…") else activity
            ChatUiState(
                agentName = appearance.displayName,
                activity = shown,
                rows = buildChatRows(messages, activity, tools = tools, hasOlder = hasOlder, speaker = room.speaker),
                messageCount = messages.size,
                room = room.invited,
                roomCandidates = room.candidates,
                speakerLooks = room.looks,
                speakingName = room.speaker?.takeIf { activity != AgentActivity.Idle }?.let { id -> room.looks[id]?.displayName },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    /** Salon de groupe de la conversation affichée : ces sous-dots débattront de chaque message (vide : fermé). */
    fun setRoom(dots: List<String>) = repository.setRoom(dots)

    fun connect(url: String, token: String) {
        val config = GatewayConfig(url.trim(), token.trim())
        viewModelScope.launch {
            repository.connect(config)
            if (!config.isDemo && repository.connection.value is ConnectionState.Connected) configStore.save(config)
        }
    }

    /** Déconnexion volontaire : on oublie aussi l'appairage pour ne pas se reconnecter au lancement. */
    fun disconnect() {
        configStore.clear()
        repository.disconnect()
    }

    fun send(text: String) {
        if (text.isNotBlank()) repository.send(text.trim())
    }

    /** Micro : la permission est demandée par l'écran avant l'appel. */
    fun startRecording(): Boolean {
        player.stop()
        return recorder.start()
    }

    fun sendRecording() {
        recorder.stop()?.let { repository.sendVoice(it.file, it.durationMs, it.waveform) }
    }

    fun cancelRecording() = recorder.cancel()

    /** Lecture d'un vocal : fichier local tout de suite, sinon téléchargé une fois auprès du gateway. */
    fun toggleVoice(messageId: String) {
        val current = player.state.value
        if (current.messageId == messageId && current.loading) return
        viewModelScope.launch {
            if (current.messageId != messageId) player.markLoading(messageId)
            val file = repository.attachmentFile(messageId)
            if (file == null) player.stop() else player.toggle(messageId, file)
        }
    }

    fun seekVoice(messageId: String, fraction: Float) = player.seek(messageId, fraction)

    /** Fichier choisi sur le téléphone : copié en privé puis envoyé, avec le brouillon comme légende. */
    fun sendAttachment(uri: Uri, caption: String, onTooBig: () -> Unit) {
        viewModelScope.launch {
            attachments.import(uri, onTooBig)?.let { repository.sendFile(it.file, it.name, it.mime, caption.trim()) }
        }
    }

    suspend fun attachmentFile(messageId: String): File? = repository.attachmentFile(messageId)

    fun openAttachment(file: File, mime: String) = attachments.open(file, mime)

    fun watchScreen(on: Boolean, takeover: Boolean) = repository.watchScreen(on, takeover)

    fun screenInput(input: ScreenInput) = repository.screenInput(input)

    /**
     * Fin de la prise en main. L'agent attend d'ordinaire sur une question (outil clarify, qui garde
     * son tour — et donc son navigateur — ouvert) : on y répond. Sinon, on le lui dit dans la conversation.
     */
    /** [doneAnswer] / [doneMessage] : réponse et message envoyés à l'agent, dans la langue du téléphone. */
    fun handBackScreen(doneAnswer: String, doneMessage: String) {
        // Demande explicite de l'agent (outil browser_request_takeover) : c'est elle qui retient son tour.
        repository.messages.value.lastOrNull { (it.content as? MessageContent.Takeover)?.pending == true }?.let {
            repository.finishTakeover(it.id)
            return
        }
        val pending = repository.messages.value.lastOrNull { (it.content as? MessageContent.Clarify)?.answer == null && it.content is MessageContent.Clarify }
        if (pending != null) {
            val choices = (pending.content as MessageContent.Clarify).choices
            // Le choix proposé par l'agent qui veut dire « c'est fait » (il peut répondre en français ou en anglais).
            val done = choices.firstOrNull { c -> DoneWords.any { c.contains(it, ignoreCase = true) } }
            repository.answerClarify(pending.id, done ?: doneAnswer)
        } else {
            repository.send(doneMessage)
        }
    }

    fun refreshActivity() = repository.refreshActivity()

    fun cronAction(id: String, action: CronAction) = repository.cronAction(id, action)

    fun stopTurn() = repository.stopTurn()

    fun loadOlder() = repository.loadOlder()

    fun selectChat(chat: String) = repository.selectChat(chat)

    fun createDot(draft: SubDotDraft) = repository.createDot(draft)

    fun updateDot(id: String, draft: SubDotDraft) = repository.updateDot(id, draft)

    fun deleteDot(id: String) = repository.deleteDot(id)

    override fun onCleared() {
        recorder.cancel()
        player.stop()
    }

    fun toggleReaction(messageId: String, emoji: String) = repository.toggleReaction(messageId, emoji)

    fun answerApproval(messageId: String, approved: Boolean) = repository.answerApproval(messageId, approved)

    fun answerClarify(messageId: String, response: String) = repository.answerClarify(messageId, response)

    fun setPalette(palette: DotPalette) = appearanceStore.setPalette(palette)

    fun setAgentName(name: String) = appearanceStore.setName(name)

    fun setSpecies(species: DotSpecies) = appearanceStore.setSpecies(species)

    fun setTheme(theme: ThemeMode) = appearanceStore.setTheme(theme)

    fun setAccessory(accessory: DotAccessory) = appearanceStore.setAccessory(accessory)

    private companion object {
        /** Fixée à la construction (variable DEFAULT_GATEWAY_URL) ; vide par défaut. */
        val DEFAULT_GATEWAY_URL: String = BuildConfig.DEFAULT_GATEWAY_URL
    }
}
