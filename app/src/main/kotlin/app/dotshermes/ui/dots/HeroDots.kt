package app.dotshermes.ui.dots

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import kotlin.math.sign
import app.dotshermes.data.model.DotKind
import app.dotshermes.data.model.MAIN_CHAT
import app.dotshermes.data.model.SubDot
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.dot.ClayDot
import app.dotshermes.ui.dot.DotMood
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Un dot tel que le hero de la conversation le montre : sa conversation et son apparence. */
data class HeroDot(val chat: String, val look: DotAppearance)

/** Ordre unique des dots (carrousel de la fiche, hero) : principal, permanents, puis temporaires récents. */
fun orderedDots(dots: List<SubDot>): List<SubDot> =
    dots.sortedWith(compareBy<SubDot> { it.kind != DotKind.Permanent }.thenByDescending { it.lastActivityAt ?: it.createdAt })

fun heroDots(main: DotAppearance, dots: List<SubDot>): List<HeroDot> =
    listOf(HeroDot(MAIN_CHAT, main)) + orderedDots(dots).map { HeroDot(it.id, it.appearance(main)) }

/** Dot voisin (précédent à gauche, suivant à droite) : décalé, réduit, en transparence, derrière. */
private const val SideOffset = 0.48f
private const val SideScale = 0.72f
private const val SideAlpha = 0.32f

/**
 * Mascotte du hero de la conversation. Le dot précédent dépasse à gauche, le suivant à droite, en
 * transparence derrière ; glisser la mascotte fait défiler les dots, la position suit le doigt puis
 * se pose sur le dot le plus proche (qui devient la conversation affichée).
 *
 * Chaque dot garde sa propre mascotte (clé = sa conversation) et ne fait que se déplacer : rien ne
 * change d'apparence pendant la bascule, donc rien ne clignote. Une seule valeur, [center] (l'index
 * du dot au centre, fractionnaire pendant le geste), donne à chacun sa place, sa taille et son opacité.
 */
@Composable
fun HeroDots(
    dots: List<HeroDot>,
    current: String,
    mood: DotMood,
    size: Dp,
    onSwitch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = dots.indexOfFirst { it.chat == current }.coerceAtLeast(0)
    val sizePx = with(LocalDensity.current) { size.toPx() }
    // Glisser d'une demi-mascotte avance d'un dot : le voisin arrive au centre sous le doigt.
    val stepPx = sizePx * SideOffset
    val center = remember { Animatable(index.toFloat()) }
    val scope = rememberCoroutineScope()
    val latestDots by rememberUpdatedState(dots)
    val latestSwitch by rememberUpdatedState(onSwitch)
    val latestCurrent by rememberUpdatedState(current)

    // Dot changé ailleurs (fiche, notification) ou bascule déjà demandée : on glisse jusqu'à lui.
    LaunchedEffect(index) { center.animateTo(index.toFloat(), spring(dampingRatio = 0.85f, stiffness = 260f)) }

    Box(
        modifier
            .size(size)
            .pointerInput(dots.size) {
                if (dots.size < 2) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            val last = latestDots.lastIndex
                            val target = center.value.roundToInt().coerceIn(0, last)
                            val chat = latestDots[target].chat
                            // La conversation bascule tout de suite ; l'animation, elle, finit de poser le dot.
                            if (chat != latestCurrent) latestSwitch(chat)
                            center.animateTo(target.toFloat(), spring(dampingRatio = 0.85f, stiffness = 260f))
                        }
                    },
                    onDragCancel = { scope.launch { center.animateTo(center.value.roundToInt().toFloat()) } },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        val last = latestDots.lastIndex.toFloat()
                        var next = center.value - amount / stepPx
                        // Au-delà du premier ou du dernier dot, le geste résiste (élastique).
                        if (next < 0f) next = center.value - amount / stepPx * 0.25f
                        if (next > last) next = center.value - amount / stepPx * 0.25f
                        scope.launch { center.snapTo(next.coerceIn(-0.4f, last + 0.4f)) }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        dots.forEachIndexed { i, dot ->
            // Seuls le dot au centre et ses voisins (plus celui qui arrive) sont dessinés.
            if (abs(i - index) > 2) return@forEachIndexed
            key(dot.chat) {
                ClayDot(
                    dot.look.palette,
                    Modifier
                        .size(size)
                        .zIndex(-abs(i - center.value))
                        .graphicsLayer {
                            val r = i - center.value
                            val d = abs(r)
                            // 0 → 1 : du centre à la place de voisin ; 1 → 2 : de voisin à invisible.
                            val near = d.coerceAtMost(1f)
                            val far = (d - 1f).coerceIn(0f, 1f)
                            translationX = sign(r) * sizePx * (SideOffset * near + 0.3f * far)
                            val scale = 1f - (1f - SideScale) * near - 0.12f * far
                            scaleX = scale
                            scaleY = scale
                            alpha = (1f - (1f - SideAlpha) * near) * (1f - far)
                        },
                    mood = if (i == index) mood else DotMood.Idle,
                    accessory = dot.look.accessory,
                    species = dot.look.species,
                )
            }
        }
    }
}
