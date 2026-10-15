package nl.jdatje.framily.wallpaper

import android.app.WallpaperColors
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
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
        return Frame(photoId, bg, fg, PhotoColors.from(bg), colors)
    }

    /** What goes on top of the photo. */
    class Overlay(
        val clockStyle: ClockStyle,
        val clock: ClockPainter,
        val weatherStyle: WeatherStyle,
        val weather: WeatherData?,
        val weatherPainter: WeatherPainter,
    )

    /** Where each element ended up (screen pixels); used by the Arrange screen. */
    class Placed(val clock: RectF?, val current: RectF?, val forecast: RectF?)

    fun draw(canvas: Canvas, frame: Frame?, w: Int, h: Int, o: Overlay): Placed {
        val full = Rect(0, 0, w, h)
        val bg = frame?.bg
        val style = if (bg == null) o.clockStyle.copy(color = "white") else o.clockStyle
        val colors = frame?.clockColors ?: PhotoColors.DEFAULT
        if (bg == null) canvas.drawColor(0xFF1B1B1F.toInt()) else canvas.drawBitmap(bg, null, full, bitmapPaint)

        val fg = frame?.fg?.takeIf { style.depth }

        // Clock: at its set height, or automatically just behind the main person's head.
        val clockLayout = if (style.enabled) {
            val measured = o.clock.layout(w, h, style, 0f)
            val headTop = if (style.auto) fg?.personTop() else null
            val top = if (headTop != null) {
                // The head covers roughly the lower third of the clock, like on iPhones.
                (headTop * h - measured.rect.height() * 0.7f).coerceIn(h * 0.05f, h * 0.55f)
            } else {
                h * style.position
            }
            o.clock.layout(w, h, style, top)
        } else null

        // Weather: under the clock, beside it, or wherever it was dragged.
        val ws = o.weatherStyle
        val data = o.weather?.takeIf { ws.enabled }
        var current: WeatherPainter.Part? = null
        var forecast: WeatherPainter.Part? = null
        if (data != null) {
            val p = o.weatherPainter
            val clockRect = clockLayout?.rect
            val placement = if (clockRect == null && ws.placement != "custom") "custom" else ws.placement
            when (placement) {
                "under" -> {
                    val r = clockRect!!
                    current = p.measureCurrent(w, ws, style, data, vertical = false).below(r.centerX(), r.bottom + w * 0.04f)
                    forecast = p.measureForecast(w, ws, data, vertical = false)?.below(r.centerX(), current.rect.bottom + w * 0.02f)
                }
                "sides" -> {
                    val r = clockRect!!
                    current = p.measureCurrent(w, ws, style, data, vertical = true).at(r.left / 2f, r.centerY())
                    forecast = p.measureForecast(w, ws, data, vertical = true)?.at((r.right + w) / 2f, r.centerY())
                }
                else -> {
                    current = p.measureCurrent(w, ws, style, data, vertical = false).at(ws.x * w, ws.y * h)
                    forecast = p.measureForecast(w, ws, data, ws.forecastVertical)?.at(ws.forecastX * w, ws.forecastY * h)
                }
            }
            current = current.keptInside(w, h)
            forecast = forecast?.keptInside(w, h)
        }

        // People go in front of each element, unless they'd hide too much of it.
        fun behind(r: RectF?, limit: Float) = r != null && fg != null && fg.coverage(r, w, h) < limit
        val clockBehind = behind(clockLayout?.rect, 0.45f)
        val currentBehind = behind(current?.rect, 0.25f)
        val forecastBehind = behind(forecast?.rect, 0.25f)

        fun drawClock() = clockLayout?.let { o.clock.draw(canvas, it, style, colors) }
        fun drawCurrent() = current?.let { o.weatherPainter.drawCurrent(canvas, it, data!!, style, colors) }
        fun drawForecast() = forecast?.let { o.weatherPainter.drawForecast(canvas, it, style, colors) }

        if (clockBehind) drawClock()
        if (currentBehind) drawCurrent()
        if (forecastBehind) drawForecast()
        if (fg != null && (clockBehind || currentBehind || forecastBehind)) canvas.drawBitmap(fg.bitmap, null, full, bitmapPaint)
        if (!clockBehind) drawClock()
        if (!currentBehind) drawCurrent()
        if (!forecastBehind) drawForecast()

        return Placed(clockLayout?.rect, current?.rect, forecast?.rect)
    }
}
