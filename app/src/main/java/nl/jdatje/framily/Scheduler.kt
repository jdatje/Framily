package nl.jdatje.framily

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
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
     * Start a scan. With "only while charging" on, it waits for a charger (and pauses when unplugged).
     * [restart] re-applies changed scan settings to a scan that is already waiting or running.
     */
    fun scanNow(context: Context, restart: Boolean = false) {
        val onlyCharging = Prefs.get(context).scanOnlyCharging
        val builder = OneTimeWorkRequestBuilder<ScanWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(onlyCharging)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .addTag(SCAN_NOW)
        // Expedited work can't wait for a charger, so only use it when charging isn't required.
        if (!onlyCharging) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(context).enqueueUniqueWork(
            SCAN_NOW,
            if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            builder.build(),
        )
    }

    /** True when a scan is waiting to start or running. */
    fun scanPending(context: Context): Boolean =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(SCAN_NOW).get()
            .any { !it.state.isFinished }

    fun stopScan(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SCAN_NOW)
    }

    fun rotateNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<RotationWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(ROTATE_NOW, ExistingWorkPolicy.REPLACE, request)
    }
}
