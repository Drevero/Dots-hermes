package app.dotshermes.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dotshermes.data.model.ApprovalStatus
import app.dotshermes.data.model.MessageContent
import app.dotshermes.data.model.StepState
import app.dotshermes.data.model.TaskStep
import app.dotshermes.ui.components.PillButton
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import app.dotshermes.R

private val CardShape = RoundedCornerShape(22.dp)

private fun Modifier.agentCard() = this
    .clip(CardShape)
    .background(Ink.Raised)
    .border(1.dp, Ink.Separator, CardShape)
    .padding(16.dp)

/** Travail de fond de l'agent : barre de progression + étapes, mise à jour en direct. */
@Composable
fun TaskCard(task: MessageContent.Task, modifier: Modifier = Modifier) {
    val palette = LocalDotPalette.current
    val progress by animateFloatAsState(task.doneCount / task.steps.size.toFloat(), tween(600), label = "task-progress")

    Column(modifier.agentCard()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(palette.light), contentAlignment = Alignment.Center) {
                if (task.isComplete) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.deep, modifier = Modifier.size(18.dp))
                } else {
                    CircularProgressIndicator(Modifier.size(16.dp), color = palette.base, strokeWidth = 2.dp, trackColor = Color.Transparent)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (task.isComplete) R.string.task_done else R.string.task_running), style = MaterialTheme.typography.labelMedium, color = Ink.Secondary)
                Text(task.title, style = MaterialTheme.typography.titleSmall, color = Ink.Primary)
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Ink.Fill)) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(CircleShape).background(palette.base))
        }
        Text(
            pluralStringResource(R.plurals.task_progress, task.steps.size, task.doneCount, task.steps.size),
            style = MaterialTheme.typography.labelSmall,
            color = Ink.Secondary,
            modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
        )
        task.steps.forEach { StepRow(it) }
    }
}

@Composable
private fun StepRow(step: TaskStep) {
    val palette = LocalDotPalette.current
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (step.state) {
                StepState.Done -> Box(Modifier.size(18.dp).clip(CircleShape).background(palette.base), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.onBase, modifier = Modifier.size(12.dp))
                }
                StepState.Running -> CircularProgressIndicator(Modifier.size(16.dp), color = palette.base, strokeWidth = 2.dp, trackColor = palette.light)
                StepState.Pending -> Box(Modifier.size(16.dp).border(1.5.dp, Ink.Tertiary, CircleShape))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            step.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (step.state == StepState.Pending) Ink.Secondary else Ink.Primary,
        )
    }
}

/** Demande de feu vert : l'agent n'agit qu'après réponse, et la carte garde la décision visible. */
@Composable
fun ApprovalCard(approval: MessageContent.Approval, onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalDotPalette.current
    val pending = approval.status == ApprovalStatus.Pending

    Column(modifier.agentCard()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (pending) Ink.Warning else Ink.Tertiary, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (pending) R.string.approval_required else R.string.approval_decided),
                style = MaterialTheme.typography.labelMedium,
                color = if (pending) Ink.Warning else Ink.Secondary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(approval.title.ifBlank { stringResource(R.string.approval_default_title) }, style = MaterialTheme.typography.titleSmall, color = Ink.Primary)
        Spacer(Modifier.height(10.dp))
        Text(
            approval.detail,
            style = MaterialTheme.typography.bodySmall,
            color = Ink.Secondary,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Grouped).padding(12.dp),
        )
        Spacer(Modifier.height(14.dp))
        AnimatedContent(
            targetState = approval.status,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
            label = "approval-status",
        ) { status ->
            when (status) {
                ApprovalStatus.Pending -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(stringResource(R.string.action_decline), { onAnswer(false) }, Modifier.weight(1f), container = Ink.Fill, content = Ink.Primary, height = 42.dp)
                    PillButton(stringResource(R.string.action_approve), { onAnswer(true) }, Modifier.weight(1f), container = palette.base, content = palette.onBase, height = 42.dp)
                }
                ApprovalStatus.Approved -> StatusLine(Icons.Rounded.CheckCircle, stringResource(R.string.approval_approved), Ink.Success)
                ApprovalStatus.Declined -> StatusLine(Icons.Rounded.Cancel, stringResource(R.string.approval_declined), Ink.Secondary)
            }
        }
    }
}

/** Question de l'agent (outil `clarify`) : un bouton par choix, ou réponse libre dans le fil. */
@Composable
fun ClarifyCard(clarify: MessageContent.Clarify, onAnswer: (String) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalDotPalette.current
    Column(modifier.agentCard()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (clarify.answer == null) palette.base else Ink.Tertiary, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.clarify_question), style = MaterialTheme.typography.labelMedium, color = Ink.Secondary)
        }
        Spacer(Modifier.height(8.dp))
        Text(clarify.question, style = MaterialTheme.typography.titleSmall, color = Ink.Primary)
        Spacer(Modifier.height(12.dp))
        when {
            clarify.answer != null -> StatusLine(Icons.Rounded.CheckCircle, clarify.answer, palette.deep)
            clarify.choices.isEmpty() -> Text(
                stringResource(R.string.clarify_free_answer),
                style = MaterialTheme.typography.bodySmall,
                color = Ink.Secondary,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                clarify.choices.forEach { choice ->
                    PillButton(choice, { onAnswer(choice) }, Modifier.fillMaxWidth(), container = palette.light, content = palette.deep, height = 42.dp)
                }
            }
        }
    }
}

@Composable
private fun StatusLine(icon: ImageVector, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** L'agent est bloqué sur une page et attend : un bouton ouvre son écran directement en prise en main. */
@Composable
fun TakeoverCard(takeover: MessageContent.Takeover, onTakeover: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalDotPalette.current
    Column(modifier.agentCard()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (takeover.pending) Ink.Warning else Ink.Tertiary, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (takeover.pending) R.string.takeover_needed else R.string.takeover_handed_back),
                style = MaterialTheme.typography.labelMedium,
                color = if (takeover.pending) Ink.Warning else Ink.Secondary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(takeover.reason, style = MaterialTheme.typography.titleSmall, color = Ink.Primary)
        Spacer(Modifier.height(14.dp))
        if (takeover.pending) {
            PillButton(stringResource(R.string.action_take_over), onTakeover, Modifier.fillMaxWidth(), container = palette.base, content = palette.onBase, height = 42.dp)
            Text(
                stringResource(R.string.takeover_hint),
                style = MaterialTheme.typography.labelSmall,
                color = Ink.Secondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            StatusLine(Icons.Rounded.CheckCircle, stringResource(R.string.takeover_resumed), Ink.Success)
        }
    }
}
