package app.dotshermes.ui

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import app.dotshermes.ui.theme.ThemeMode
import app.dotshermes.ui.dots.DotEditor
import app.dotshermes.ui.dots.DotsSheet
import app.dotshermes.ui.dots.DotsCarousel
import app.dotshermes.ui.dots.heroDots
import androidx.compose.runtime.remember
import androidx.compose.runtime.key

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dotshermes.AppContainer
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.ui.agent.AgentSettings
import app.dotshermes.ui.chat.ChatScreen
import app.dotshermes.ui.chat.VoiceControls
import app.dotshermes.ui.chat.AttachmentControls
import app.dotshermes.ui.activity.ActivitySheet
import app.dotshermes.ui.screen.ScreenViewer
import app.dotshermes.ui.connect.ConnectScreen
import app.dotshermes.ui.theme.DotsTheme
import androidx.compose.ui.res.stringResource
import app.dotshermes.R
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.dotshermes.update.Release
import app.dotshermes.update.UpdateState
import app.dotshermes.ui.update.UpdateDialog
import app.dotshermes.ui.update.updateStatusText
import kotlinx.coroutines.launch

/** Racine : écran d'appairage tant que le gateway n'est pas connecté, puis la conversation unique. */
@Composable
fun HermesApp(
    viewModel: HermesViewModel = viewModel {
        HermesViewModel(AppContainer.chatRepository, AppContainer.appearance, AppContainer.gatewayConfig, AppContainer.voiceRecorder, AppContainer.voicePlayer, AppContainer.attachments)
    },
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val snapshot by viewModel.activitySnapshot.collectAsStateWithLifecycle()
    val turns by viewModel.turns.collectAsStateWithLifecycle()
    val liveTools by viewModel.liveTools.collectAsStateWithLifecycle()
    val browsing by viewModel.browsing.collectAsStateWithLifecycle()
    var agentSheetOpen by rememberSaveable { mutableStateOf(false) }
    var activityOpen by rememberSaveable { mutableStateOf(false) }
    var screenOpen by rememberSaveable { mutableStateOf(false) }
    var screenTakeover by rememberSaveable { mutableStateOf(false) }
    val look by viewModel.activeLook.collectAsStateWithLifecycle()
    val dots by viewModel.dots.collectAsStateWithLifecycle()
    val activeChat by viewModel.activeChat.collectAsStateWithLifecycle()
    val chatActivity by viewModel.chatActivity.collectAsStateWithLifecycle()
    val unread by viewModel.unread.collectAsStateWithLifecycle()
    var creatingDot by rememberSaveable { mutableStateOf(false) }
    // Apparence saisie dans l'éditeur d'un sous-dot (ou du nouveau) : le carrousel l'affiche en direct.
    var editPreview by remember { mutableStateOf<DotAppearance?>(null) }

    // Mise à jour : vérifiée à l'ouverture ; le dialogue s'ouvre pour une nouvelle version (une fois
    // par version, sauf si l'on touche la notification ou la ligne « Version » des réglages).
    val updater = AppContainer.updater
    val updateState by updater.state.collectAsStateWithLifecycle()
    val updatePrompt by updater.promptRequested.collectAsStateWithLifecycle()
    var updateDialog by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        updater.check()?.let { if (updater.shouldPrompt(it)) updateDialog = true }
    }
    LaunchedEffect(updatePrompt) {
        if (!updatePrompt) return@LaunchedEffect
        updater.clearPrompt()
        if (updateState is UpdateState.Available || updater.check(force = true) != null) updateDialog = true
    }
    // Retour des réglages « sources inconnues » : si l'accord est donné, l'installation reprend seule.
    LifecycleResumeEffect(updateState) {
        val waiting = updateState as? UpdateState.NeedsPermission
        if (waiting != null && updater.canInstall()) scope.launch { updater.install(waiting.release) }
        onPauseOrDispose { }
    }

    val dark = when (appearance.theme) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    SystemBarsFor(dark)
    // La conversation affichée donne sa couleur à toute l'interface : on sait toujours à quel dot on parle.
    DotsTheme(look.appearance.palette, dark) {
        AnimatedContent(
            targetState = connection is ConnectionState.Connected,
            transitionSpec = { (fadeIn(tween(450)) + scaleIn(tween(450), initialScale = 0.97f)) togetherWith fadeOut(tween(200)) },
            label = "root",
        ) { inChat ->
            if (inChat) {
                ChatScreen(
                    state = chat,
                    connection = connection as? ConnectionState.Connected,
                    appearance = look.appearance,
                    onSend = viewModel::send,
                    onReact = viewModel::toggleReaction,
                    onApproval = viewModel::answerApproval,
                    onClarify = viewModel::answerClarify,
                    onOpenAgent = { agentSheetOpen = true },
                    onOpenActivity = { activityOpen = true },
                    onOpenScreen = { screenOpen = true },
                    screen = screen,
                    browsing = browsing,
                    onLoadOlder = viewModel::loadOlder,
                    otherUnread = unread.filterKeys { it != activeChat }.values.sum(),
                    heroDots = if ((connection as? ConnectionState.Connected)?.demo == true) emptyList() else heroDots(appearance, dots),
                    activeChat = activeChat,
                    onSwitchDot = viewModel::selectChat,
                    onTakeover = {
                        screenTakeover = true
                        screenOpen = true
                    },
                    onSendFile = viewModel::sendAttachment,
                    onSetRoom = viewModel::setRoom,
                    attachments = AttachmentControls(load = viewModel::attachmentFile, open = viewModel::openAttachment),
                    voice = VoiceControls(
                        recording = recording,
                        playback = playback,
                        onStartRecording = viewModel::startRecording,
                        onSendRecording = viewModel::sendRecording,
                        onCancelRecording = viewModel::cancelRecording,
                        onToggle = viewModel::toggleVoice,
                        onSeek = viewModel::seekVoice,
                    ),
                )
            } else {
                ConnectScreen(
                    connection = connection,
                    appearance = appearance,
                    initialUrl = viewModel.initialGatewayUrl,
                    onConnect = viewModel::connect,
                )
            }
        }


        if (activityOpen) {
            ActivitySheet(
                agentName = chat.agentName,
                appearance = appearance,
                activity = chat.activity,
                snapshot = snapshot,
                liveTools = liveTools,
                turns = turns,
                screen = screen,
                onRefresh = viewModel::refreshActivity,
                onCron = viewModel::cronAction,
                onStop = viewModel::stopTurn,
                onOpenScreen = { screenOpen = true },
                onDismiss = { activityOpen = false },
            )
        }

        if (screenOpen) {
            val handBackAnswer = stringResource(R.string.takeover_done_answer)
            val handBackMessage = stringResource(R.string.takeover_done_message)
            ScreenViewer(
                screen, chat.agentName, appearance,
                onWatch = viewModel::watchScreen,
                onInput = viewModel::screenInput,
                onHandBack = { viewModel.handBackScreen(handBackAnswer, handBackMessage) },
                startInTakeover = screenTakeover,
                onClose = {
                    screenOpen = false
                    screenTakeover = false
                },
            )
        }

        if (agentSheetOpen) {
            DotsSheet(
                onDismiss = {
                    agentSheetOpen = false
                    creatingDot = false
                    editPreview = null
                },
                strip = {
                    // Les sous-dots demandent le gateway : pas de bande en démo.
                    DotsCarousel(
                        main = appearance, dots = dots, activeChat = activeChat, creating = creatingDot,
                        preview = editPreview.takeIf { creatingDot || look.subDot != null },
                        activities = chatActivity, unread = unread,
                        showSubDots = (connection as? ConnectionState.Connected)?.demo != true,
                        onSelect = { chat ->
                            creatingDot = false
                            editPreview = null
                            viewModel.selectChat(chat)
                        },
                        onCreate = {
                            editPreview = null
                            creatingDot = true
                        },
                    )
                },
            ) {
                val sub = look.subDot
                when {
                    // La clé repart de zéro à chaque dot : le formulaire ne garde pas la saisie d'un autre.
                    creatingDot -> key("new-dot") {
                        DotEditor(existing = null, theme = appearance, onSave = { draft ->
                            viewModel.createDot(draft)
                            creatingDot = false
                            editPreview = null
                        }, onDelete = null, onPreview = { editPreview = it })
                    }
                    sub != null -> key(sub.id) {
                        DotEditor(existing = sub, theme = appearance, onSave = { draft -> viewModel.updateDot(sub.id, draft) },
                            onDelete = { viewModel.deleteDot(sub.id) }, onPreview = { editPreview = it })
                    }
                    else -> AgentSettings(
                        profile = profile,
                        connection = connection,
                        appearance = appearance,
                        onName = viewModel::setAgentName,
                        onSpecies = viewModel::setSpecies,
                        onTheme = viewModel::setTheme,
                        onPalette = viewModel::setPalette,
                        onAccessory = viewModel::setAccessory,
                        appVersion = updater.currentVersion,
                        updateStatus = updateStatusText(updateState, updater.enabled),
                        onUpdateRow = {
                            if (updateState is UpdateState.Available || updateState is UpdateState.Failed) updateDialog = true
                            else scope.launch { if (updater.check(force = true) != null) updateDialog = true }
                        },
                        onDisconnect = {
                            agentSheetOpen = false
                            viewModel.disconnect()
                        },
                    )
                }
            }
        }
        if (updateDialog) {
            UpdateDialog(
                state = updateState,
                currentVersion = updater.currentVersion,
                onUpdate = { scope.launch { updater.install(updateRelease(updateState) ?: return@launch) } },
                onLater = {
                    updateRelease(updateState)?.let(updater::dismiss)
                    updateDialog = false
                },
                onOpenSettings = { context.startActivity(updater.permissionSettingsIntent()) },
                onClose = { updateDialog = false },
            )
        }
    }
}

/** Icônes de la barre d'état et de navigation : sombres sur fond clair, claires sur fond sombre. */
@Composable
private fun SystemBarsFor(dark: Boolean) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    LaunchedEffect(dark) {
        val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}

private fun updateRelease(state: UpdateState): Release? = when (state) {
    is UpdateState.Available -> state.release
    is UpdateState.NeedsPermission -> state.release
    is UpdateState.Failed -> state.release
    else -> null
}
