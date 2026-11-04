package app.dotshermes.ui.webpair

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dotshermes.R
import app.dotshermes.data.AgentSummary
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.data.webpair.AgentIssue
import app.dotshermes.data.webpair.PairFailure
import app.dotshermes.data.webpair.PairQr
import app.dotshermes.data.webpair.PairQrError
import app.dotshermes.data.webpair.PairReport
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.components.CircleIconButton
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.Hairline
import app.dotshermes.ui.components.PillButton
import app.dotshermes.ui.components.SectionLabel
import app.dotshermes.ui.displayName
import app.dotshermes.ui.dot.ClayDot
import app.dotshermes.ui.dot.DotMood
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette

/**
 * « Connecter un navigateur » (docs/appairage-web.md, section 2) : viseur plein écran, confirmation
 * qui nomme l'hôte et les agents transmis, puis le résultat agent par agent. Rien ne quitte le
 * téléphone avant l'appui sur « Connecter ».
 */
@Composable
fun WebPairScreen(viewModel: WebPairViewModel, appearance: DotAppearance, onClose: () -> Unit) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val agents by viewModel.agentList.collectAsStateWithLifecycle()

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Ink.Surface)) {
            when (val s = step) {
                is WebPairStep.Scanning, is WebPairStep.Confirm -> {
                    ScanStep(
                        error = (s as? WebPairStep.Scanning)?.error,
                        // Pendant la confirmation, la caméra reste ouverte derrière la feuille mais les lectures sont ignorées.
                        onCode = viewModel::onScanned,
                        onClose = onClose,
                    )
                    if (s is WebPairStep.Confirm) {
                        ConfirmSheet(s.qr, agents, onConnect = viewModel::confirm, onCancel = viewModel::rescan)
                    }
                }
                is WebPairStep.Working -> ResultPanel(appearance, onClose) { WorkingContent(s.qr) }
                is WebPairStep.Done -> ResultPanel(appearance, onClose, mood = if (s.report.failure == null) DotMood.Happy else DotMood.Sleeping) {
                    DoneContent(s.qr, s.report, onRescan = viewModel::rescan, onClose = onClose)
                }
            }
        }
    }
}

// -- Viseur ----------------------------------------------------------------------------------------

@Composable
private fun ScanStep(error: PairQrError?, onCode: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCamera()) }
    // Refus définitif (« Ne plus demander ») : seule la page des réglages de l'app peut rendre l'accès.
    var blocked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        blocked = !ok && context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
    }
    // Retour des réglages : l'accès a peut-être été donné.
    LifecycleResumeEffect(Unit) {
        granted = context.hasCamera()
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            QrScanner(onCode = onCode, modifier = Modifier.fillMaxSize())
            Viewfinder(Modifier.fillMaxSize())
        } else {
            CameraRationale(
                blocked = blocked,
                onAllow = { permission.launch(Manifest.permission.CAMERA) },
                onSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.web_scan_title),
                style = MaterialTheme.typography.titleLarge,
                color = if (granted) Color.White else Ink.Primary,
                modifier = Modifier.weight(1f),
            )
            CircleIconButton(
                Icons.Rounded.Close, stringResource(R.string.action_close), onClose,
                container = if (granted) Color.White.copy(alpha = 0.18f) else Ink.Fill,
                tint = if (granted) Color.White else Ink.Primary,
            )
        }

        if (granted) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (error != null) {
                    Text(
                        stringResource(error.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Ink.Danger.copy(alpha = 0.9f)).padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    stringResource(R.string.web_scan_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Voile sombre percé d'un carré aux coins arrondis, cerclé de la couleur du dot. */
@Composable
private fun Viewfinder(modifier: Modifier) {
    val accent = LocalDotPalette.current.base
    Box(
        modifier
            // Calque à part : le « trou » efface le voile sans effacer l'aperçu caméra dessous.
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawBehind {
                val side = minOf(size.width, size.height) * 0.68f
                val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
                val radius = CornerRadius(28.dp.toPx())
                drawRect(Color.Black.copy(alpha = 0.55f))
                drawRoundRect(Color.Transparent, topLeft, Size(side, side), radius, blendMode = BlendMode.Clear)
                drawRoundRect(accent, topLeft, Size(side, side), radius, style = Stroke(width = 3.dp.toPx()))
            },
    )
}

@Composable
private fun CameraRationale(blocked: Boolean, onAllow: () -> Unit, onSettings: () -> Unit) {
    val palette = LocalDotPalette.current
    Column(
        Modifier.fillMaxSize().background(Ink.Surface).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ClayDot(palette, Modifier.size(150.dp), mood = DotMood.Idle, showShadow = false)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.web_camera_title), style = MaterialTheme.typography.headlineSmall, color = Ink.Primary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (blocked) R.string.web_camera_blocked else R.string.web_camera_rationale),
            style = MaterialTheme.typography.bodyLarge, color = Ink.Secondary, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        PillButton(
            text = stringResource(if (blocked) R.string.web_camera_settings else R.string.web_camera_allow),
            onClick = if (blocked) onSettings else onAllow,
            modifier = Modifier.fillMaxWidth(),
            container = palette.base,
            content = palette.onBase,
            height = 54.dp,
        )
    }
}

// -- Confirmation ----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmSheet(qr: PairQr, agents: List<AgentSummary>, onConnect: () -> Unit, onCancel: () -> Unit) {
    val palette = LocalDotPalette.current
    val shared = agents.filter { it.connection is ConnectionState.Connected }
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.Grouped,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Ink.Tertiary) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text(
                withBold(stringResource(R.string.web_confirm_title, qr.host), qr.host),
                style = MaterialTheme.typography.titleLarge,
                color = Ink.Primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            )

            SectionLabel(stringResource(R.string.web_confirm_agents))
            GroupedCard {
                agents.forEachIndexed { index, agent ->
                    if (index > 0) Hairline(startIndent = 64.dp)
                    AgentLine(agent.appearance, agent.connection is ConnectionState.Connected)
                }
            }
            if (shared.isEmpty()) {
                Text(
                    stringResource(R.string.web_confirm_none),
                    style = MaterialTheme.typography.bodySmall, color = Ink.Danger,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(18.dp)).background(Ink.Raised).padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Rounded.Warning, null, tint = Ink.Warning, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.web_confirm_warning), style = MaterialTheme.typography.bodyMedium, color = Ink.Primary)
            }

            Spacer(Modifier.height(20.dp))
            PillButton(
                text = stringResource(R.string.web_connect),
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                container = palette.base,
                content = palette.onBase,
                enabled = shared.isNotEmpty(),
                height = 54.dp,
            )
            Spacer(Modifier.height(8.dp))
            PillButton(
                text = stringResource(R.string.action_cancel),
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                container = Ink.Fill,
                content = Ink.Primary,
                height = 54.dp,
            )
        }
    }
}

@Composable
private fun AgentLine(look: DotAppearance, connected: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ClayDot(
            look.palette, Modifier.size(40.dp), mood = if (connected) DotMood.Idle else DotMood.Sleeping,
            accessory = look.accessory, species = look.species, showShadow = false,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(look.displayName, style = MaterialTheme.typography.bodyLarge, color = if (connected) Ink.Primary else Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!connected) Text(stringResource(R.string.web_confirm_offline), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
        }
    }
}

// -- Progression et résultat -----------------------------------------------------------------------

@Composable
private fun ResultPanel(appearance: DotAppearance, onClose: () -> Unit, mood: DotMood = DotMood.Thinking, content: @Composable () -> Unit) {
    val palette = LocalDotPalette.current
    Box(Modifier.fillMaxSize().background(Ink.Surface)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            ClayDot(palette, Modifier.size(180.dp), mood = mood, accessory = appearance.accessory, species = appearance.species)
            Spacer(Modifier.height(8.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
        CircleIconButton(
            Icons.Rounded.Close, stringResource(R.string.action_close), onClose,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp),
        )
    }
}

@Composable
private fun WorkingContent(qr: PairQr) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), color = LocalDotPalette.current.base, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.web_working, qr.host), style = MaterialTheme.typography.bodyLarge, color = Ink.Secondary)
    }
}

@Composable
private fun DoneContent(qr: PairQr, report: PairReport, onRescan: () -> Unit, onClose: () -> Unit) {
    val palette = LocalDotPalette.current
    val ok = report.failure == null
    Text(
        stringResource(if (ok) R.string.web_success_title else R.string.web_failed_title),
        style = MaterialTheme.typography.headlineSmall, color = Ink.Primary, textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    val summary = when (report.failure) {
        null -> stringResource(R.string.web_success_text, report.shared.joinToString(", "), qr.host)
        PairFailure.NothingShared -> stringResource(R.string.web_error_nothing)
        PairFailure.Expired -> stringResource(R.string.web_error_expired)
        PairFailure.InvalidQr -> stringResource(R.string.web_error_invalid)
        PairFailure.Network -> stringResource(R.string.web_error_network, qr.host)
    }
    Text(summary, style = MaterialTheme.typography.bodyLarge, color = Ink.Secondary, textAlign = TextAlign.Center)

    if (report.issues.isNotEmpty()) {
        SectionLabel(stringResource(R.string.web_issues_title))
        GroupedCard {
            report.issues.forEachIndexed { index, issue ->
                if (index > 0) Hairline()
                Text(
                    issueText(issue),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.Primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }

    Spacer(Modifier.height(28.dp))
    if (ok) {
        PillButton(stringResource(R.string.web_done), onClose, Modifier.fillMaxWidth(), container = palette.base, content = palette.onBase, height = 54.dp)
    } else {
        PillButton(stringResource(R.string.web_scan_again), onRescan, Modifier.fillMaxWidth(), container = palette.base, content = palette.onBase, height = 54.dp)
        Spacer(Modifier.height(8.dp))
        PillButton(stringResource(R.string.action_close), onClose, Modifier.fillMaxWidth(), container = Ink.Fill, content = Ink.Primary, height = 54.dp)
    }
}

@Composable
private fun issueText(issue: AgentIssue): String = when (issue) {
    is AgentIssue.Offline -> stringResource(R.string.web_issue_offline, issue.name)
    is AgentIssue.OriginNotAllowed -> stringResource(R.string.web_issue_origin, issue.name, issue.origin)
    is AgentIssue.Unsupported -> stringResource(R.string.web_issue_unsupported, issue.name)
    is AgentIssue.NoReply -> stringResource(R.string.web_issue_no_reply, issue.name)
}

private val PairQrError.message: Int
    get() = when (this) {
        PairQrError.NotPairing -> R.string.web_qr_not_pairing
        PairQrError.UnsupportedVersion -> R.string.web_qr_unsupported
        PairQrError.InsecureOrigin -> R.string.web_qr_insecure
        PairQrError.Malformed -> R.string.web_qr_malformed
    }

/** Met [bold] en gras dans [text] (l'hôte dans la question de confirmation, quelle que soit la langue). */
private fun withBold(text: String, bold: String): AnnotatedString = buildAnnotatedString {
    append(text)
    val start = text.indexOf(bold)
    if (start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + bold.length)
}

private fun Context.hasCamera(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
