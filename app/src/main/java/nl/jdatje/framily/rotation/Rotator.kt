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

/** Picks the next wallpaper from the pool (top N plus "on this day" extras). */
object Rotator {
    private val mutex = Mutex()

    /**
     * Fair random pick: photos that haven't been shown for the longest time come first, so every photo
     * comes by before one repeats. A liked photo counts its waiting time 1.7×, which in practice
     * makes it come back about 1.5× as often (tested in RotationTest).
     */
    fun pick(candidates: List<Photo>, now: Long): Photo {
        fun waited(p: Photo) = (now - p.lastShown).toDouble() * (if (p.rating > 0) 1.7 else 1.0)
        val longestWaiting = candidates.sortedByDescending { waited(it) }
        return longestWaiting.take(max(1, longestWaiting.size / 4)).random()
    }

    /** Next photo for the home screen (and the lock screen, unless that has its own photo). */
    suspend fun next(context: Context): Photo? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val prefs = Prefs.get(context)
            val pool = PoolBuilder.build(context).photos
            if (pool.isEmpty()) return@withContext null
            val exclude = setOf(prefs.currentPhotoId, prefs.lockPhotoId)
            val candidates = pool.filter { it.id !in exclude }.ifEmpty { pool }
            val chosen = pick(candidates, System.currentTimeMillis())
            showInternal(context, chosen, pool, newLockPhoto = true)
            chosen
        }
    }

    /** Next photo for the lock screen only. */
    suspend fun nextLock(context: Context): Photo? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val prefs = Prefs.get(context)
            if (!prefs.lockScreenEnabled) return@withContext null
            val pool = PoolBuilder.build(context).photos
            val candidates = pool.filter { it.id != prefs.currentPhotoId && it.id != prefs.lockPhotoId }
            if (candidates.isEmpty()) return@withContext null
            val chosen = pick(candidates, System.currentTimeMillis())
            applyLock(context, chosen)
            chosen
        }
    }

    /** Show this specific photo now. */
    suspend fun show(context: Context, photo: Photo) = mutex.withLock {
        withContext(Dispatchers.IO) { showInternal(context, photo, PoolBuilder.build(context).photos, newLockPhoto = false) }
    }

    private suspend fun showInternal(context: Context, photo: Photo, pool: List<Photo>, newLockPhoto: Boolean) {
        val prefs = Prefs.get(context)
        val db = PhotoDb.get(context)
        db.markShown(photo.id, System.currentTimeMillis())
        // Caption first, so the wallpaper draws photo and caption together.
        prefs.captionText = if (prefs.caption) Captions.forPhoto(context, photo) else null
        prefs.currentPhotoId = photo.id // the live wallpaper picks this up by itself
        if (prefs.lockScreenEnabled) {
            if (prefs.lockDifferent) {
                if (newLockPhoto || prefs.lockPhotoId < 0 || prefs.lockPhotoId == photo.id) {
                    val others = pool.filter { it.id != photo.id }
                    if (others.isNotEmpty()) applyLock(context, pick(others, System.currentTimeMillis()))
                    else applyLock(context, photo)
                }
            } else {
                applyLock(context, photo)
            }
        } else {
            prefs.lockPhotoId = -1L
        }
        Notifications.showCurrent(context, db.get(photo.id) ?: photo)
    }

    private fun applyLock(context: Context, photo: Photo) {
        val prefs = Prefs.get(context)
        if (prefs.lockDifferent) PhotoDb.get(context).markShown(photo.id, System.currentTimeMillis())
        prefs.lockPhotoId = photo.id
        LockScreen.apply(context, photo)
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
