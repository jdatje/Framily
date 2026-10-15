package nl.jdatje.framily

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import nl.jdatje.framily.data.Prefs
import nl.jdatje.framily.rotation.RotationWorker
import nl.jdatje.framily.scan.ScanWorker
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Background jobs: changing the wallpaper on a timer, and scanning for new photos. */
object Scheduler {
    const val ROTATION = "rotation"
    const val ROTATE_NOW = "rotate_now"
    const val SCAN_PERIODIC = "scan_periodic"
    const val SCAN_NOW = "scan_now"

    fun scheduleRotation(context: Context) {
        val minutes = max(15, Prefs.get(context).intervalMinutes).toLong()
        val request = PeriodicWorkRequestBuilder<RotationWorker>(minutes, TimeUnit.MINUTES)
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(ROTATION, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Once a day while charging: pick up new photos. */
    fun schedulePeriodicScan(context: Context) {
        val request = PeriodicWorkRequestBuilder<ScanWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(SCAN_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * The scan job. With "only while charging" it waits for a charger (and pauses when unplugged).
     * Not "expedited": Android doesn't allow that together with battery conditions (that combination
     * crashed versions 0.3–0.6). The scan still runs as a long background job with a notification.
     */
    fun scanRequest(onlyCharging: Boolean): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<ScanWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(onlyCharging)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .addTag(SCAN_NOW)
            .build()

    /** Start a scan. [restart] re-applies changed scan settings to a scan that is already waiting or running. */
    fun scanNow(context: Context, restart: Boolean = false) {
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                SCAN_NOW,
                if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                scanRequest(Prefs.get(context).scanOnlyCharging),
            )
        } catch (e: Exception) {
            // Never let scheduling take the app down.
            Log.e("Scheduler", "Could not queue scan", e)
            CrashLog.record(context, e, fatal = false)
        }
    }

    /** True when a scan is waiting to start or running. */
    fun scanPending(context: Context): Boolean = try {
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(SCAN_NOW).get()
            .any { !it.state.isFinished }
    } catch (e: Exception) {
        false
    }

    fun stopScan(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SCAN_NOW)
    }

    fun rotateNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<RotationWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(ROTATE_NOW, ExistingWorkPolicy.REPLACE, request)
    }
}
