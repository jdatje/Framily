package nl.jdatje.framily.rotation

import android.content.Context
import nl.jdatje.framily.data.Photo
import nl.jdatje.framily.data.PhotoDb
import nl.jdatje.framily.data.Prefs
import nl.jdatje.framily.family.FamilyIndex
import nl.jdatje.framily.util.Fingerprint
import kotlin.math.abs

/**
 * Builds the rotation: all qualifying photos, best first, with very similar photos grouped so only
 * the best one of each group is used. Then the top N.
 */
object PoolBuilder {

    /** A photo in the rotation, plus how many similar (lower scoring) photos it stands in for. */
    class Entry(val photo: Photo, var similar: Int = 0)

    class Result(
        val entries: List<Entry>,
        val qualifying: Int,
        val unique: Int,
        /** Every photo that qualifies (similar ones grouped), not only the top N. */
        val allUnique: List<Photo> = emptyList(),
    ) {
        val photos: List<Photo> get() = entries.map { it.photo }
    }

    const val OFF = 0
    const val STRICT = 1
    const val LOOSE = 2

    private class Rule(val sameMomentMinutes: Int, val sameMomentDistance: Int, val copyDistance: Int)

    private fun rule(level: Int): Rule? = when (level) {
        // Burst shots of the same moment, and copies (e.g. the WhatsApp copy of your own photo).
        STRICT -> Rule(sameMomentMinutes = 5, sameMomentDistance = 10, copyDistance = 5)
        // Also photos of the same scene that differ a bit more.
        LOOSE -> Rule(sameMomentMinutes = 15, sameMomentDistance = 16, copyDistance = 8)
        else -> null
    }

    fun build(context: Context): Result {
        val prefs = Prefs.get(context)
        val familyOnly = prefs.familyOnly && FamilyIndex.familyCount(context) > 0
        val all = PhotoDb.get(context).qualifying(prefs.selectedBuckets, prefs.minFacePercent / 100f, familyOnly)
        val rule = rule(prefs.dedupeLevel)
        val kept = ArrayList<Entry>()
        for (photo in all) { // best first, so the first of each group is its best photo
            val match = if (rule == null) null else kept.firstOrNull { similar(it.photo, photo, rule) }
            if (match != null) match.similar++ else kept += Entry(photo)
        }
        return Result(kept.take(prefs.poolSize), qualifying = all.size, unique = kept.size, allUnique = kept.map { it.photo })
    }

    private fun similar(a: Photo, b: Photo, rule: Rule): Boolean {
        val ha = a.phash ?: return false
        val hb = b.phash ?: return false
        val d = Fingerprint.distance(ha, hb)
        if (d <= rule.copyDistance) return true
        if (a.dateTaken <= 0 || b.dateTaken <= 0) return false
        val minutesApart = abs(a.dateTaken - b.dateTaken) / 60_000.0
        return minutesApart <= rule.sameMomentMinutes && d <= rule.sameMomentDistance
    }
}
