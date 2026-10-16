package nl.jdatje.framily.scan

import android.content.ContentValues
import android.content.Context
import android.graphics.RectF
import nl.jdatje.framily.data.PhotoDb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The photo score (0–100), version 2. Stricter than version 1 (where good photos all ended up at
 * 98–99), and tuned for this wallpaper:
 *
 *   face size 15 · sharpness 15 · eyes open 15 · smiling 15 · room above the head (for the clock) 15
 *   no cut-off faces 10 · centered 5 · family members in the photo 10
 *
 * It only uses values the scan already stored, so changing it never needs a rescan.
 */
object Scorer {
    const val VERSION = 2

    class Parts(
        val size: Float,
        val sharp: Float,
        val eyes: Float,
        val smile: Float,
        val headroom: Float,
        val uncut: Float,
        val centered: Float,
        val family: Float,
    ) {
        val total: Float
            get() = 100f * (
                0.15f * size + 0.15f * sharp + 0.15f * eyes + 0.15f * smile +
                    0.15f * headroom + 0.10f * uncut + 0.05f * centered + 0.10f * family
                )
    }

    // Plain math instead of RectF.height()/centerX(), so this also runs in unit tests.
    private fun RectF.h() = bottom - top
    private fun RectF.cx() = (left + right) / 2f

    private fun smooth(x: Float, from: Float, to: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /**
     * @param faces face boxes as fractions of the photo
     * @param ratio height of the largest face / photo height
     * @param familyFaces how many recognized family members are in the photo
     * @param hasFamily whether any family members were named at all (otherwise that part is neutral)
     */
    fun parts(
        faces: List<RectF>,
        ratio: Float,
        smile: Float,
        eyes: Float,
        sharp: Float,
        familyFaces: Int,
        hasFamily: Boolean,
    ): Parts {
        // Size: best between ~25% and 50% of the height; very close-up selfies a bit less.
        val size = smooth(ratio, 0.06f, 0.28f) * (1f - 0.25f * smooth(ratio, 0.5f, 0.75f))

        // Main faces: at least half as tall as the biggest one.
        val main = faces.filter { it.h() >= faces.maxOf { f -> f.h() } * 0.5f }.ifEmpty { faces }

        // Headroom: the highest face should start a fair bit below the top, so the clock fits behind
        // the head. Too low in the frame isn't great either.
        val top = main.minOfOrNull { it.top } ?: 0.4f
        val headroom = smooth(top, 0.08f, 0.24f) * (1f - 0.6f * smooth(top, 0.55f, 0.8f))

        // Faces touching the edge of the photo are cut off; bigger faces count more.
        val biggest = max(0.001f, faces.maxOfOrNull { it.h() } ?: 0.001f)
        var cut = 0f
        for (f in faces) {
            val touching = f.left < 0.01f || f.right > 0.99f || f.top < 0.01f || f.bottom > 0.99f
            if (touching) cut = max(cut, f.h() / biggest)
        }
        val uncut = 1f - cut

        val cx = if (main.isEmpty()) 0.5f else main.map { it.cx() }.average().toFloat()
        val centered = 1f - min(1f, abs(cx - 0.5f) * 2.5f)

        val family = if (!hasFamily) 0.5f else when (familyFaces) {
            0 -> 0f
            1 -> 0.6f
            2 -> 0.85f
            else -> 1f
        }

        return Parts(
            size = size,
            sharp = sharp.coerceIn(0f, 1f).pow(1.5f),
            eyes = eyes.coerceIn(0f, 1f).pow(3f),       // half-closed eyes cost a lot
            smile = smile.coerceIn(0f, 1f).pow(2f),
            headroom = headroom,
            uncut = uncut,
            centered = centered,
            family = family,
        )
    }

    /** Recompute every score from the stored parts (fast: no photos are opened). */
    fun recomputeAll(context: Context) {
        val helper = PhotoDb.get(context)
        val db = helper.writableDatabase
        val hasFamily = db.rawQuery("SELECT COUNT(*) FROM people WHERE family=1", null)
            .use { c -> c.moveToFirst() && c.getInt(0) > 0 }
        val familyCounts = HashMap<Long, Int>()
        db.rawQuery(
            "SELECT f.photo_id, COUNT(*) FROM faces f JOIN people p ON f.match_id = p.id WHERE p.family = 1 GROUP BY f.photo_id",
            null,
        ).use { c -> while (c.moveToNext()) familyCounts[c.getLong(0)] = c.getInt(1) }

        val rows = ArrayList<Pair<Long, Float>>()
        db.rawQuery(
            "SELECT id, faces, face_ratio, smile, eyes, sharp FROM photos WHERE portrait=1 AND face_count>0",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val p = parts(
                    faces = PhotoDb.decodeFaces(c.getString(1)),
                    ratio = c.getFloat(2),
                    smile = c.getFloat(3),
                    eyes = c.getFloat(4),
                    sharp = c.getFloat(5),
                    familyFaces = familyCounts[id] ?: 0,
                    hasFamily = hasFamily,
                )
                rows += id to p.total
            }
        }
        db.beginTransaction()
        try {
            val cv = ContentValues()
            for ((id, score) in rows) {
                cv.put("score", score)
                db.update("photos", cv, "id=?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
