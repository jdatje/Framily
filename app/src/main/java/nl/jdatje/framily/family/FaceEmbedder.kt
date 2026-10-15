package nl.jdatje.framily.family

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tflite.java.TfLite
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.tensorflow.lite.InterpreterApi
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Turns a face into 192 numbers (an "embedding") with MobileFaceNet. Photos of the same person give
 * similar numbers (similarity ~0.75–0.98), different people very different ones (below ~0.25).
 * Runs fully on the phone; the runtime comes from Google Play services.
 */
class FaceEmbedder(private val context: Context) : Closeable {

    companion object {
        const val SIZE = 112
        const val DIM = 192
        private const val TAG = "FaceEmbedder"
        private val initLock = Mutex()
        @Volatile private var runtimeReady: Boolean? = null

        /** Cosine similarity of two embeddings that are already normalized. */
        fun similarity(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        fun toBytes(e: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(e.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            e.forEach { buf.putFloat(it) }
            return buf.array()
        }

        fun fromBytes(b: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { buf.float }
        }
    }

    private var interpreter: InterpreterApi? = null
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(DIM) }
    private val pixels = IntArray(SIZE * SIZE)
    private val crop = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** False when Play services can't provide the runtime; the scan then retries later. */
    suspend fun ready(): Boolean {
        if (interpreter != null) return true
        val runtimeOk = initLock.withLock {
            runtimeReady ?: try {
                TfLite.initialize(context).await()
                true
            } catch (e: Exception) {
                Log.w(TAG, "TFLite runtime not available: ${e.message}")
                false
            }.also { runtimeReady = it.takeIf { ok -> ok } }
        }
        if (!runtimeOk) return false
        return try {
            val fd = context.assets.openFd("mobilefacenet.tflite")
            val model = FileInputStream(fd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            interpreter = InterpreterApi.create(
                model,
                InterpreterApi.Options().setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY),
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Model failed to load: ${e.message}")
            false
        }
    }

    /**
     * Embedding of the face in [box] (pixels of [bmp]). When both eyes are known the face is
     * rotated upright first, which makes matching more reliable.
     */
    fun embed(bmp: Bitmap, box: Rect, eyeA: PointF?, eyeB: PointF?): FloatArray? {
        val interp = interpreter ?: return null
        val side = max(box.width(), box.height()) * 1.2f
        val angle = if (eyeA != null && eyeB != null) {
            val (l, r) = if (eyeA.x <= eyeB.x) eyeA to eyeB else eyeB to eyeA
            Math.toDegrees(atan2((r.y - l.y).toDouble(), (r.x - l.x).toDouble())).toFloat()
        } else 0f
        val m = Matrix().apply {
            postTranslate(-box.exactCenterX(), -box.exactCenterY())
            postRotate(-angle)
            postScale(SIZE / side, SIZE / side)
            postTranslate(SIZE / 2f, SIZE / 2f)
        }
        crop.eraseColor(0)
        Canvas(crop).drawBitmap(bmp, m, paint)
        crop.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)

        input.rewind()
        for (c in pixels) {
            input.putFloat((((c shr 16) and 0xFF) - 128f) / 128f)
            input.putFloat((((c shr 8) and 0xFF) - 128f) / 128f)
            input.putFloat(((c and 0xFF) - 128f) / 128f)
        }
        input.rewind()
        interp.run(input, output)
        val e = output[0].copyOf()
        var norm = 0f
        for (v in e) norm += v * v
        norm = sqrt(norm)
        if (norm == 0f) return null
        for (i in e.indices) e[i] /= norm
        return e
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }
}
