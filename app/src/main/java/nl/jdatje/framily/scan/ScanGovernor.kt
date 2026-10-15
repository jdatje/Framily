package nl.jdatje.framily.scan

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.delay

/**
 * Keeps the scan from draining the battery or heating up the phone.
 *
 * - Pauses between photos, depending on the chosen speed.
 * - Slows down further while the screen is on (you're using the phone).
 * - Slows down when the battery drops even though it's plugged in (slow charger),
 *   and speeds up again once it's charging properly.
 * - Pauses completely while the phone is warm.
 * - Stops when running on battery and the battery gets below 30%.
 */
class ScanGovernor(context: Context, speed: Int, private val onlyWhileCharging: Boolean) {

    enum class Pause { NONE, WARM }

    private val battery = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    private val baseDelayMs = when (speed) {
        0 -> 600L  // gentle
        1 -> 150L  // normal
        else -> 0L // fast
    }

    private var slowdown = 1
    private var photosSinceCheck = 24 // so the very first photo checks the battery
    private var lastLevel = level()

    private fun level() = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    private fun charging() = battery.isCharging
    private fun warm() = power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE

    /**
     * Call before each photo. Waits as long as needed.
     * Returns false when the scan should stop for now (it continues later).
     */
    suspend fun beforeNext(isStopped: () -> Boolean, report: suspend (Pause) -> Unit): Boolean {
        if (++photosSinceCheck >= 25) {
            photosSinceCheck = 0
            val lvl = level()
            val plugged = charging()
            if (!plugged && (onlyWhileCharging || lvl <= 30)) return false
            if (plugged) {
                // Battery going down while plugged in: the scan uses more than the charger gives.
                slowdown = if (lvl < lastLevel) minOf(slowdown * 2, 16) else maxOf(1, slowdown / 2)
            }
            lastLevel = lvl
        }

        if (warm()) {
            report(Pause.WARM)
            while (warm()) {
                if (isStopped()) return false
                delay(30_000)
            }
            report(Pause.NONE)
        }

        val base = if (slowdown > 1) maxOf(baseDelayMs, 100L) else baseDelayMs
        val screenOn = if (power.isInteractive) 2 else 1
        val wait = base * slowdown * screenOn
        if (wait > 0) delay(wait)
        return !isStopped()
    }
}
