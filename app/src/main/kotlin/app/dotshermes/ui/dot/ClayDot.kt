package app.dotshermes.ui.dot

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import app.dotshermes.R
import app.dotshermes.ui.theme.DotPalette
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import androidx.annotation.StringRes

enum class DotMood { Idle, Thinking, Talking, Happy, Sleeping }

/** L'accessoire « signature » de chaque espèce (pétase, antenne, pousse) ou rien. */
enum class DotAccessory { WingedCap, None }

@StringRes
fun DotAccessory.labelFor(species: DotSpecies): Int =
    if (this == DotAccessory.WingedCap) species.accessoryLabel else R.string.accessory_none

private const val TWO_PI = (2 * PI).toFloat()

// Pivots des ailes du Galet (fractions du cadre), imprimés par `render_mascot.py --app`.
private val WingLeftPivot = TransformOrigin(0.4003f, 0.3326f)
private val WingRightPivot = TransformOrigin(0.5933f, 0.3094f)

/**
 * Mascotte en pâte à modeler ([DotSpecies]) : des calques pré-rendus en 3D (corps par couleur, yeux, bouche,
 * accessoire) superposés puis animés indépendamment — flottement, écrasement au rebond,
 * clignement, regard, battement d'ailes. Chaque [DotMood] a son propre mouvement.
 */
@Composable
fun ClayDot(
    palette: DotPalette,
    modifier: Modifier = Modifier,
    mood: DotMood = DotMood.Idle,
    accessory: DotAccessory = DotAccessory.WingedCap,
    showShadow: Boolean = true,
    species: DotSpecies = DotSpecies.Galet,
) {
    val art = species.art
    // Une seule horloge cyclique ; chaque humeur utilise des harmoniques entières pour boucler sans saut.
    val phase by rememberInfiniteTransition(label = "clay-dot").animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(tween(durationMillis = 4_800, easing = LinearEasing)),
        label = "phase",
    )

    val blink = remember { Animatable(1f) }
    LaunchedEffect(mood) {
        if (mood == DotMood.Sleeping) return@LaunchedEffect
        val random = Random(palette.id.hashCode())
        while (true) {
            delay(2_000L + random.nextLong(2_800L))
            blink.animateTo(0.1f, tween(70))
            blink.animateTo(1f, tween(130))
        }
    }

    // Poids animés plutôt qu'un switch : les humeurs se fondent l'une dans l'autre.
    val idle by animateFloatAsState(if (mood == DotMood.Idle) 1f else 0f, tween(500), label = "idle")
    val thinking by animateFloatAsState(if (mood == DotMood.Thinking) 1f else 0f, tween(500), label = "thinking")
    val talking by animateFloatAsState(if (mood == DotMood.Talking) 1f else 0f, tween(400), label = "talking")
    val happy by animateFloatAsState(if (mood == DotMood.Happy) 1f else 0f, tween(400), label = "happy")
    val sleeping by animateFloatAsState(if (mood == DotMood.Sleeping) 1f else 0f, tween(700), label = "sleeping")
    val lookX by animateFloatAsState(if (mood == DotMood.Thinking) 1f else 0f, spring(0.6f, 120f), label = "lookX")
    val lookY by animateFloatAsState(if (mood == DotMood.Thinking) -1f else 0f, spring(0.6f, 120f), label = "lookY")

    // Lus uniquement dans les lambdas graphicsLayer : l'animation ne recompose pas, elle redessine.
    fun lift() = idle * sin(phase) * 0.016f +
        thinking * (0.5f + 0.5f * sin(2 * phase)) * 0.05f +
        talking * abs(sin(4 * phase)) * 0.03f +
        happy * abs(sin(3 * phase)) * 0.075f

    fun squash() = 0.018f * sin(2 * phase) * (1f - 0.6f * sleeping) +
        happy * (1f - abs(sin(3 * phase))).pow(4) * 0.09f +
        talking * (1f - abs(sin(4 * phase))).pow(4) * 0.04f

    /** Suit le corps : un point à la hauteur [anchorY] descend quand le corps s'écrase vers sa base. */
    fun GraphicsLayerScope.followBody(anchorY: Float) {
        translationY = (-lift() + squash() * (art.bodyBottom - anchorY)) * size.height
    }

    val desaturate = if (sleeping > 0f) {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1f - 0.65f * sleeping) })
    } else {
        null
    }
    val layer = Modifier.fillMaxSize()

    Box(modifier) {
        if (showShadow) {
            Image(
                painterResource(art.shadow), null,
                layer.graphicsLayer {
                    val k = (1f - lift() * 4f).coerceIn(0.6f, 1f)
                    scaleX = k
                    scaleY = k
                    alpha = k
                    transformOrigin = TransformOrigin(0.5f, art.shadowY)
                },
            )
        }

        Crossfade(targetState = art.body(palette.id), animationSpec = tween(450), label = "body", modifier = layer) { res ->
            Image(
                painterResource(res), null,
                layer.graphicsLayer {
                    scaleX = 1f + squash()
                    scaleY = 1f - squash()
                    translationY = -lift() * size.height
                    transformOrigin = TransformOrigin(0.5f, art.bodyBottom)
                },
                colorFilter = desaturate,
            )
        }

        // Yeux : ouverts (qui clignent et suivent le regard), plissés de joie, ou clos.
        val eyesOpen = (1f - happy - sleeping).coerceIn(0f, 1f)
        Image(
            painterResource(art.eyesOpen), null,
            layer.graphicsLayer {
                followBody(art.eyeY)
                translationX = (lookX * 0.025f + talking * 0.01f * sin(phase)) * size.width
                translationY += lookY * 0.02f * size.height
                scaleY = blink.value
                transformOrigin = TransformOrigin(0.5f, art.eyeY)
                alpha = eyesOpen
            },
        )
        Image(painterResource(art.eyesHappy), null, layer.graphicsLayer { followBody(art.eyeY); alpha = happy })
        Image(painterResource(art.eyesSleepy), null, layer.graphicsLayer { followBody(art.eyeY); alpha = sleeping })

        art.mouthRest?.let { rest ->
            Image(painterResource(rest), null, layer.graphicsLayer { followBody(art.mouthY); alpha = (idle + sleeping).coerceIn(0f, 1f) })
        }
        Image(painterResource(art.mouthSmile), null, layer.graphicsLayer { followBody(art.mouthY); alpha = happy })
        Image(
            painterResource(art.mouthO), null,
            layer.graphicsLayer {
                followBody(art.mouthY)
                alpha = maxOf(thinking, talking)
                // En parlant, la bouche s'ouvre et se ferme au rythme des petits rebonds.
                val open = 1f + talking * (abs(sin(4 * phase)) * 0.9f - 0.3f)
                scaleX = open
                scaleY = open
                transformOrigin = TransformOrigin(0.5108f, art.mouthY)
            },
        )

        val swaying = art.swaying
        if (accessory == DotAccessory.WingedCap && swaying != null) {
            // Balancement en retard sur le rebond : l'accessoire semble souple, entraîné par le corps.
            Image(
                painterResource(swaying.res), null,
                layer.graphicsLayer {
                    followBody(art.bodyTop)
                    rotationZ = swaying.amplitude * (0.5f * sin(phase - 0.6f) + happy * sin(3 * phase - 0.8f) + talking * 0.6f * sin(4 * phase - 0.8f) + thinking * 0.4f * sin(2 * phase))
                    transformOrigin = swaying.pivot
                },
            )
        } else if (accessory == DotAccessory.WingedCap) {
            fun flap() = sin(2 * phase) * 5f + thinking * sin(6 * phase) * 8f + happy * sin(6 * phase) * 12f
            Image(
                painterResource(R.drawable.dot_wing_left), null,
                layer.graphicsLayer { followBody(art.bodyTop); rotationZ = flap(); transformOrigin = WingLeftPivot },
            )
            Image(
                painterResource(R.drawable.dot_wing_right), null,
                layer.graphicsLayer { followBody(art.bodyTop); rotationZ = -flap(); transformOrigin = WingRightPivot },
            )
            Image(painterResource(R.drawable.dot_cap), null, layer.graphicsLayer { followBody(art.bodyTop) })
        }
    }
}
