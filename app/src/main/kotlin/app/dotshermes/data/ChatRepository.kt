package app.dotshermes.data

import app.dotshermes.data.model.ActivitySnapshot
import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.CronAction
import app.dotshermes.data.model.ScreenInput
import app.dotshermes.data.model.ScreenState
import app.dotshermes.data.model.SubDot
import app.dotshermes.data.model.SubDotDraft
import app.dotshermes.data.model.AgentProfile
import app.dotshermes.data.model.ChatMessage
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.data.model.GatewayConfig
import app.dotshermes.data.model.ToolCall
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Seul point de contact entre l'interface et l'agent Hermes.
 *
 * Implémentations : [app.dotshermes.data.gateway.GatewayChatRepository] (WebSocket vers le
 * plugin « dots » du gateway) et [app.dotshermes.data.mock.MockChatRepository] (mode démo),
 * aiguillées par [RoutingChatRepository].
 */
interface ChatRepository {
    val profile: StateFlow<AgentProfile>
    val connection: StateFlow<ConnectionState>
    val messages: StateFlow<List<ChatMessage>>
    val activity: StateFlow<AgentActivity>
    /** Outils utilisés par l'agent, dans l'ordre ; affichés en sur-messages, jamais comme des messages. */
    val tools: StateFlow<List<ToolCall>>
    /** Écran du navigateur de l'agent (vignette après chaque action, flux quand on le regarde). */
    val screen: StateFlow<ScreenState>
    /** Vue Activité ; null tant qu'elle n'a pas été demandée. */
    val activitySnapshot: StateFlow<ActivitySnapshot?>
    /** Des messages plus anciens restent à charger (défilement vers le haut). */
    val hasOlder: StateFlow<Boolean>

    /** Les sous-dots ; le dot principal n'en fait pas partie (conversation [app.dotshermes.data.model.MAIN_CHAT]). */
    val dots: StateFlow<List<SubDot>>
    /** Conversation affichée : `messages`, `tools`, `activity`… décrivent celle-ci. */
    val activeChat: StateFlow<String>
    /** Activité de chaque conversation, pour animer les dots qui travaillent en arrière-plan. */
    val chatActivity: StateFlow<Map<String, AgentActivity>>
    /** Messages de l'agent arrivés dans une conversation qui n'était pas affichée. */
    val unread: StateFlow<Map<String, Int>>

    suspend fun connect(config: GatewayConfig)
    fun disconnect()
    fun send(text: String)
    fun toggleReaction(messageId: String, emoji: String)
    fun answerApproval(messageId: String, approved: Boolean)
    fun answerClarify(messageId: String, response: String)

    /** Vocal enregistré au micro (AAC dans un conteneur MP4), avec son enveloppe pour l'affichage. */
    fun sendVoice(file: File, durationMs: Long, waveform: List<Float>)

    /** Pièce jointe envoyée par l'utilisateur ; [caption] part avec elle comme texte du message. */
    fun sendFile(file: File, name: String, mime: String, caption: String)

    /** Fichier d'un vocal ou d'une pièce jointe : local, ou téléchargé depuis le gateway ; null si introuvable. */
    suspend fun attachmentFile(messageId: String): File?

    /** Ouvre (ou ferme) le flux en direct de l'écran du navigateur ; [takeover] : l'utilisateur pilote (flux plus rapide). */
    fun watchScreen(on: Boolean, takeover: Boolean = false) {}

    /** Clic, défilement, frappe ou touche, rejoués sur la page de l'agent. */
    fun screenInput(input: ScreenInput) {}

    fun refreshActivity() {}

    /** Charge la page d'historique précédente ; sans effet si rien ne reste ou si une page arrive déjà. */
    fun loadOlder() {}

    fun cronAction(id: String, action: CronAction) {}

    fun selectChat(chat: String) {}

    fun createDot(draft: SubDotDraft) {}

    fun updateDot(id: String, draft: SubDotDraft) {}

    /** Ferme un sous-dot : sa conversation est effacée côté gateway. */
    fun deleteDot(id: String) {}

    /** Rend la main à l'agent après une prise en main qu'il avait demandée. */
    fun finishTakeover(messageId: String) {}

    /** Interrompt le tour en cours (équivalent de /stop). */
    fun stopTurn() {}

    /** L'app est-elle à l'écran ? Sinon, le gateway prévient par notification push. */
    fun setForeground(foreground: Boolean) {}

    /** Jeton FCM de cet appareil, transmis au gateway pour les notifications. */
    fun setPushToken(token: String) {}
}
