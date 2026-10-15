package nl.jdatje.framily.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.tasks.await
import java.io.Closeable
import kotlin.math.roundToInt

/** The people cut out of a photo, to draw on top of the clock. */
class Foreground(
    val bitmap: Bitmap,
    private val mask: FloatArray,
    private val maskW: Int,
    private val maskH: Int,
) {
    /**
     * Top of the main person's head, as a fraction of the height (null when nobody is found).
     * Looks in the middle half of the image, where the main subject usually is.
     */
    fun personTop(): Float? = headTop

    private val headTop: Float? by lazy {
        val l = (maskW * 0.25f).roundToInt()
        val r = (maskW * 0.75f).roundToInt()
        for (y in 0 until (maskH * 0.9f).roundToInt()) {
            var hit = 0
            for (x in l until r) if (mask[y * maskW + x] > 0.5f) hit++
            if (hit > (r - l) * 0.12f) return@lazy y.toFloat() / maskH
        }
        null
    }

    /** Which part (0..1) of [rect] (screen pixels) is covered by people. */
    fun coverage(rect: RectF, screenW: Int, screenH: Int): Float {
        val l = (rect.left / screenW * maskW).roundToInt().coerceIn(0, maskW - 1)
        val r = (rect.right / screenW * maskW).roundToInt().coerceIn(0, maskW - 1)
        val t = (rect.top / screenH * maskH).roundToInt().coerceIn(0, maskH - 1)
        val b = (rect.bottom / screenH * maskH).roundToInt().coerceIn(0, maskH - 1)
        var hit = 0
        var all = 0
        for (y in t..b step 2) for (x in l..r step 2) {
            all++
            if (mask[y * maskW + x] > 0.5f) hit++
        }
        return if (all == 0) 0f else hit.toFloat() / all
    }
}

/** Uses ML Kit's on-device person segmentation. */
class DepthSegmenter : Closeable {
    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
    )

    suspend fun foreground(photo: Bitmap): Foreground? {
        val smallW = 512
        val smallH = (photo.height * smallW.toFloat() / photo.width).roundToInt()
        val small = Bitmap.createScaledBitmap(photo, smallW, smallH, true)
        val result = segmenter.process(InputImage.fromBitmap(small, 0)).await()
        if (small !== photo) small.recycle()

        val mw = result.width
        val mh = result.height
        val buffer = result.buffer
        buffer.rewind()
        val conf = FloatArray(mw * mh) { buffer.float }

        // Soft edges: fade between 35% and 65% confidence.
        val alpha = IntArray(mw * mh) { i ->
            val t = ((conf[i] - 0.35f) / 0.3f).coerceIn(0f, 1f)
            val s = t * t * (3 - 2 * t)
            (s * 255).roundToInt() shl 24
        }
        val maskBmp = Bitmap.createBitmap(alpha, mw, mh, Bitmap.Config.ARGB_8888)

        val fg = photo.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        Canvas(fg).drawBitmap(maskBmp, null, Rect(0, 0, fg.width, fg.height), paint)
        maskBmp.recycle()
        return Foreground(fg, conf, mw, mh)
    }

    override fun close() {
        segmenter.close()
    }
}
