package nl.jdatje.framily.wallpaper

import android.app.WallpaperColors
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt
import nl.jdatje.framily.data.PhotoDb
import nl.jdatje.framily.util.ImageUtils
import nl.jdatje.framily.weather.WeatherData

/** One prepared wallpaper: the photo, the people cut out of it, and its colors. */
class Frame(
    val photoId: Long,
    val bg: Bitmap?,
    val fg: Foreground?,
    val clockColors: PhotoColors,
    val colors: WallpaperColors?,
    /** Small blurred copy of the photo, for frosted backgrounds. */
    val blurred: Bitmap? = null,
)

/** Shared by the live wallpaper and the preview in the app, so they always look the same. */
object WallpaperRenderer {
    private const val TAG = "WallpaperRenderer"
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    suspend fun buildFrame(
        context: Context,
        photoId: Long,
        w: Int,
        h: Int,
        depth: Boolean,
        segmenter: () -> DepthSegmenter,
    ): Frame {
        val empty = Frame(photoId, null, null, PhotoColors.DEFAULT, null)
        if (photoId < 0) return empty
        val photo = PhotoDb.get(context).get(photoId) ?: return empty
        val bg = ImageUtils.renderPhoto(context, photo, w, h) ?: return empty
        val fg = if (depth) {
            try {
                segmenter().foreground(bg)
            } catch (e: Exception) {
                Log.w(TAG, "Segmentation failed: ${e.message}")
                null
            }
        } else null
        val colors = try {
            WallpaperColors.fromBitmap(bg)
        } catch (_: Exception) {
            null
        }
        return Frame(photoId, bg, fg, PhotoColors.from(bg), colors, Ink.blur(bg))
    }

    /** What goes on top of the photo. */
    class Overlay(
        val clockStyle: ClockStyle,
        val clock: ClockPainter,
        val weatherStyle: WeatherStyle,
        val weather: WeatherData?,
        val weatherPainter: WeatherPainter,
        val look: LookStyle,
        /** e.g. "June 2021 · Texel" for the photo on screen. */
        val caption: String?,
    )

    /** Where each element ended up (screen pixels); used by the Arrange screen and for taps. */
    class Placed(val clock: RectF?, val current: RectF?, val forecast: RectF?, val caption: RectF?)

    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL), 500, false)
    }

    /** Night per sunrise/sunset of the weather location, or 21:00–07:00 without weather. */
    fun isNight(weather: WeatherData?): Boolean =
        weather?.isNight() ?: java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY).let { it < 7 || it >= 21 }

    fun draw(canvas: Canvas, frame: Frame?, w: Int, h: Int, o: Overlay): Placed {
        val full = Rect(0, 0, w, h)
        val bg = frame?.bg
        val look = o.look
        val baseStyle = if (bg == null) o.clockStyle.copy(color = "white") else o.clockStyle
        val colors = frame?.clockColors ?: PhotoColors.DEFAULT
        if (bg == null) canvas.drawColor(0xFF1B1B1F.toInt()) else canvas.drawBitmap(bg, null, full, bitmapPaint)

        // Night dimming darkens the photo (and the people cut out of it), not the text.
        val dim = if (bg != null && look.nightDim && isNight(o.weather)) look.nightDimStrength.coerceIn(0f, 0.8f) else 0f
        val fgPaint = if (dim > 0f) {
            canvas.drawColor(ColorUtils.setAlphaComponent(android.graphics.Color.BLACK, (dim * 255).roundToInt()))
            val m = ((1f - dim) * 255).roundToInt()
            Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = LightingColorFilter(android.graphics.Color.rgb(m, m, m), 0) }
        } else bitmapPaint

        val fg = frame?.fg?.takeIf { baseStyle.depth }

        // Clock: at its set height, or automatically just behind the main person's head.
        val clockLayout = if (baseStyle.enabled) {
            val measured = o.clock.layout(w, h, baseStyle, 0f)
            val headTop = if (baseStyle.auto) fg?.personTop() else null
            val top = if (headTop != null) {
                // The head covers roughly the lower third of the clock, like on iPhones.
                (headTop * h - measured.rect.height() * 0.7f).coerceIn(h * 0.05f, h * 0.55f)
            } else {
                h * baseStyle.position
            }
            o.clock.layout(w, h, baseStyle, top)
        } else null

        // Weather: under the clock, beside it, or wherever it was dragged.
        val ws = o.weatherStyle
        val data = o.weather?.takeIf { ws.enabled }
        var current: WeatherPainter.Part? = null
        var forecast: WeatherPainter.Part? = null
        if (data != null) {
            val p = o.weatherPainter
            val alert = if (look.rainAlert) p.rainAlert(data) else null
            val clockRect = clockLayout?.rect
            val placement = if (clockRect == null && ws.placement != "custom") "custom" else ws.placement
            when (placement) {
                "under" -> {
                    val r = clockRect!!
                    current = p.measureCurrent(w, ws, baseStyle, data, vertical = false, alert).below(r.centerX(), r.bottom + w * 0.04f)
                    forecast = p.measureForecast(w, ws, data, vertical = false)?.below(r.centerX(), current.rect.bottom + w * 0.03f)
                }
                "sides" -> {
                    val r = clockRect!!
                    current = p.measureCurrent(w, ws, baseStyle, data, vertical = true, alert).at(r.left / 2f, r.centerY())
                    forecast = p.measureForecast(w, ws, data, vertical = true)?.at((r.right + w) / 2f, r.centerY())
                }
                else -> {
                    current = p.measureCurrent(w, ws, baseStyle, data, vertical = false, alert).at(ws.x * w, ws.y * h)
                    forecast = p.measureForecast(w, ws, data, ws.forecastVertical)?.at(ws.forecastX * w, ws.forecastY * h)
                }
            }
            current = current.keptInside(w, h)
            forecast = forecast?.keptInside(w, h)
        }

        // Caption: "June 2021 · Texel".
        val captionText = o.caption?.takeIf { look.caption && bg != null }
        val captionRect = captionText?.let {
            captionPaint.textSize = w * 0.036f
            val tw = captionPaint.measureText(it)
            val th = captionPaint.textSize * 1.3f
            val cx = (look.captionX * w).coerceIn(tw / 2 + w * 0.04f, w - tw / 2 - w * 0.04f)
            val cy = (look.captionY * h).coerceIn(th, h - th)
            RectF(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2)
        }

        // "Auto" color: light or dark text depending on the photo right behind each element.
        fun styleFor(r: RectF): ClockStyle =
            if (baseStyle.color == "auto") baseStyle.copy(color = if (Ink.luminance(bg, r, w, h) > 0.55f) "black" else "white") else baseStyle
        fun isDark(st: ClockStyle) = ColorUtils.calculateLuminance(ClockPainter.textColor(st, colors) or 0xFF000000.toInt()) < 0.4

        fun drawClock() = clockLayout?.let {
            val st = styleFor(it.rect)
            if (look.bgClock) Ink.backdrop(canvas, Ink.padded(it.rect, w), look, isDark(st), frame?.blurred, w, h)
            o.clock.draw(canvas, it, st, colors, look.outline)
        }
        fun drawCurrent() = current?.let {
            val st = styleFor(it.rect)
            Ink.backdrop(canvas, Ink.padded(it.rect, w), look, isDark(st), frame?.blurred, w, h)
            o.weatherPainter.drawCurrent(canvas, it, data!!, st, colors, look.outline)
        }
        fun drawForecast() = forecast?.let {
            val st = styleFor(it.rect)
            Ink.backdrop(canvas, Ink.padded(it.rect, w), look, isDark(st), frame?.blurred, w, h)
            o.weatherPainter.drawForecast(canvas, it, st, colors, look.outline)
        }
        fun drawCaption() = captionRect?.let { r ->
            val st = styleFor(r)
            Ink.backdrop(canvas, Ink.padded(r, w), look, isDark(st), frame?.blurred, w, h)
            captionPaint.color = ClockPainter.textColor(st, colors)
            captionPaint.setShadowLayer(captionPaint.textSize * 0.2f, 0f, captionPaint.textSize * 0.05f, ClockPainter.shadowColor(st, colors))
            val fm = captionPaint.fontMetrics
            Ink.text(canvas, captionText!!, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, captionPaint, look.outline)
        }

        // People go in front of each element, unless they'd hide too much of it.
        fun behind(r: RectF?, limit: Float) = r != null && fg != null && fg.coverage(r, w, h) < limit
        val clockBehind = behind(clockLayout?.rect, 0.45f)
        val currentBehind = behind(current?.rect, 0.25f)
        val forecastBehind = behind(forecast?.rect, 0.25f)
        val captionBehind = behind(captionRect, 0.25f)

        if (clockBehind) drawClock()
        if (currentBehind) drawCurrent()
        if (forecastBehind) drawForecast()
        if (captionBehind) drawCaption()
        if (fg != null && (clockBehind || currentBehind || forecastBehind || captionBehind)) {
            canvas.drawBitmap(fg.bitmap, null, full, fgPaint)
        }
        if (!clockBehind) drawClock()
        if (!currentBehind) drawCurrent()
        if (!forecastBehind) drawForecast()
        if (!captionBehind) drawCaption()

        return Placed(clockLayout?.rect, current?.rect, forecast?.rect, captionRect)
    }
}
