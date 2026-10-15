package nl.jdatje.framily

import android.app.Application

class FramilyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        Scheduler.scheduleRotation(this)
        Scheduler.schedulePeriodicScan(this)
    }
}
