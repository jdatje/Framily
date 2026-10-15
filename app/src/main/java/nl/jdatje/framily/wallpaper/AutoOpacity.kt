package nl.jdatje.framily.wallpaper

import kotlin.math.max
import kotlin.math.min

/**
 * Picks text and background opacity per element, so text is readable on every photo
 * without covering more of the photo than needed.
 *
 * It looks at two things behind the element:
 * - **contrast** between the text color and the photo (white on a white sky = almost none)
 * - **busyness**: how much the brightness varies (leaves, crowds) — busy spots need more help
 *
 * [target] is the "Subtle ↔ Very clear" slider (0..1).
 */
object AutoOpacity {

    class Result(val text: Float, val background: Float)

    /** WCAG contrast ratio between two relative luminances (1 = none … 21 = black on white). */
    fun contrast(a: Float, b: Float): Float = (max(a, b) + 0.05f) / (min(a, b) + 0.05f)

    /**
     * @param photoLum average brightness behind the element (0..1)
     * @param busyness spread of brightness behind it (standard deviation, ~0..0.5)
     * @param textLum brightness of the text color (0..1)
     */
    fun pick(photoLum: Float, busyness: Float, textLum: Float, target: Float): Result {
        // How much contrast we want: a subtle look accepts less, and busy spots need more.
        val wanted = (2.2f + 4.3f * target) * (1f + busyness * 1.6f)
        val have = contrast(photoLum, textLum)
        val shortfall = ((wanted - have) / wanted).coerceIn(0f, 1f)   // 0 = enough contrast
        val surplus = ((have - wanted) / wanted).coerceIn(0f, 1f)     // 0 = just enough

        // Text: more solid when contrast is short, more see-through when there's plenty.
        val text = if (shortfall > 0f) 0.85f + 0.15f * shortfall else 0.85f - 0.35f * surplus
        // Background: only as much as the text can't do by itself.
        val background = (shortfall * 1.1f + busyness * 0.9f + 0.08f * target - surplus * 0.35f).coerceIn(0f, 0.85f)
        return Result(text.coerceIn(0.5f, 1f), background)
    }
}
