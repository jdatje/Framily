package nl.jdatje.framily.family

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.jdatje.framily.data.PhotoDb
import nl.jdatje.framily.data.Prefs
import nl.jdatje.framily.scan.Scorer
import kotlin.math.sqrt

/**
 * Who is who.
 *
 * 1. **Groups:** faces that look alike are grouped automatically (each group is usually one person).
 * 2. **People:** you name a group once ("Anna", family) or mark it "Not family".
 * 3. **Matching:** every face is compared with the faces you named. A photo counts as a family
 *    photo when a family member's face is in it, big enough.
 *
 * "Not family" answers are kept as counter-examples, so similar-looking strangers get rejected too.
 */
object FamilyIndex {

    /** Faces smaller than this (fraction of photo height) are too small to group reliably. */
    private const val GROUP_MIN_RATIO = 0.06f
    private const val GROUP_SIMILARITY = 0.55f
    private const val MAX_GROUPED_FACES = 15_000
    private const val REFS_PER_PERSON = 80
    private const val NOT_FAMILY_NAME = "Not family"

    private val lock = Mutex()

    class FaceRef(val faceId: Long, val photoId: Long, val box: RectF) {
        val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)
    }

    class Person(
        val id: Long,
        val name: String,
        val family: Boolean,
        val faces: Int,
        val photos: Int,
        val sample: FaceRef?,
        /** False: photos with only this family member are skipped (they need company). */
        val alone: Boolean = true,
    )

    class Group(val cluster: Long, val size: Int, val samples: List<FaceRef>, val suggestion: String?)

    private fun db(context: Context): SQLiteDatabase = PhotoDb.get(context).writableDatabase

    // ---------------------------------------------------------------- people

    fun people(context: Context): List<Person> {
        val db = db(context)
        val list = db.rawQuery(
            """
            SELECT p.id, p.name, p.family, p.alone,
                   (SELECT COUNT(*) FROM faces f WHERE f.match_id = p.id) AS nf,
                   (SELECT COUNT(DISTINCT f.photo_id) FROM faces f WHERE f.match_id = p.id) AS np
            FROM people p WHERE p.name != ? ORDER BY p.family DESC, p.name COLLATE NOCASE
            """.trimIndent(),
            arrayOf(NOT_FAMILY_NAME),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Person(
                            id = c.getLong(0),
                            name = c.getString(1),
                            family = c.getInt(2) == 1,
                            alone = c.getInt(3) == 1,
                            faces = c.getInt(4),
                            photos = c.getInt(5),
                            sample = null,
                        )
                    )
                }
            }
        }
        return list.map { p ->
            Person(p.id, p.name, p.family, p.faces, p.photos, samples(db, "person_id=?", p.id, 1).firstOrNull(), p.alone)
        }
    }

    fun familyCount(context: Context): Int =
        db(context).rawQuery("SELECT COUNT(*) FROM people WHERE family=1", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun notFamilyFaces(context: Context): Int =
        db(context).rawQuery(
            "SELECT COUNT(*) FROM faces WHERE person_id = (SELECT id FROM people WHERE name=?)", arrayOf(NOT_FAMILY_NAME)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun addPerson(context: Context, name: String, family: Boolean = true): Long =
        db(context).insert("people", null, ContentValues().apply { put("name", name.trim()); put("family", if (family) 1 else 0) })

    private fun notFamilyId(context: Context): Long =
        db(context).rawQuery("SELECT id FROM people WHERE name=?", arrayOf(NOT_FAMILY_NAME)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        } ?: addPerson(context, NOT_FAMILY_NAME, family = false)

    fun renamePerson(context: Context, id: Long, name: String) {
        db(context).update("people", ContentValues().apply { put("name", name.trim()) }, "id=?", arrayOf(id.toString()))
    }

    fun setAlone(context: Context, id: Long, alone: Boolean) {
        db(context).update("people", ContentValues().apply { put("alone", if (alone) 1 else 0) }, "id=?", arrayOf(id.toString()))
    }

    fun setFamily(context: Context, id: Long, family: Boolean) {
        db(context).update("people", ContentValues().apply { put("family", if (family) 1 else 0) }, "id=?", arrayOf(id.toString()))
    }

    /** Forget a person: their faces become unknown again. */
    fun deletePerson(context: Context, id: Long) {
        val db = db(context)
        db.execSQL("UPDATE faces SET person_id=NULL WHERE person_id=?", arrayOf(id))
        db.delete("people", "id=?", arrayOf(id.toString()))
    }

    /** Clear all "Not family" answers. */
    fun resetNotFamily(context: Context) {
        db(context).execSQL("UPDATE faces SET person_id=NULL WHERE person_id = (SELECT id FROM people WHERE name=?)", arrayOf(NOT_FAMILY_NAME))
    }

    /** Name a whole group at once. */
    fun assignGroup(context: Context, cluster: Long, personId: Long) {
        db(context).execSQL("UPDATE faces SET person_id=? WHERE cluster=? AND person_id IS NULL", arrayOf(personId, cluster))
    }

    fun assignGroupNotFamily(context: Context, cluster: Long) = assignGroup(context, cluster, notFamilyId(context))

    /** Take one face away from a person ("that's not them"). */
    fun removeFaceFromPerson(context: Context, faceId: Long) {
        db(context).execSQL("UPDATE faces SET person_id=? WHERE id=?", arrayOf(notFamilyId(context), faceId))
    }

    /** "Not family" on a wallpaper: the faces that were taken for family become counter-examples. */
    fun markPhotoNotFamily(context: Context, photoId: Long) {
        val db = db(context)
        db.execSQL(
            """
            UPDATE faces SET person_id=? WHERE photo_id=? AND person_id IS NULL
              AND match_id IN (SELECT id FROM people WHERE family=1)
            """.trimIndent(),
            arrayOf(notFamilyId(context), photoId),
        )
        db.execSQL("UPDATE photos SET family_ratio=0 WHERE id=?", arrayOf(photoId))
    }

    // ---------------------------------------------------------------- groups for the screen

    /** Groups that aren't named yet, biggest first. */
    fun openGroups(context: Context, limit: Int): List<Group> {
        val db = db(context)
        val names = db.rawQuery("SELECT id, name FROM people", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1)) }
        }
        val clusters = db.rawQuery(
            """
            SELECT cluster, COUNT(*) AS n, SUM(person_id IS NOT NULL) AS named
            FROM faces WHERE cluster IS NOT NULL
            GROUP BY cluster HAVING n >= 2 AND named * 2 < n
            ORDER BY n DESC LIMIT $limit
            """.trimIndent(), null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getInt(1)) } }
        return clusters.map { (cluster, n) ->
            val suggestion = db.rawQuery(
                "SELECT match_id, COUNT(*) AS k FROM faces WHERE cluster=? AND match_id IS NOT NULL GROUP BY match_id ORDER BY k DESC LIMIT 1",
                arrayOf(cluster.toString()),
            ).use { c -> if (c.moveToFirst()) names[c.getLong(0)] else null }
            Group(cluster, n, samples(db, "cluster=? AND person_id IS NULL", cluster, 8), suggestion)
        }
    }

    fun personSamples(context: Context, personId: Long, limit: Int): List<FaceRef> =
        samples(db(context), "match_id=?", personId, limit)

    private fun samples(db: SQLiteDatabase, where: String, arg: Long, limit: Int): List<FaceRef> =
        db.rawQuery(
            "SELECT id, photo_id, l, t, r, b FROM faces WHERE $where ORDER BY ratio DESC LIMIT $limit",
            arrayOf(arg.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(FaceRef(c.getLong(0), c.getLong(1), RectF(c.getFloat(2), c.getFloat(3), c.getFloat(4), c.getFloat(5))))
            }
        }

    // ---------------------------------------------------------------- computing

    private class F(val id: Long, val photoId: Long, val ratio: Float, val emb: FloatArray, val person: Long?)

    private fun loadFaces(db: SQLiteDatabase, where: String, limit: Int? = null): List<F> =
        db.rawQuery(
            "SELECT id, photo_id, ratio, emb, person_id FROM faces WHERE emb IS NOT NULL $where ORDER BY ratio DESC" +
                (limit?.let { " LIMIT $it" } ?: ""),
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(F(c.getLong(0), c.getLong(1), c.getFloat(2), FaceEmbedder.fromBytes(c.getBlob(3)), if (c.isNull(4)) null else c.getLong(4)))
                }
            }
        }

    /** Re-group all faces, then re-match. Takes a few seconds to a minute on a big library. */
    suspend fun rebuild(context: Context) = lock.withLock {
        withContext(Dispatchers.Default) {
            group(context)
            match(context)
        }
    }

    /** Re-match only (after you named someone or changed the strictness). Takes a few seconds. */
    suspend fun rematch(context: Context) = lock.withLock {
        withContext(Dispatchers.Default) { match(context) }
    }

    private fun group(context: Context) {
        val db = db(context)
        val faces = loadFaces(db, "AND ratio >= $GROUP_MIN_RATIO", MAX_GROUPED_FACES)
        val sums = ArrayList<FloatArray>()
        val centers = ArrayList<FloatArray>()
        val assignment = LongArray(faces.size)
        // Biggest (clearest) faces first, so they become the "center" of their group.
        faces.forEachIndexed { i, f ->
            var best = -1
            var bestSim = GROUP_SIMILARITY
            for (k in centers.indices) {
                val s = FaceEmbedder.similarity(f.emb, centers[k])
                if (s >= bestSim) {
                    bestSim = s
                    best = k
                }
            }
            if (best < 0) {
                sums += f.emb.copyOf()
                centers += f.emb.copyOf()
                best = centers.size - 1
            } else {
                val sum = sums[best]
                for (d in sum.indices) sum[d] += f.emb[d]
                centers[best] = normalized(sum)
            }
            assignment[i] = best.toLong()
        }
        db.beginTransaction()
        try {
            db.execSQL("UPDATE faces SET cluster=NULL")
            val stmt = db.compileStatement("UPDATE faces SET cluster=? WHERE id=?")
            faces.forEachIndexed { i, f ->
                stmt.bindLong(1, assignment[i])
                stmt.bindLong(2, f.id)
                stmt.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun match(context: Context) {
        val db = db(context)
        val threshold = Prefs.get(context).familyThreshold
        val family = HashMap<Long, Boolean>()
        val mayBeAlone = HashMap<Long, Boolean>()
        db.rawQuery("SELECT id, family, alone FROM people", null).use { c ->
            while (c.moveToNext()) {
                family[c.getLong(0)] = c.getInt(1) == 1
                mayBeAlone[c.getLong(0)] = c.getInt(2) == 1
            }
        }

        // Reference faces per person: the clearest ones they were named on.
        val refs = HashMap<Long, List<FloatArray>>()
        for (personId in family.keys) {
            refs[personId] = db.rawQuery(
                "SELECT emb FROM faces WHERE person_id=? AND emb IS NOT NULL ORDER BY ratio DESC LIMIT $REFS_PER_PERSON",
                arrayOf(personId.toString()),
            ).use { c -> buildList { while (c.moveToNext()) add(FaceEmbedder.fromBytes(c.getBlob(0))) } }
        }

        val faces = loadFaces(db, "")
        val matchOf = HashMap<Long, Long?>(faces.size)
        val familyRatio = HashMap<Long, Float>()
        val familyIn = HashMap<Long, MutableSet<Long>>() // photo -> family members in it
        for (f in faces) {
            val match: Long? = if (f.person != null) f.person else {
                var bestPerson: Long? = null
                var bestSim = -1f
                var bestOther = -1f // best similarity to someone who is NOT family
                for ((personId, list) in refs) {
                    var s = -1f
                    for (r in list) s = maxOf(s, FaceEmbedder.similarity(f.emb, r))
                    if (family[personId] == true) {
                        if (s > bestSim) { bestSim = s; bestPerson = personId }
                    } else {
                        bestOther = maxOf(bestOther, s)
                    }
                }
                // Family only when clearly closer to a family member than to any "not family" face.
                if (bestPerson != null && bestSim >= threshold && bestSim >= bestOther + 0.05f) bestPerson else null
            }
            matchOf[f.id] = match
            if (match != null && family[match] == true) {
                familyRatio[f.photoId] = maxOf(familyRatio[f.photoId] ?: 0f, f.ratio)
                familyIn.getOrPut(f.photoId) { HashSet() } += match
            }
        }
        // Photos with only one family member who shouldn't appear on their own don't count as family photos.
        for ((photoId, members) in familyIn) {
            if (members.size == 1 && mayBeAlone[members.first()] == false) familyRatio.remove(photoId)
        }

        db.beginTransaction()
        try {
            db.execSQL("UPDATE faces SET match_id=NULL")
            val m = db.compileStatement("UPDATE faces SET match_id=? WHERE id=?")
            for ((id, person) in matchOf) {
                if (person == null) continue
                m.bindLong(1, person); m.bindLong(2, id); m.executeUpdateDelete()
            }
            db.execSQL("UPDATE photos SET family_ratio=0")
            val p = db.compileStatement("UPDATE photos SET family_ratio=? WHERE id=?")
            for ((photoId, ratio) in familyRatio) {
                p.bindDouble(1, ratio.toDouble()); p.bindLong(2, photoId); p.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // Family members count in the score, so scores change with every match.
        Scorer.recomputeAll(context)
    }

    private fun normalized(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        n = sqrt(n)
        return FloatArray(v.size) { if (n == 0f) 0f else v[it] / n }
    }
}
