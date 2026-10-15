package nl.jdatje.framily.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import nl.jdatje.framily.scan.Analysis
import java.util.Locale

/** A scanned photo that is (or could be) in the rotation. */
data class Photo(
    val id: Long,
    val bucketId: String,
    val bucketName: String,
    val width: Int,
    val height: Int,
    val faceCount: Int,
    /** Height of the largest face divided by the photo height. */
    val faceRatio: Float,
    val score: Float,
    /** Face boxes, as fractions of the photo size. */
    val faces: List<RectF>,
    val smile: Float,
    val eyes: Float,
    val sharp: Float,
    val blocked: Boolean,
    val lastShown: Long,
    val dateTaken: Long,
    /** Visual fingerprint; similar-looking photos have similar values. Null = not computed yet. */
    val phash: Long?,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
}

/** Remembers the scan result of every photo, so each photo is only analysed once. */
class PhotoDb private constructor(context: Context) :
    SQLiteOpenHelper(context, "framily.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE photos(
                id INTEGER PRIMARY KEY,
                bucket_id TEXT NOT NULL,
                bucket_name TEXT NOT NULL,
                date_modified INTEGER NOT NULL,
                date_taken INTEGER NOT NULL DEFAULT 0,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                portrait INTEGER NOT NULL DEFAULT 0,
                face_count INTEGER NOT NULL DEFAULT 0,
                face_ratio REAL NOT NULL DEFAULT 0,
                score REAL NOT NULL DEFAULT 0,
                faces TEXT NOT NULL DEFAULT '',
                smile REAL NOT NULL DEFAULT 0,
                eyes REAL NOT NULL DEFAULT 0,
                sharp REAL NOT NULL DEFAULT 0,
                error INTEGER NOT NULL DEFAULT 0,
                blocked INTEGER NOT NULL DEFAULT 0,
                block_reason TEXT,
                last_shown INTEGER NOT NULL DEFAULT 0,
                phash INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_pool ON photos(portrait, face_count, blocked, score)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 2 added fingerprints for spotting similar photos. Existing scan results are kept.
        if (oldVersion < 2) db.execSQL("ALTER TABLE photos ADD COLUMN phash INTEGER")
    }

    fun tx(block: () -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Saves a scan result. Keeps the "blocked" flag and rotation history. */
    fun upsert(item: MediaItem, a: Analysis?) {
        val cv = ContentValues().apply {
            put("bucket_id", item.bucketId)
            put("bucket_name", item.bucketName)
            put("date_modified", item.dateModified)
            put("date_taken", item.dateTaken)
            if (a == null) {
                put("error", 1)
                put("portrait", 0)
                put("face_count", 0)
                put("face_ratio", 0f)
                put("score", 0f)
                put("faces", "")
            } else {
                put("error", 0)
                put("width", a.width)
                put("height", a.height)
                put("portrait", if (a.portrait) 1 else 0)
                put("face_count", a.faceCount)
                put("face_ratio", a.faceRatio)
                put("score", a.score)
                put("faces", encodeFaces(a.faces))
                put("smile", a.smile)
                put("eyes", a.eyes)
                put("sharp", a.sharp)
                if (a.phash != null) put("phash", a.phash) else putNull("phash")
            }
        }
        val db = writableDatabase
        val updated = db.update("photos", cv, "id=?", arrayOf(item.id.toString()))
        if (updated == 0) {
            cv.put("id", item.id)
            db.insert("photos", null, cv)
        }
    }

    /** id -> date_modified of everything already scanned. */
    fun knownState(): Map<Long, Long> {
        val map = HashMap<Long, Long>()
        readableDatabase.rawQuery("SELECT id, date_modified FROM photos", null).use { c ->
            while (c.moveToNext()) map[c.getLong(0)] = c.getLong(1)
        }
        return map
    }

    /** Forget photos that were deleted from these folders. */
    fun deleteMissing(present: Set<Long>, buckets: Set<String>) {
        val gone = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT id, bucket_id FROM photos", null).use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) in buckets && c.getLong(0) !in present) gone += c.getLong(0)
            }
        }
        if (gone.isEmpty()) return
        tx { gone.forEach { writableDatabase.delete("photos", "id=?", arrayOf(it.toString())) } }
    }

    /** Makes the next scan re-analyse everything (used when the scoring changes). */
    fun invalidateAll() {
        writableDatabase.execSQL("UPDATE photos SET date_modified = -1")
    }

    private fun marks(buckets: Set<String>) = buckets.joinToString(",") { "?" }

    /** Every photo that meets the criteria (portrait, big enough face, not removed), best first. */
    fun qualifying(buckets: Set<String>, minRatio: Float): List<Photo> {
        if (buckets.isEmpty()) return emptyList()
        val args = arrayOf(minRatio.toString()) + buckets.toTypedArray()
        return readableDatabase.rawQuery(
            "SELECT * FROM photos WHERE portrait=1 AND face_count>0 AND blocked=0 AND face_ratio>=? " +
                "AND bucket_id IN (${marks(buckets)}) ORDER BY score DESC",
            args,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toPhoto()) } }
    }

    /** Photos with faces that were scanned before fingerprints existed. */
    fun missingHash(buckets: Set<String>): List<Long> {
        if (buckets.isEmpty()) return emptyList()
        return readableDatabase.rawQuery(
            "SELECT id FROM photos WHERE portrait=1 AND face_count>0 AND phash IS NULL " +
                "AND bucket_id IN (${marks(buckets)})",
            buckets.toTypedArray(),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    fun setHash(id: Long, hash: Long) {
        val cv = ContentValues().apply { put("phash", hash) }
        writableDatabase.update("photos", cv, "id=?", arrayOf(id.toString()))
    }

    fun get(id: Long): Photo? =
        readableDatabase.rawQuery("SELECT * FROM photos WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toPhoto() else null
        }

    fun block(id: Long, reason: String) {
        val cv = ContentValues().apply { put("blocked", 1); put("block_reason", reason) }
        writableDatabase.update("photos", cv, "id=?", arrayOf(id.toString()))
    }

    fun unblockAll() {
        writableDatabase.execSQL("UPDATE photos SET blocked=0, block_reason=NULL")
    }

    fun blockedCount(): Int = count("SELECT COUNT(*) FROM photos WHERE blocked=1")

    fun scannedCount(): Int = count("SELECT COUNT(*) FROM photos WHERE date_modified >= 0")

    fun markShown(id: Long, time: Long) {
        val cv = ContentValues().apply { put("last_shown", time) }
        writableDatabase.update("photos", cv, "id=?", arrayOf(id.toString()))
    }

    private fun count(sql: String): Int =
        readableDatabase.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun Cursor.toPhoto(): Photo = Photo(
        id = getLong(getColumnIndexOrThrow("id")),
        bucketId = getString(getColumnIndexOrThrow("bucket_id")),
        bucketName = getString(getColumnIndexOrThrow("bucket_name")),
        width = getInt(getColumnIndexOrThrow("width")),
        height = getInt(getColumnIndexOrThrow("height")),
        faceCount = getInt(getColumnIndexOrThrow("face_count")),
        faceRatio = getFloat(getColumnIndexOrThrow("face_ratio")),
        score = getFloat(getColumnIndexOrThrow("score")),
        faces = decodeFaces(getString(getColumnIndexOrThrow("faces"))),
        smile = getFloat(getColumnIndexOrThrow("smile")),
        eyes = getFloat(getColumnIndexOrThrow("eyes")),
        sharp = getFloat(getColumnIndexOrThrow("sharp")),
        blocked = getInt(getColumnIndexOrThrow("blocked")) == 1,
        lastShown = getLong(getColumnIndexOrThrow("last_shown")),
        dateTaken = getLong(getColumnIndexOrThrow("date_taken")),
        phash = getColumnIndexOrThrow("phash").let { if (isNull(it)) null else getLong(it) },
    )

    companion object {
        @Volatile private var instance: PhotoDb? = null

        fun get(context: Context): PhotoDb =
            instance ?: synchronized(this) {
                instance ?: PhotoDb(context.applicationContext).also { instance = it }
            }

        fun encodeFaces(faces: List<RectF>): String = faces.joinToString(";") {
            String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", it.left, it.top, it.right, it.bottom)
        }

        fun decodeFaces(s: String?): List<RectF> {
            if (s.isNullOrBlank()) return emptyList()
            return s.split(';').mapNotNull { part ->
                val v = part.split(',').mapNotNull { it.toFloatOrNull() }
                if (v.size == 4) RectF(v[0], v[1], v[2], v[3]) else null
            }
        }
    }
}
