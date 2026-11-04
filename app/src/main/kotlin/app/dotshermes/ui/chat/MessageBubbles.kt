package app.dotshermes.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.dotshermes.data.model.ChatMessage
import app.dotshermes.data.model.Delivery
import app.dotshermes.data.model.MessageContent
import app.dotshermes.data.model.Sender
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import app.dotshermes.voice.Playback
import androidx.compose.ui.res.stringResource
import app.dotshermes.R
import app.dotshermes.ui.formatTime
import app.dotshermes.ui.DotAppearance

private val QuickReactions = listOf("❤️", "👍", "😂", "😮", "🎉", "👀")

@Composable
fun MessageRow(
    row: ChatRow.Bubble,
    onReact: (String) -> Unit,
    onApproval: (Boolean) -> Unit,
    onClarify: (String) -> Unit,
    playback: Playback = Playback(),
    onToggleVoice: () -> Unit = {},
    onSeekVoice: (Float) -> Unit = {},
    attachments: AttachmentControls? = null,
    onTakeover: () -> Unit = {},
    /** Salon de groupe : la mascotte de qui parle, posée au-dessus de la bulle ; null hors salon. */
    speakerLook: DotAppearance? = null,
) {
    val message = row.message
    val fromUser = message.sender == Sender.User
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = if ((row.groupedWithPrev || row.afterTools) && speakerLook == null) 4.dp else 12.dp),
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
    ) {
        speakerLook?.let { SpeakerHeader(it) }
        when (val content = message.content) {
            is MessageContent.Text -> TextBubble(message, content.text, fromUser, row.groupedWithPrev, row.groupedWithNext, onReact)
            is MessageContent.Task -> TaskCard(content, Modifier.fillMaxWidth(0.86f))
            is MessageContent.Approval -> ApprovalCard(content, onApproval, Modifier.fillMaxWidth(0.86f))
            is MessageContent.Clarify -> ClarifyCard(content, onClarify, Modifier.fillMaxWidth(0.86f))
            is MessageContent.Voice -> VoiceBubble(message.id, content, fromUser, playback, onToggleVoice, onSeekVoice)
            is MessageContent.File -> attachments?.let { FileBubble(message.id, content, fromUser, it) }
            is MessageContent.Takeover -> TakeoverCard(content, onTakeover, Modifier.fillMaxWidth(0.86f))
        }
        if (row.showReceipt) {
            Text(
                text = receiptLabel(message),
                style = MaterialTheme.typography.labelSmall,
                color = Ink.Secondary,
                modifier = Modifier.padding(top = 4.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun receiptLabel(message: ChatMessage) = when (message.delivery) {
    Delivery.Sending -> stringResource(R.string.receipt_sending)
    Delivery.Sent -> stringResource(R.string.receipt_sent)
    Delivery.Read -> stringResource(R.string.receipt_read, formatTime(message.sentAt))
}

/**
 * Bulle de texte. Les coins côté auteur se resserrent quand les bulles sont groupées (façon iMessage).
 * Appui long : barre de réactions ; double tap : ❤️ direct.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextBubble(
    message: ChatMessage,
    text: String,
    fromUser: Boolean,
    groupedWithPrev: Boolean,
    groupedWithNext: Boolean,
    onReact: (String) -> Unit,
) {
    val palette = LocalDotPalette.current
    val haptics = LocalHapticFeedback.current
    var pickerOpen by remember { mutableStateOf(false) }

    val round = 20.dp
    val tight = 6.dp
    val shape = if (fromUser) {
        RoundedCornerShape(topStart = round, topEnd = if (groupedWithPrev) tight else round, bottomEnd = if (groupedWithNext) tight else round, bottomStart = round)
    } else {
        RoundedCornerShape(topStart = if (groupedWithPrev) tight else round, topEnd = round, bottomEnd = round, bottomStart = if (groupedWithNext) tight else round)
    }
    val reactions = message.reactions.values.distinct()

    Box {
        Box(
            modifier = Modifier
                .padding(top = if (reactions.isNotEmpty()) 16.dp else 0.dp)
                .widthIn(max = 296.dp)
                .clip(shape)
                .background(if (fromUser) palette.base else Ink.AgentBubble)
                .combinedClickable(
                    onClick = {},
                    onDoubleClick = { onReact("❤️") },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        pickerOpen = true
                    },
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            val ink = if (fromUser) palette.onBase else Ink.Primary
            // L'utilisateur tape du texte simple ; l'agent répond en markdown complet.
            if (fromUser) {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = ink)
            } else {
                MarkdownText(text, ink = ink, link = palette.deep)
            }
        }
        if (reactions.isNotEmpty()) {
            // Comme dans Dots : la réaction mord sur le coin supérieur, côté opposé à l'alignement.
            ReactionBadge(
                emojis = reactions,
                modifier = Modifier
                    .align(if (fromUser) Alignment.TopStart else Alignment.TopEnd)
                    .offset(x = if (fromUser) (-8).dp else 8.dp),
            )
        }
        if (pickerOpen) {
            ReactionPicker(
                selected = message.reactions[Sender.User],
                anchorEnd = fromUser,
                onPick = { emoji ->
                    pickerOpen = false
                    onReact(emoji)
                },
                onDismiss = { pickerOpen = false },
            )
        }
    }
}

@Composable
private fun ReactionBadge(emojis: List<String>, modifier: Modifier = Modifier) {
    val pop = remember { Animatable(0.4f) }
    LaunchedEffect(emojis) {
        pop.snapTo(0.4f)
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 420f))
    }
    Row(
        modifier = modifier
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(Ink.Raised)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        emojis.forEach { Text(it, fontSize = 15.sp) }
    }
}

@Composable
private fun ReactionPicker(
    selected: String?,
    anchorEnd: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalDotPalette.current
    val offsetY = with(LocalDensity.current) { -64.dp.roundToPx() }
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    Popup(
        alignment = if (anchorEnd) Alignment.TopEnd else Alignment.TopStart,
        offset = IntOffset(0, offsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = visible,
            enter = scaleIn(initialScale = 0.7f, transformOrigin = TransformOrigin(if (anchorEnd) 1f else 0f, 1f)) + fadeIn(),
        ) {
            Row(
                modifier = Modifier
                    .padding(8.dp)
                    .shadow(16.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Ink.Raised)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                QuickReactions.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (emoji == selected) palette.light else Color.Transparent)
                            .clickable { onPick(emoji) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emoji, fontSize = 22.sp)
                    }
                }
            }
        }
    }
}

/** Les trois points qui rebondissent pendant que l'agent écrit ; en salon, sous la mascotte de [speaker] qui parle. */
@Composable
fun TypingBubble(speaker: DotAppearance? = null) {
    Column(Modifier.padding(top = 12.dp)) {
        speaker?.let { SpeakerHeader(it, talking = true) }
        TypingDots()
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Ink.AgentBubble)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) { index ->
            val lift by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    keyframes {
                        durationMillis = 1_200
                        0f at index * 160
                        1f at index * 160 + 280
                        0f at index * 160 + 560
                    },
                ),
                label = "typing-dot-$index",
            )
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        translationY = -lift * 4.dp.toPx()
                        alpha = 0.4f + 0.6f * lift
                    }
                    .background(Ink.Secondary, CircleShape),
            )
        }
    }
}
