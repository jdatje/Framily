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

    suspend fun next(context: Context): Photo? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val prefs = Prefs.get(context)
            val pool = PoolBuilder.build(context).photos
            if (pool.isEmpty()) return@withContext null
            val current = prefs.currentPhotoId
            val candidates = pool.filter { it.id != current }.ifEmpty { pool }
            // Choose randomly among the ones that haven't been shown for the longest time,
            // so every photo in the pool comes by before one repeats.
            val oldestFirst = candidates.sortedBy { it.lastShown }
            val pick = oldestFirst.take(max(1, oldestFirst.size / 4)).random()
            showInternal(context, pick)
            pick
        }
    }

    /** Show this specific photo now. */
    suspend fun show(context: Context, photo: Photo) = mutex.withLock {
        withContext(Dispatchers.IO) { showInternal(context, photo) }
    }

    private fun showInternal(context: Context, photo: Photo) {
        val prefs = Prefs.get(context)
        PhotoDb.get(context).markShown(photo.id, System.currentTimeMillis())
        prefs.currentPhotoId = photo.id // the live wallpaper picks this up by itself
        if (prefs.lockScreenEnabled) LockScreen.apply(context, photo)
        Notifications.showCurrent(context, photo)
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
