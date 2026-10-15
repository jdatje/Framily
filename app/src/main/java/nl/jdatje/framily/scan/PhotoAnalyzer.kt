package nl.jdatje.framily.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import nl.jdatje.framily.util.Fingerprint
import nl.jdatje.framily.util.ImageUtils
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** What the scan found in one photo. */
data class Analysis(
    val portrait: Boolean,
    val width: Int,
    val height: Int,
    val faceCount: Int,
    val faceRatio: Float,
    val faces: List<RectF>,
    val smile: Float,
    val eyes: Float,
    val sharp: Float,
    val score: Float,
    val phash: Long? = null,
) {
    companion object {
        fun empty(portrait: Boolean, width: Int, height: Int) =
            Analysis(portrait, width, height, 0, 0f, emptyList(), 0f, 0f, 0f, 0f)
    }
}

/**
 * Finds faces in a photo and gives it a 0–100 score.
 *
 * Score parts: face size 25, sharpness 25, eyes open 15, smiling 15,
 * looking at the camera 10, faces well placed 10.
 */
class PhotoAnalyzer(private val context: Context) : Closeable {

    companion object {
        /** Raise this when the scoring changes; the app then rescans everything. */
        const val VERSION = 1

        /** Faces smaller than this (fraction of photo height) are ignored completely. */
        private const val MIN_FACE = 0.04f
    }

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.05f)
            .build()
    )

    suspend fun analyze(uri: Uri): Analysis? {
        val bmp = ImageUtils.decodeScaled(context, uri, 960) ?: return null
        try {
            val w = bmp.width
            val h = bmp.height
            if (h <= w * 1.05f) return Analysis.empty(false, w, h)

            val faces = detector.process(InputImage.fromBitmap(bmp, 0)).await()
            val significant = faces.filter { it.boundingBox.height().toFloat() / h >= MIN_FACE }
            if (significant.isEmpty()) return Analysis.empty(true, w, h)

            val maxRatio = significant.maxOf { it.boundingBox.height().toFloat() / h }
            // The "main" faces: at least half as big as the biggest one.
            val main = significant.filter { it.boundingBox.height().toFloat() / h >= maxRatio * 0.5f }

            val smile = main.map { it.smilingProbability ?: 0.5f }.average().toFloat()
            val eyes = main.map {
                min(it.leftEyeOpenProbability ?: 0.5f, it.rightEyeOpenProbability ?: 0.5f)
            }.average().toFloat()
            val frontal = main.map { 1f - min(abs(it.headEulerAngleY), 45f) / 45f }.average().toFloat()
            val sharp = main.map { sharpness(bmp, it.boundingBox) }.average().toFloat()

            val rects = significant.map { f ->
                val b = f.boundingBox
                RectF(
                    (b.left.toFloat() / w).coerceIn(0f, 1f),
                    (b.top.toFloat() / h).coerceIn(0f, 1f),
                    (b.right.toFloat() / w).coerceIn(0f, 1f),
                    (b.bottom.toFloat() / h).coerceIn(0f, 1f),
                )
            }
            val focus = ImageUtils.focusPoint(rects)

            val size = ((maxRatio - 0.05f) / 0.25f).coerceIn(0f, 1f)
            val horizontal = 1f - min(1f, abs(focus.x - 0.5f) * 2f)
            val vertical = 1f - (max(0f, abs(focus.y - 0.4f) - 0.15f) / 0.45f).coerceIn(0f, 1f)
            val position = 0.6f * horizontal + 0.4f * vertical

            val score = 100f * (
                0.25f * size + 0.25f * sharp + 0.15f * eyes +
                    0.15f * smile + 0.10f * frontal + 0.10f * position
                )

            return Analysis(
                true, w, h, significant.size, maxRatio, rects, smile, eyes, sharp, score,
                phash = Fingerprint.dHash(bmp),
            )
        } finally {
            bmp.recycle()
        }
    }

    /** 0 = blurry, 1 = sharp. Measures edge strength inside the face. */
    private fun sharpness(bmp: Bitmap, box: Rect): Float {
        val r = Rect(box)
        if (!r.intersect(0, 0, bmp.width, bmp.height) || r.width() < 8 || r.height() < 8) return 0.5f
        val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
        val small = Bitmap.createScaledBitmap(crop, 128, 128, true)
        val px = IntArray(128 * 128)
        small.getPixels(px, 0, 128, 0, 0, 128, 128)
        if (small !== crop && small !== bmp) small.recycle()
        if (crop !== bmp) crop.recycle()

        val gray = FloatArray(px.size) { i ->
            val c = px[i]
            0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until 127) for (x in 1 until 127) {
            val i = y * 128 + x
            val lap = (gray[i - 1] + gray[i + 1] + gray[i - 128] + gray[i + 128] - 4 * gray[i]).toDouble()
            sum += lap
            sumSq += lap * lap
            n++
        }
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        return ((ln(variance + 1) - ln(15.0)) / (ln(400.0) - ln(15.0))).toFloat().coerceIn(0f, 1f)
    }

    override fun close() {
        detector.close()
    }
}
