package nl.jdatje.framily.util

import android.graphics.Bitmap

/**
 * A 64-bit "difference hash": the photo shrunk to 9x8 brightness blocks, recording for each block
 * whether it is brighter than its right neighbour. Burst shots of the same moment give almost the
 * same 64 bits; different scenes differ in about half of them.
 */
object Fingerprint {

    fun dHash(bmp: Bitmap): Long {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // Average brightness per block (true averaging, so tiny details don't flip bits).
        val sums = DoubleArray(9 * 8)
        val counts = IntArray(9 * 8)
        val step = if (w * h > 400_000) 2 else 1
        for (y in 0 until h step step) {
            val by = (y * 8 / h).coerceAtMost(7)
            for (x in 0 until w step step) {
                val bx = (x * 9 / w).coerceAtMost(8)
                val c = px[y * w + x]
                sums[by * 9 + bx] += 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
                counts[by * 9 + bx]++
            }
        }
        val lum = DoubleArray(sums.size) { if (counts[it] == 0) 0.0 else sums[it] / counts[it] }

        var hash = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (lum[y * 9 + x] > lum[y * 9 + x + 1]) hash = hash or (1L shl bit)
            bit++
        }
        return hash
    }

    /** Number of differing bits (0 = identical, ~32 = unrelated). */
    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
