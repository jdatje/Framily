package nl.jdatje.framily

import android.app.Application
import nl.jdatje.framily.weather.WeatherRepo

class FramilyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Notifications.createChannels(this)
        Scheduler.scheduleRotation(this)
        Scheduler.schedulePeriodicScan(this)
        WeatherRepo.schedule(this)

        val prefs = getSharedPreferences("framily_internal", MODE_PRIVATE)
        // Scores from an older formula: recompute from the stored parts (no rescan needed).
        if (prefs.getInt("score_version", 1) < nl.jdatje.framily.scan.Scorer.VERSION) {
            Thread {
                try {
                    nl.jdatje.framily.scan.Scorer.recomputeAll(this)
                    prefs.edit().putInt("score_version", nl.jdatje.framily.scan.Scorer.VERSION).apply()
                } catch (e: Exception) {
                    CrashLog.record(this, e, fatal = false)
                }
            }.start()
        }
        // A scan queued by an older version has old battery conditions: re-queue it once.
        if (prefs.getInt("scan_request_version", 0) < 3) {
            Thread {
                if (Scheduler.scanPending(this)) Scheduler.scanNow(this, restart = true)
                prefs.edit().putInt("scan_request_version", 3).apply()
            }.start()
        }
    }
}
