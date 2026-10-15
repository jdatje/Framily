package nl.jdatje.framily.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import nl.jdatje.framily.data.Prefs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Readability and extras: backgrounds, outlines, night dimming, caption. */
data class LookStyle(
    val bgStyle: String,
    val bgOpacity: Float,
    val bgRound: Float,
    val bgColor: String,
    val bgClock: Boolean,
    val outline: Boolean,
    val nightDim: Boolean,
    val nightDimStrength: Float,
    val rainAlert: Boolean,
    val caption: Boolean,
    val captionX: Float,
    val captionY: Float,
) {
    companion object {
        fun from(p: Prefs) = LookStyle(
            bgStyle = p.bgStyle,
            bgOpacity = p.bgOpacity,
            bgRound = p.bgRound,
            bgColor = p.bgColor,
            bgClock = p.bgClock,
            outline = p.textOutline,
            nightDim = p.nightDim,
            nightDimStrength = p.nightDimStrength,
            rainAlert = p.rainAlert,
            caption = p.caption,
            captionX = p.captionX,
            captionY = p.captionY,
        )
    }
}

object Ink {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    /** Draws text, with a thin contrasting edge first when [outline] is on. */
    fun text(canvas: Canvas, s: String, x: Float, y: Float, paint: Paint, outline: Boolean) {
        if (outline) {
            stroke.typeface = paint.typeface
            stroke.textSize = paint.textSize
            stroke.textAlign = paint.textAlign
            stroke.strokeWidth = max(2f, paint.textSize * 0.07f)
            val dark = ColorUtils.calculateLuminance(paint.color or 0xFF000000.toInt()) < 0.4
            stroke.color = if (dark) 0xCCFFFFFF.toInt() else 0xCC000000.toInt()
            stroke.alpha = (Color.alpha(paint.color) * 0.85f).roundToInt()
            canvas.drawText(s, x, y, stroke)
        }
        canvas.drawText(s, x, y, paint)
    }

    /** Average brightness (0 dark … 1 light) of the photo inside [r]. */
    fun luminance(photo: Bitmap?, r: RectF, screenW: Int, screenH: Int): Float {
        photo ?: return 0f
        val sx = photo.width.toFloat() / screenW
        val sy = photo.height.toFloat() / screenH
        val l = (r.left * sx).roundToInt().coerceIn(0, photo.width - 1)
        val t = (r.top * sy).roundToInt().coerceIn(0, photo.height - 1)
        val rr = (r.right * sx).roundToInt().coerceIn(l + 1, photo.width)
        val b = (r.bottom * sy).roundToInt().coerceIn(t + 1, photo.height)
        var sum = 0.0
        var n = 0
        val stepX = max(1, (rr - l) / 12)
        val stepY = max(1, (b - t) / 12)
        for (y in t until b step stepY) for (x in l until rr step stepX) {
            sum += ColorUtils.calculateLuminance(photo.getPixel(x, y))
            n++
        }
        return if (n == 0) 0f else (sum / n).toFloat()
    }

    /** The rectangle a background is drawn in: [r] plus some breathing room. */
    fun padded(r: RectF, w: Int): RectF {
        val p = w * 0.025f
        return RectF(r.left - p * 1.4f, r.top - p, r.right + p * 1.4f, r.bottom + p)
    }

    /**
     * Draws a background behind an element.
     * - "pill": a solid rounded shape
     * - "frosted": the photo blurred and lightly tinted, like a frosted glass card
     */
    fun backdrop(canvas: Canvas, area: RectF, look: LookStyle, textIsDark: Boolean, blurred: Bitmap?, w: Int, h: Int) {
        if (look.bgStyle == "none" || look.bgOpacity <= 0f) return
        val tint = when (look.bgColor) {
            "dark" -> Color.BLACK
            "light" -> Color.WHITE
            else -> if (textIsDark) Color.WHITE else Color.BLACK
        }
        val radius = look.bgRound * min(area.width(), area.height()) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (look.bgStyle == "frosted" && blurred != null) {
            val path = Path().apply { addRoundRect(area, radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(path)
            paint.alpha = (min(1f, look.bgOpacity * 1.8f) * 255).roundToInt()
            canvas.drawBitmap(blurred, null, Rect(0, 0, w, h), paint)
            paint.alpha = 255
            paint.color = ColorUtils.setAlphaComponent(tint, (look.bgOpacity * 0.55f * 255).roundToInt())
            canvas.drawRect(area, paint)
            canvas.restore()
        } else {
            paint.color = ColorUtils.setAlphaComponent(tint, (look.bgOpacity * 255).roundToInt())
            canvas.drawRoundRect(area, radius, radius, paint)
        }
    }

    /** A strongly blurred, small copy of the photo for frosted backgrounds. */
    fun blur(photo: Bitmap): Bitmap {
        // Shrinking in steps and stretching back up gives a smooth blur, cheaply.
        val mid = Bitmap.createScaledBitmap(photo, max(1, photo.width / 6), max(1, photo.height / 6), true)
        val small = Bitmap.createScaledBitmap(mid, max(1, photo.width / 24), max(1, photo.height / 24), true)
        if (mid !== photo && mid !== small) mid.recycle()
        return small
    }
}
