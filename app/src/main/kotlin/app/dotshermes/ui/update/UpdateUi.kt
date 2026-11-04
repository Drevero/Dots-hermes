package app.dotshermes.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dotshermes.R
import app.dotshermes.update.UpdateState
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import kotlin.math.roundToInt

/**
 * Dialogue de mise à jour : il suit [state] de la proposition jusqu'à l'installation. Pendant le
 * téléchargement, « Masquer » le ferme sans rien interrompre.
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    currentVersion: String,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalDotPalette.current
    val title: String
    val confirm: Pair<String, () -> Unit>?
    val dismiss: Pair<String, () -> Unit>
    val body: @Composable () -> Unit
    when (state) {
        is UpdateState.Available -> {
            title = stringResource(R.string.update_title)
            body = { Text(stringResource(R.string.update_available_text, state.release.version, currentVersion)) }
            confirm = stringResource(R.string.update_action_install) to onUpdate
            dismiss = stringResource(R.string.update_action_later) to onLater
        }
        is UpdateState.NeedsPermission -> {
            title = stringResource(R.string.update_permission_title)
            body = { Text(stringResource(R.string.update_permission_text)) }
            confirm = stringResource(R.string.update_action_open_settings) to onOpenSettings
            dismiss = stringResource(R.string.update_action_later) to onLater
        }
        is UpdateState.Downloading -> {
            title = stringResource(R.string.update_title)
            body = {
                Column {
                    Text(stringResource(R.string.update_downloading, (state.progress * 100).roundToInt()))
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = palette.base, trackColor = Ink.Fill,
                    )
                }
            }
            confirm = null
            dismiss = stringResource(R.string.update_action_hide) to onClose
        }
        is UpdateState.Installing -> {
            title = stringResource(R.string.update_title)
            body = { Text(stringResource(R.string.update_installing)) }
            confirm = null
            dismiss = stringResource(R.string.update_action_hide) to onClose
        }
        is UpdateState.Failed -> {
            title = stringResource(R.string.update_failed_title)
            body = { Text(state.reason) }
            confirm = if (state.release != null) stringResource(R.string.update_action_retry) to onUpdate else null
            dismiss = stringResource(R.string.action_close) to onClose
        }
        else -> return
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = body,
        confirmButton = {
            confirm?.let { (label, action) -> TextButton(onClick = action) { Text(label, color = palette.deep) } }
        },
        dismissButton = { TextButton(onClick = dismiss.second) { Text(dismiss.first, color = Ink.Primary) } },
        containerColor = Ink.Raised,
        titleContentColor = Ink.Primary,
        textContentColor = Ink.Secondary,
    )
}

/** Ce que la ligne « Version » des réglages affiche sous le numéro, selon [state]. */
@Composable
fun updateStatusText(state: UpdateState, enabled: Boolean): String = when {
    !enabled -> stringResource(R.string.update_status_disabled)
    state is UpdateState.Checking -> stringResource(R.string.update_status_checking)
    state is UpdateState.UpToDate -> stringResource(R.string.update_status_up_to_date)
    state is UpdateState.Available -> stringResource(R.string.update_status_available, state.release.version)
    state is UpdateState.NeedsPermission -> stringResource(R.string.update_status_available, state.release.version)
    state is UpdateState.Downloading -> stringResource(R.string.update_downloading, (state.progress * 100).roundToInt())
    state is UpdateState.Installing -> stringResource(R.string.update_status_installing)
    state is UpdateState.Failed -> state.reason
    else -> stringResource(R.string.update_status_check)
}
