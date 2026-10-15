package nl.jdatje.framily.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.net.Uri
import android.view.Display
import nl.jdatje.framily.data.Photo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageUtils {

    /** Opens a photo (correctly rotated) with its longest side at most [maxEdge] pixels. */
    fun decodeScaled(context: Context, uri: Uri, maxEdge: Int): Bitmap? = decode(context, uri) { w, h ->
        min(1f, maxEdge.toFloat() / max(w, h))
    }

    /** Opens a photo just big enough to fill a [dstW] x [dstH] area. */
    fun decodeCover(context: Context, uri: Uri, dstW: Int, dstH: Int): Bitmap? = decode(context, uri) { w, h ->
        min(1f, max(dstW.toFloat() / w, dstH.toFloat() / h))
    }

    private fun decode(context: Context, uri: Uri, scaleFor: (Int, Int) -> Float): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = scaleFor(w, h)
            if (scale < 1f) {
                decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /** The point the crop should center on: the middle of all faces. */
    fun focusPoint(faces: List<RectF>): PointF {
        if (faces.isEmpty()) return PointF(0.5f, 0.4f)
        val u = RectF(faces[0])
        faces.drop(1).forEach { u.union(it) }
        return PointF(u.centerX(), u.centerY())
    }

    /** Crops [src] to fill [dstW] x [dstH], keeping the faces in view. */
    fun renderCover(src: Bitmap, dstW: Int, dstH: Int, faces: List<RectF>): Bitmap {
        val scale = max(dstW.toFloat() / src.width, dstH.toFloat() / src.height)
        val visW = dstW / scale
        val visH = dstH / scale
        val f = focusPoint(faces)
        val left = (f.x * src.width - visW / 2f).coerceIn(0f, max(0f, src.width - visW))
        val top = (f.y * src.height - visH * 0.45f).coerceIn(0f, max(0f, src.height - visH))
        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val srcRect = Rect(
            left.roundToInt(), top.roundToInt(),
            min(src.width, (left + visW).roundToInt()), min(src.height, (top + visH).roundToInt())
        )
        Canvas(out).drawBitmap(src, srcRect, Rect(0, 0, dstW, dstH), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** A screen-sized, face-centered version of a photo. */
    fun renderPhoto(context: Context, photo: Photo, w: Int, h: Int): Bitmap? {
        val src = decodeCover(context, photo.uri, w, h) ?: return null
        return try {
            renderCover(src, w, h, photo.faces)
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            src.recycle()
        }
    }

    /** Full screen size in portrait orientation. */
    fun screenSize(context: Context): Pair<Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display.mode
        val a = mode.physicalWidth
        val b = mode.physicalHeight
        return min(a, b) to max(a, b)
    }
}
