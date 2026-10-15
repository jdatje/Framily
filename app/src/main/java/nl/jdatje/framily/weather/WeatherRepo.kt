package nl.jdatje.framily.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nl.jdatje.framily.data.Prefs
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object WeatherRepo {
    private const val TAG = "Weather"
    private const val PERIODIC = "weather_periodic"
    private const val NOW = "weather_now"

    @Volatile private var cacheJson: String? = null
    @Volatile private var cache: WeatherData? = null

    /** The last fetched weather, or null if there is none or it's more than 6 hours old. */
    fun current(context: Context): WeatherData? {
        val json = Prefs.get(context).weatherData
        if (json != cacheJson) {
            cache = WeatherData.fromJson(json)
            cacheJson = json
        }
        return cache?.takeIf { System.currentTimeMillis() - it.fetchedAt < 6 * 3600_000L }
    }

    /** Download fresh weather for the saved place. */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val (lat, lon, name) = Prefs.get(context).weatherPlace ?: return@withContext false
        try {
            Prefs.get(context).weatherData = WeatherApi.forecast(lat, lon, name).toJson()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed: ${e.message}")
            false
        }
    }

    /** Hourly refresh while weather is on (only with internet). */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!Prefs.get(context).weatherEnabled) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<WeatherWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun refreshSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<WeatherWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun hasLocationPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * With "My location": save the phone's approximate location. Called while Framily is open,
     * so no background-location permission is needed; the wallpaper uses the last saved place.
     * Returns true when the place changed noticeably.
     */
    suspend fun updateFromPhoneLocation(context: Context): Boolean {
        val prefs = Prefs.get(context)
        if (prefs.weatherSource != "location" || !hasLocationPermission(context)) return false
        val loc = currentLocation(context) ?: return false
        val old = prefs.weatherPlace
        val moved = old == null || distanceKm(old.first, old.second, loc.latitude, loc.longitude) > 5
        if (!moved) return false
        prefs.weatherPlace = Triple(loc.latitude, loc.longitude, placeName(context, loc))
        return true
    }

    @Suppress("MissingPermission")
    private suspend fun currentLocation(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java)
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            else -> null
        }
        val fresh: Location? = if (provider != null) {
            withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    try {
                        lm.getCurrentLocation(provider, null, context.mainExecutor) { cont.resume(it) }
                    } catch (e: Exception) {
                        cont.resume(null)
                    }
                }
            }
        } else null
        return fresh ?: listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstNotNullOfOrNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
    }

    private suspend fun placeName(context: Context, loc: Location): String {
        if (!Geocoder.isPresent()) return ""
        return withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                try {
                    Geocoder(context).getFromLocation(loc.latitude, loc.longitude, 1) { list ->
                        cont.resume(list.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea } ?: "")
                    }
                } catch (e: Exception) {
                    cont.resume("")
                }
            }
        } ?: ""
    }

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0] / 1000f
    }
}

class WeatherWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when {
        Prefs.get(applicationContext).weatherPlace == null -> Result.success() // nothing to fetch yet
        WeatherRepo.refresh(applicationContext) -> Result.success()
        else -> Result.retry()
    }
}
