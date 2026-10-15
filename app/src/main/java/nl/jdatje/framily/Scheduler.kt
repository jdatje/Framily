package nl.jdatje.framily

import android.content.Context
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
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(SCAN_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun scanNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ScanWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SCAN_NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun stopScan(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SCAN_NOW)
    }

    fun rotateNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<RotationWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(ROTATE_NOW, ExistingWorkPolicy.REPLACE, request)
    }
}
