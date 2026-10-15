package nl.jdatje.framily.wallpaper

import android.app.WallpaperColors
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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

    fun draw(canvas: Canvas, frame: Frame?, w: Int, h: Int, o: Overlay) {
        val full = Rect(0, 0, w, h)
        val bg = frame?.bg
        val style = if (bg == null) o.clockStyle.copy(color = "white") else o.clockStyle
        val colors = frame?.clockColors ?: PhotoColors.DEFAULT
        if (bg == null) canvas.drawColor(0xFF1B1B1F.toInt()) else canvas.drawBitmap(bg, null, full, bitmapPaint)

        val clockLayout = if (style.enabled) o.clock.layout(w, h, style) else null
        val weatherData = o.weather?.takeIf { o.weatherStyle.enabled }
        val weatherLayout = weatherData?.let {
            val top = if (o.weatherStyle.placement == "clock" && clockLayout != null) {
                clockLayout.rect.bottom + w * 0.05f
            } else {
                h * o.weatherStyle.position
            }
            o.weatherPainter.layout(w, top, o.weatherStyle, it)
        }

        // People go in front of the clock and weather, unless they'd hide too much of them.
        val fg = frame?.fg?.takeIf { style.depth }
        val clockBehind = clockLayout != null && fg != null && fg.coverage(clockLayout.rect, w, h) < 0.45f
        val weatherBehind = weatherLayout != null && fg != null && fg.coverage(weatherLayout.rect, w, h) < 0.25f

        if (clockLayout != null && clockBehind) o.clock.draw(canvas, clockLayout, style, colors)
        if (weatherLayout != null && weatherBehind) o.weatherPainter.draw(canvas, weatherLayout, weatherData, style, colors)
        if (fg != null && (clockBehind || weatherBehind)) canvas.drawBitmap(fg.bitmap, null, full, bitmapPaint)
        if (clockLayout != null && !clockBehind) o.clock.draw(canvas, clockLayout, style, colors)
        if (weatherLayout != null && !weatherBehind) o.weatherPainter.draw(canvas, weatherLayout, weatherData, style, colors)
    }
}
