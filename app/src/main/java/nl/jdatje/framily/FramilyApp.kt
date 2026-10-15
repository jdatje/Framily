package nl.jdatje.framily

import android.app.Application
import nl.jdatje.framily.weather.WeatherRepo

class FramilyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        Scheduler.scheduleRotation(this)
        Scheduler.schedulePeriodicScan(this)
        WeatherRepo.schedule(this)

        // A scan queued by an older version doesn't have the battery limits yet: re-queue it once.
        val prefs = getSharedPreferences("framily_internal", MODE_PRIVATE)
        if (prefs.getInt("scan_request_version", 0) < 2) {
            Thread {
                if (Scheduler.scanPending(this)) Scheduler.scanNow(this, restart = true)
                prefs.edit().putInt("scan_request_version", 2).apply()
            }.start()
        }
    }
}
