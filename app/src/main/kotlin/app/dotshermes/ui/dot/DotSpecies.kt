package app.dotshermes.ui.dot

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.TransformOrigin
import app.dotshermes.R

/**
 * Les trois mascottes. Toutes partagent le même jeu de calques et d'animations ; seuls changent
 * les dessins et les repères (fractions du cadre 640 px) imprimés par les scripts de rendu :
 * `render_mascot.py --app` pour le Galet, `render_trio.py --app` pour Nuage et Pavé.
 */
enum class DotSpecies(@StringRes val label: Int, @StringRes val accessoryLabel: Int, val signaturePalette: String) {
    Galet(R.string.species_galet, R.string.accessory_winged_cap, "tangerine"),
    Nuage(R.string.species_nuage, R.string.accessory_antenna, "sky"),
    Pave(R.string.species_pave, R.string.accessory_sprout, "grape"),
}

/** Accessoire de tête d'une seule pièce, qui se balance autour de sa base. */
internal class SwayingAccessory(@DrawableRes val res: Int, val pivot: TransformOrigin, val amplitude: Float)

internal class SpeciesArt(
    val bodyTop: Float,
    val bodyBottom: Float,
    val eyeY: Float,
    val mouthY: Float,
    val shadowY: Float,
    @DrawableRes val shadow: Int,
    @DrawableRes val eyesOpen: Int,
    @DrawableRes val eyesHappy: Int,
    @DrawableRes val eyesSleepy: Int,
    @DrawableRes val mouthSmile: Int,
    @DrawableRes val mouthO: Int,
    /** Petit sourire permanent au repos (Nuage, Pavé) ; le Galet n'a pas de bouche au repos. */
    @DrawableRes val mouthRest: Int?,
    /** Le pétase ailé du Galet a son propre montage (chapeau + deux ailes) ; les autres un seul calque. */
    val swaying: SwayingAccessory?,
    private val bodies: Map<String, Int>,
) {
    @DrawableRes
    fun body(paletteId: String): Int = bodies[paletteId] ?: bodies.getValue("tangerine")
}

internal val DotSpecies.art: SpeciesArt
    get() = when (this) {
        DotSpecies.Galet -> GaletArt
        DotSpecies.Nuage -> NuageArt
        DotSpecies.Pave -> PaveArt
    }

private val GaletArt = SpeciesArt(
    bodyTop = 0.3478f, bodyBottom = 0.85f, eyeY = 0.5935f, mouthY = 0.6421f, shadowY = 0.88f,
    shadow = R.drawable.dot_shadow,
    eyesOpen = R.drawable.dot_eyes_open, eyesHappy = R.drawable.dot_eyes_happy, eyesSleepy = R.drawable.dot_eyes_sleepy,
    mouthSmile = R.drawable.dot_mouth_smile, mouthO = R.drawable.dot_mouth_o, mouthRest = null,
    swaying = null,
    bodies = mapOf(
        "tangerine" to R.drawable.dot_body_tangerine, "bubblegum" to R.drawable.dot_body_bubblegum,
        "sky" to R.drawable.dot_body_sky, "lime" to R.drawable.dot_body_lime,
        "sun" to R.drawable.dot_body_sun, "grape" to R.drawable.dot_body_grape,
    ),
)

private val NuageArt = SpeciesArt(
    bodyTop = 0.3344f, bodyBottom = 0.8125f, eyeY = 0.6135f, mouthY = 0.6594f, shadowY = 0.835f,
    shadow = R.drawable.dot_nuage_shadow,
    eyesOpen = R.drawable.dot_nuage_eyes_open, eyesHappy = R.drawable.dot_nuage_eyes_happy, eyesSleepy = R.drawable.dot_nuage_eyes_sleepy,
    mouthSmile = R.drawable.dot_nuage_mouth_smile, mouthO = R.drawable.dot_nuage_mouth_o, mouthRest = R.drawable.dot_nuage_mouth_rest,
    // L'antenne part du sommet du lobe central : elle oscille comme un ressort.
    swaying = SwayingAccessory(R.drawable.dot_nuage_antenna, TransformOrigin(0.5f, 0.336f), amplitude = 9f),
    bodies = mapOf(
        "tangerine" to R.drawable.dot_nuage_body_tangerine, "bubblegum" to R.drawable.dot_nuage_body_bubblegum,
        "sky" to R.drawable.dot_nuage_body_sky, "lime" to R.drawable.dot_nuage_body_lime,
        "sun" to R.drawable.dot_nuage_body_sun, "grape" to R.drawable.dot_nuage_body_grape,
    ),
)

private val PaveArt = SpeciesArt(
    bodyTop = 0.3719f, bodyBottom = 0.8078f, eyeY = 0.6085f, mouthY = 0.6544f, shadowY = 0.813f,
    shadow = R.drawable.dot_pave_shadow,
    eyesOpen = R.drawable.dot_pave_eyes_open, eyesHappy = R.drawable.dot_pave_eyes_happy, eyesSleepy = R.drawable.dot_pave_eyes_sleepy,
    mouthSmile = R.drawable.dot_pave_mouth_smile, mouthO = R.drawable.dot_pave_mouth_o, mouthRest = R.drawable.dot_pave_mouth_rest,
    // La pousse plie à la base de sa tige, plus raide que l'antenne.
    swaying = SwayingAccessory(R.drawable.dot_pave_sprout, TransformOrigin(0.5f, 0.40f), amplitude = 6f),
    bodies = mapOf(
        "tangerine" to R.drawable.dot_pave_body_tangerine, "bubblegum" to R.drawable.dot_pave_body_bubblegum,
        "sky" to R.drawable.dot_pave_body_sky, "lime" to R.drawable.dot_pave_body_lime,
        "sun" to R.drawable.dot_pave_body_sun, "grape" to R.drawable.dot_pave_body_grape,
    ),
)
