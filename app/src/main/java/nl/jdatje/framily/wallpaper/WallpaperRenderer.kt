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

    fun draw(canvas: Canvas, frame: Frame?, w: Int, h: Int, style: ClockStyle, clock: ClockPainter) {
        val full = Rect(0, 0, w, h)
        val bg = frame?.bg
        if (bg == null) {
            canvas.drawColor(0xFF1B1B1F.toInt())
            if (style.enabled) clock.draw(canvas, clock.layout(w, h, style), style.copy(color = "white"), PhotoColors.DEFAULT)
            return
        }
        canvas.drawBitmap(bg, null, full, bitmapPaint)
        if (!style.enabled) return
        val layout = clock.layout(w, h, style)
        clock.draw(canvas, layout, style, frame.clockColors)
        val fg = frame.fg
        // If people would hide most of the time, keep the clock on top for this photo.
        if (fg != null && style.depth && fg.coverage(layout.rect, w, h) < 0.45f) {
            canvas.drawBitmap(fg.bitmap, null, full, bitmapPaint)
        }
    }
}
