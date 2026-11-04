package app.dotshermes

import android.content.Context
import java.io.File
import app.dotshermes.data.ChatRepository
import app.dotshermes.data.GatewayConfigStore
import app.dotshermes.data.RoutingChatRepository
import app.dotshermes.data.gateway.GatewayChatRepository
import app.dotshermes.data.mock.MockChatRepository
import app.dotshermes.attach.Attachments
import app.dotshermes.push.Notifications
import app.dotshermes.ui.AppearanceStore
import app.dotshermes.update.AppUpdater
import app.dotshermes.voice.VoicePlayer
import app.dotshermes.voice.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Injection manuelle : gateway réel par défaut, agent simulé pour l'adresse « demo ». */
object AppContainer {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var appContext: Context

    /** Une activité est visible (tenu à jour par [HermesApplication]). */
    @Volatile var inForeground = false

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Exposé à part pour la réponse rapide des notifications, qui vise toujours le vrai gateway. */
    val gateway: GatewayChatRepository by lazy {
        GatewayChatRepository(appContext, appScope, File(appContext.cacheDir, "voice"), onApprovalResolved = { id -> Notifications.cancelApproval(appContext, id) })
    }

    val chatRepository: ChatRepository by lazy {
        RoutingChatRepository(appScope, gateway = gateway, demo = MockChatRepository(appContext, appScope))
    }
    val gatewayConfig: GatewayConfigStore by lazy { GatewayConfigStore(appContext) }
    val appearance: AppearanceStore by lazy { AppearanceStore(appContext) }
    val voiceRecorder: VoiceRecorder by lazy { VoiceRecorder(appContext, appScope) }
    val voicePlayer: VoicePlayer by lazy { VoicePlayer(appScope) }
    val attachments: Attachments by lazy { Attachments(appContext) }
    val updater: AppUpdater by lazy { AppUpdater(appContext) }
}
