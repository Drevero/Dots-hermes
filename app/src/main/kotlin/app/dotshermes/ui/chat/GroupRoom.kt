package app.dotshermes.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dotshermes.R
import app.dotshermes.data.model.SubDot
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.displayName
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.Hairline
import app.dotshermes.ui.components.PillButton
import app.dotshermes.ui.dot.ClayDot
import app.dotshermes.ui.dot.DotMood
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette

/** Autant d'invités que le gateway en accepte (ROOM_MAX_SPEAKERS) : au-delà, le débat devient long et coûteux. */
const val ROOM_MAX_SPEAKERS = 4

/** Tours de débat joués par le gateway avant que le dot de la conversation conclue (ROOM_ROUNDS). */
const val ROOM_ROUNDS = 2

/** Mascotte et nom de qui parle, au-dessus de sa bulle dans un salon de groupe. */
@Composable
fun SpeakerHeader(look: DotAppearance, talking: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ClayDot(look.palette, Modifier.size(30.dp), mood = if (talking) DotMood.Talking else DotMood.Idle,
            accessory = look.accessory, species = look.species, showShadow = false)
        Spacer(Modifier.width(6.dp))
        Text(look.displayName, style = MaterialTheme.typography.labelMedium, color = look.palette.deep, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Petites mascottes qui se chevauchent : le salon en un coup d'œil. */
@Composable
private fun MascotStack(looks: List<DotAppearance>, size: Dp) {
    Box(Modifier.width(size + size * 0.6f * (looks.size - 1).coerceAtLeast(0)).height(size)) {
        looks.forEachIndexed { index, look ->
            ClayDot(look.palette, Modifier.offset(x = size * 0.6f * index).size(size), accessory = look.accessory,
                species = look.species, showShadow = false)
        }
    }
}

/**
 * Bandeau au-dessus du champ de saisie tant que le salon est ouvert : qui est invité, qui conclut.
 * Un tap rouvre le choix des invités, la croix referme le salon.
 */
@Composable
fun RoomBar(invited: List<SubDot>, looks: Map<String, DotAppearance>, hostName: String, onOpen: () -> Unit, onClose: () -> Unit) {
    val palette = LocalDotPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Ink.Fill)
            .border(1.dp, palette.base.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MascotStack(invited.mapNotNull { looks[it.id] }, 26.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.room_bar_title, invited.joinToString(", ") { it.name }),
                style = MaterialTheme.typography.labelLarge, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(stringResource(R.string.room_bar_subtitle, hostName), style = MaterialTheme.typography.labelSmall, color = Ink.Secondary, maxLines = 1)
        }
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, stringResource(R.string.room_close), tint = Ink.Secondary, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Choix des sous-dots invités dans le salon de la conversation affichée. Rien ne part avant
 * « Ouvrir le salon » : on peut cocher, décocher, puis valider d'un geste.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomSheet(
    candidates: List<SubDot>,
    invited: List<SubDot>,
    looks: Map<String, DotAppearance>,
    hostName: String,
    onApply: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalDotPalette.current
    // Liste ordonnée : l'ordre des coches est l'ordre de parole.
    var picked by rememberSaveable { mutableStateOf(invited.map { it.id }) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.Grouped,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Ink.Tertiary) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.room_title), style = MaterialTheme.typography.headlineSmall, color = Ink.Primary, modifier = Modifier.padding(start = 4.dp))
            Text(
                stringResource(R.string.room_explain, ROOM_ROUNDS, hostName),
                style = MaterialTheme.typography.bodyMedium, color = Ink.Secondary,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 16.dp),
            )
            if (candidates.isEmpty()) {
                GroupedCard {
                    Text(stringResource(R.string.room_no_dots), style = MaterialTheme.typography.bodyMedium, color = Ink.Secondary, modifier = Modifier.padding(16.dp))
                }
                return@Column
            }
            GroupedCard {
                candidates.forEachIndexed { index, dot ->
                    if (index > 0) Hairline(startIndent = 64.dp)
                    val order = picked.indexOf(dot.id)
                    val full = picked.size >= ROOM_MAX_SPEAKERS
                    CandidateRow(
                        dot = dot,
                        look = looks[dot.id],
                        order = order.takeIf { it >= 0 }?.plus(1),
                        enabled = order >= 0 || !full,
                        onToggle = { picked = if (order >= 0) picked - dot.id else picked + dot.id },
                    )
                }
            }
            Text(
                pluralStringResource(R.plurals.room_limit, ROOM_MAX_SPEAKERS, ROOM_MAX_SPEAKERS),
                style = MaterialTheme.typography.labelSmall, color = Ink.Tertiary,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 20.dp),
            )
            PillButton(
                text = stringResource(if (invited.isEmpty()) R.string.room_open else R.string.room_update),
                onClick = { onApply(picked) },
                enabled = picked.isNotEmpty(),
                container = palette.base,
                content = palette.onBase,
                modifier = Modifier.fillMaxWidth(),
            )
            if (invited.isNotEmpty()) {
                PillButton(
                    text = stringResource(R.string.room_close),
                    onClick = { onApply(emptyList()) },
                    container = Ink.Fill,
                    content = Ink.Primary,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        }
    }
}

/** Une ligne cochable : mascotte, nom, spécialité ; le numéro dit à quel rang il prendra la parole. */
@Composable
private fun CandidateRow(dot: SubDot, look: DotAppearance?, order: Int?, enabled: Boolean, onToggle: () -> Unit) {
    val palette = LocalDotPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (look != null) {
            ClayDot(look.palette, Modifier.size(40.dp), mood = if (order != null) DotMood.Happy else DotMood.Idle,
                accessory = look.accessory, species = look.species, showShadow = false)
        } else {
            Spacer(Modifier.size(40.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(dot.name, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Ink.Primary else Ink.Tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (dot.mission.isNotBlank()) {
                Text(dot.mission, style = MaterialTheme.typography.labelSmall, color = Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (order != null) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(palette.base), contentAlignment = Alignment.Center) {
                Text("$order", style = MaterialTheme.typography.labelMedium, color = palette.onBase)
            }
        } else {
            Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = if (enabled) Ink.Secondary else Ink.Tertiary, modifier = Modifier.size(26.dp))
        }
    }
}
