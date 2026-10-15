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
    val autoOpacity: Boolean = false,
    val readability: Float = 0.5f,
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
            autoOpacity = p.autoOpacity,
            readability = p.readability,
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

    /** Average brightness and its spread ("busyness") of the photo inside [r]. */
    fun stats(photo: Bitmap?, r: RectF, screenW: Int, screenH: Int): Pair<Float, Float> {
        photo ?: return 0f to 0f
        val sx = photo.width.toFloat() / screenW
        val sy = photo.height.toFloat() / screenH
        val l = (r.left * sx).roundToInt().coerceIn(0, photo.width - 1)
        val t = (r.top * sy).roundToInt().coerceIn(0, photo.height - 1)
        val rr = (r.right * sx).roundToInt().coerceIn(l + 1, photo.width)
        val b = (r.bottom * sy).roundToInt().coerceIn(t + 1, photo.height)
        val stepX = max(1, (rr - l) / 16)
        val stepY = max(1, (b - t) / 16)
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in t until b step stepY) for (x in l until rr step stepX) {
            val v = ColorUtils.calculateLuminance(photo.getPixel(x, y))
            sum += v
            sumSq += v * v
            n++
        }
        if (n == 0) return 0f to 0f
        val mean = sum / n
        val sd = kotlin.math.sqrt(kotlin.math.max(0.0, sumSq / n - mean * mean))
        return mean.toFloat() to sd.toFloat()
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
            // Gentle at low settings: a hint of frost, not a block of blur.
            paint.alpha = (min(1f, 0.15f + look.bgOpacity * 1.1f) * 255).roundToInt()
            canvas.drawBitmap(blurred, null, Rect(0, 0, w, h), paint)
            paint.alpha = 255
            paint.color = ColorUtils.setAlphaComponent(tint, (look.bgOpacity * 0.7f * 255).roundToInt())
            canvas.drawRect(area, paint)
            canvas.restore()
        } else {
            paint.color = ColorUtils.setAlphaComponent(tint, (look.bgOpacity * 255).roundToInt())
            canvas.drawRoundRect(area, radius, radius, paint)
        }
    }

    /** A strongly blurred, small copy of the photo for frosted backgrounds. */
    fun blur(photo: Bitmap): Bitmap {
        // A copy at 1/8 size, blurred three times with a box blur: smooth when stretched back up
        // (an even smaller copy would show blocks on busy photos like trees).
        val mid = Bitmap.createScaledBitmap(photo, max(1, photo.width / 4), max(1, photo.height / 4), true)
        val small = Bitmap.createScaledBitmap(mid, max(1, photo.width / 8), max(1, photo.height / 8), true)
        if (mid !== photo && mid !== small) mid.recycle()
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) {
            boxBlur(px, w, h, radius = 5, horizontal = true)
            boxBlur(px, w, h, radius = 5, horizontal = false)
        }
        val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        if (small !== photo) small.recycle()
        return out
    }

    /** One pass of a box blur along rows or columns (running sum, so it's fast). */
    private fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val r = IntArray(len); val g = IntArray(len); val b = IntArray(len)
        for (line in 0 until lines) {
            fun idx(i: Int) = if (horizontal) line * w + i else i * w + line
            for (i in 0 until len) {
                val c = px[idx(i)]
                r[i] = (c shr 16) and 0xFF; g[i] = (c shr 8) and 0xFF; b[i] = c and 0xFF
            }
            var sr = 0; var sg = 0; var sb = 0
            val win = radius * 2 + 1
            for (k in -radius..radius) {
                val j = k.coerceIn(0, len - 1)
                sr += r[j]; sg += g[j]; sb += b[j]
            }
            for (i in 0 until len) {
                px[idx(i)] = (0xFF shl 24) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
                val out = (i - radius).coerceIn(0, len - 1)
                val inn = (i + radius + 1).coerceIn(0, len - 1)
                sr += r[inn] - r[out]; sg += g[inn] - g[out]; sb += b[inn] - b[out]
            }
        }
    }
}
