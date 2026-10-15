package nl.jdatje.framily.scan

import android.content.ContentUris
import android.content.Context
import android.content.pm.ServiceInfo
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.jdatje.framily.Notifications
import nl.jdatje.framily.Permissions
import nl.jdatje.framily.data.MediaRepo
import nl.jdatje.framily.data.PhotoDb
import nl.jdatje.framily.data.Prefs
import nl.jdatje.framily.rotation.PoolBuilder
import nl.jdatje.framily.rotation.Rotator
import nl.jdatje.framily.util.Fingerprint
import nl.jdatje.framily.util.ImageUtils

/**
 * Goes through the selected folders and analyses every photo it hasn't seen yet.
 * Stops safely at any moment; the next run continues where it left off.
 */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val DONE = "done"
        const val TOTAL = "total"
        private const val TAG = "ScanWorker"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    private fun foregroundInfo(done: Int, total: Int) = ForegroundInfo(
        Notifications.ID_SCAN,
        Notifications.scanNotification(applicationContext, done, total),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private suspend fun tryForeground(done: Int, total: Int) {
        try {
            setForeground(foregroundInfo(done, total))
        } catch (e: Exception) {
            // Android doesn't always allow this from the background; the scan still runs.
            Log.w(TAG, "Could not run in foreground: ${e.message}")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        if (!Permissions.hasMedia(ctx)) return@withContext Result.failure()
        tryForeground(0, 0)

        val prefs = Prefs.get(ctx)
        val db = PhotoDb.get(ctx)
        if (prefs.scorerVersion != PhotoAnalyzer.VERSION) {
            db.invalidateAll()
            prefs.scorerVersion = PhotoAnalyzer.VERSION
        }

        val buckets = prefs.selectedBuckets
        val items = MediaRepo.allItems(ctx).filter { it.bucketId in buckets }
        db.deleteMissing(items.map { it.id }.toSet(), buckets)

        val known = db.knownState()
        val todo = items.filter { known[it.id] != it.dateModified }

        // Landscape photos are skipped without even opening them.
        val (landscape, portraitOrUnknown) = todo.partition { it.portraitByMetadata == false }
        // Newest photos first, so recent ones show up quickly.
        val toDecode = portraitOrUnknown.sortedByDescending { it.dateTaken }
        db.tx { landscape.forEach { db.upsert(it, Analysis.empty(false, it.width, it.height)) } }

        // Photos scanned by an older version only need a fingerprint, not a full analysis.
        val needHash = db.missingHash(buckets)

        val total = toDecode.size + needHash.size
        setProgress(workDataOf(DONE to 0, TOTAL to total))
        var done = 0

        for (chunk in needHash.chunked(50)) {
            if (isStopped) break
            val hashes = chunk.mapNotNull { id ->
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val bmp = ImageUtils.decodeScaled(ctx, uri, 256) ?: return@mapNotNull null
                try {
                    id to Fingerprint.dHash(bmp)
                } finally {
                    bmp.recycle()
                }
            }
            db.tx { hashes.forEach { (id, h) -> db.setHash(id, h) } }
            done += chunk.size
            setProgress(workDataOf(DONE to done, TOTAL to total))
        }

        PhotoAnalyzer(ctx).use { analyzer ->
            for (chunk in toDecode.chunked(20)) {
                if (isStopped) break
                val results = chunk.map { item ->
                    item to try {
                        analyzer.analyze(item.uri)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed on ${item.id}: ${e.message}")
                        null
                    }
                }
                db.tx { results.forEach { (item, a) -> db.upsert(item, a) } }
                done += chunk.size
                setProgress(workDataOf(DONE to done, TOTAL to total))
                if (done % 200 < chunk.size) tryForeground(done, total)
                // First run: show a wallpaper as soon as there is a usable photo.
                if (prefs.currentPhotoId < 0 &&
                    db.qualifying(buckets, prefs.minFacePercent / 100f).isNotEmpty()
                ) {
                    Rotator.next(ctx)
                }
            }
        }

        // Make sure something sensible is on screen.
        val pool = PoolBuilder.build(ctx).photos
        if (pool.isNotEmpty() && pool.none { it.id == prefs.currentPhotoId }) {
            Rotator.next(ctx)
        }
        Result.success()
    }
}
