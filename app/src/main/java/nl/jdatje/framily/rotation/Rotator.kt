package nl.jdatje.framily.rotation

import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.jdatje.framily.Notifications
import nl.jdatje.framily.data.Photo
import nl.jdatje.framily.data.PhotoDb
import nl.jdatje.framily.data.Prefs
import nl.jdatje.framily.util.ImageUtils
import kotlin.math.max

/** Picks the next wallpaper from the top-N pool. */
object Rotator {
    private val mutex = Mutex()
    private const val DAY = 24 * 3600_000L

    suspend fun next(context: Context): Photo? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val prefs = Prefs.get(context)
            val result = PoolBuilder.build(context)
            val pool = result.photos
            if (pool.isEmpty()) return@withContext null
            val current = prefs.currentPhotoId
            val now = System.currentTimeMillis()

            // "On this day": a qualifying photo from around this date in an earlier year goes first,
            // even when it isn't in the top N (each one once a day).
            val onThisDay = if (prefs.onThisDay) {
                result.allUnique
                    .filter { it.id != current && now - it.lastShown > DAY * 5 / 6 }
                    .filter { OnThisDay.yearsAgoToday(it.dateTaken, now) != null }
                    .maxByOrNull { it.score }
            } else null

            val pick = onThisDay ?: run {
                val candidates = pool.filter { it.id != current }.ifEmpty { pool }
                // Choose randomly among the ones that haven't been shown for the longest time,
                // so every photo in the pool comes by before one repeats.
                val oldestFirst = candidates.sortedBy { it.lastShown }
                oldestFirst.take(max(1, oldestFirst.size / 4)).random()
            }
            showInternal(context, pick, pool)
            pick
        }
    }

    /** Show this specific photo now. */
    suspend fun show(context: Context, photo: Photo) = mutex.withLock {
        withContext(Dispatchers.IO) { showInternal(context, photo, PoolBuilder.build(context).photos) }
    }

    private suspend fun showInternal(context: Context, photo: Photo, pool: List<Photo>) {
        val prefs = Prefs.get(context)
        val db = PhotoDb.get(context)
        val now = System.currentTimeMillis()
        db.markShown(photo.id, now)
        // Caption first, so the wallpaper draws photo and caption together.
        prefs.captionText = if (prefs.caption) Captions.forPhoto(context, photo) else null
        prefs.currentPhotoId = photo.id // the live wallpaper picks this up by itself
        if (prefs.lockScreenEnabled) {
            val lockPhoto = if (prefs.lockDifferent) {
                pool.filter { it.id != photo.id }.minByOrNull { it.lastShown }?.also { db.markShown(it.id, now) } ?: photo
            } else photo
            LockScreen.apply(context, lockPhoto)
        }
        Notifications.showCurrent(context, photo)
    }

    /** Recompute the caption of the photo on screen (after turning captions on). */
    suspend fun refreshCaption(context: Context) = withContext(Dispatchers.IO) {
        val prefs = Prefs.get(context)
        val photo = PhotoDb.get(context).get(prefs.currentPhotoId)
        prefs.captionText = if (prefs.caption && photo != null) Captions.forPhoto(context, photo) else null
    }
}

class RotationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Rotator.next(applicationContext)
        return Result.success()
    }
}

/** The optional lock screen photo: a plain image, without our clock (the phone shows its own). */
object LockScreen {
    fun apply(context: Context, photo: Photo) {
        val (w, h) = ImageUtils.screenSize(context)
        val bmp = ImageUtils.renderPhoto(context, photo, w, h) ?: return
        try {
            WallpaperManager.getInstance(context).setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
        } catch (e: Exception) {
            Log.w("LockScreen", "Could not set lock screen: ${e.message}")
        } finally {
            bmp.recycle()
        }
    }

    /** Lock screen goes back to showing the same as the home screen. */
    fun clear(context: Context) {
        try {
            WallpaperManager.getInstance(context).clear(WallpaperManager.FLAG_LOCK)
        } catch (e: Exception) {
            Log.w("LockScreen", "Could not clear lock screen: ${e.message}")
        }
    }
}
