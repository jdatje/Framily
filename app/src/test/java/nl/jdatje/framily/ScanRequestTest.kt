package nl.jdatje.framily

import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import nl.jdatje.framily.scan.ScanWorker
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRequestTest {
    /** The 0.3–0.6 bug: an expedited scan with a battery condition is rejected by WorkManager. */
    @Test
    fun expeditedWithBatteryConstraintIsRejected() {
        val result = runCatching {
            OneTimeWorkRequestBuilder<ScanWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        }
        println("old request: ${result.exceptionOrNull()}")
        assertTrue(result.isFailure)
    }

    /** The fixed request builds fine both with and without "only while charging". */
    @Test
    fun scanRequestBuilds() {
        val a = Scheduler.scanRequest(onlyCharging = true)
        val b = Scheduler.scanRequest(onlyCharging = false)
        assertTrue(a.workSpec.constraints.requiresCharging())
        assertTrue(!b.workSpec.constraints.requiresCharging())
        assertTrue(!a.workSpec.expedited && !b.workSpec.expedited)
    }
}
