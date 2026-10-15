package nl.jdatje.framily.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** All user settings, stored on the phone. */
class Prefs private constructor(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("framily", Context.MODE_PRIVATE)

    /** Minutes between wallpaper changes. */
    var intervalMinutes: Int
        get() = sp.getInt(INTERVAL, 120)
        set(v) = sp.edit { putInt(INTERVAL, v) }

    /** How many of the best photos are in the rotation. */
    var poolSize: Int
        get() = sp.getInt(POOL, 40)
        set(v) = sp.edit { putInt(POOL, v) }

    /** Minimum height of the largest face, as % of the photo height. */
    var minFacePercent: Int
        get() = sp.getInt(MIN_FACE, 10)
        set(v) = sp.edit { putInt(MIN_FACE, v) }

    /** 0 = keep all, 1 = group very similar photos, 2 = also group somewhat similar ones. */
    var dedupeLevel: Int
        get() = sp.getInt(DEDUPE, 1)
        set(v) = sp.edit { putInt(DEDUPE, v) }

    /** Only photos with a recognized family member. */
    var familyOnly: Boolean
        get() = sp.getBoolean(FAMILY_ONLY, false)
        set(v) = sp.edit { putBoolean(FAMILY_ONLY, v) }

    /** How alike a face must be to a family member's (0.45 loose … 0.70 strict). */
    var familyThreshold: Float
        get() = sp.getFloat(FAMILY_THRESHOLD, 0.55f)
        set(v) = sp.edit { putFloat(FAMILY_THRESHOLD, v) }

    /** 0 = gentle, 1 = normal, 2 = fast. */
    var scanSpeed: Int
        get() = sp.getInt(SCAN_SPEED, 0)
        set(v) = sp.edit { putInt(SCAN_SPEED, v) }

    var scanOnlyCharging: Boolean
        get() = sp.getBoolean(SCAN_CHARGING, true)
        set(v) = sp.edit { putBoolean(SCAN_CHARGING, v) }

    var clockEnabled: Boolean
        get() = sp.getBoolean(CLOCK, true)
        set(v) = sp.edit { putBoolean(CLOCK, v) }

    /** Top of the clock, as a fraction of the screen height. */
    var clockPosition: Float
        get() = sp.getFloat(CLOCK_POS, 0.18f)
        set(v) = sp.edit { putFloat(CLOCK_POS, v) }

    var clockFont: String
        get() = sp.getString(CLOCK_FONT, "bold") ?: "bold"
        set(v) = sp.edit { putString(CLOCK_FONT, v) }

    /** "stacked" (hours above minutes) or "line" (07:50). */
    var clockLayout: String
        get() = sp.getString(CLOCK_LAYOUT, "stacked") ?: "stacked"
        set(v) = sp.edit { putString(CLOCK_LAYOUT, v) }

    var clockSize: Float
        get() = sp.getFloat(CLOCK_SIZE, 1f)
        set(v) = sp.edit { putFloat(CLOCK_SIZE, v) }

    /** "photo", "photo_dark", "white" or "black". */
    var clockColor: String
        get() = sp.getString(CLOCK_COLOR, "photo") ?: "photo"
        set(v) = sp.edit { putString(CLOCK_COLOR, v) }

    var clockOpacity: Float
        get() = sp.getFloat(CLOCK_OPACITY, 0.92f)
        set(v) = sp.edit { putFloat(CLOCK_OPACITY, v) }

    var clockDate: Boolean
        get() = sp.getBoolean(CLOCK_DATE, false)
        set(v) = sp.edit { putBoolean(CLOCK_DATE, v) }

    var weatherEnabled: Boolean
        get() = sp.getBoolean(WEATHER, false)
        set(v) = sp.edit { putBoolean(WEATHER, v) }

    var weatherForecast: Boolean
        get() = sp.getBoolean(WEATHER_FORECAST, true)
        set(v) = sp.edit { putBoolean(WEATHER_FORECAST, v) }

    /**
     * "under" (below the clock), "sides" (current weather left of the clock, forecast right of it)
     * or "custom" (wherever you dragged them).
     */
    var weatherPlacement: String
        get() = when (val v = sp.getString(WEATHER_PLACEMENT, "sides")) {
            "clock" -> "under"      // name used by version 0.4
            "separate" -> "custom"  // name used by version 0.4
            null -> "sides"
            else -> v
        }
        set(v) = sp.edit { putString(WEATHER_PLACEMENT, v) }

    var weatherSize: Float
        get() = sp.getFloat(WEATHER_SIZE, 1f)
        set(v) = sp.edit { putFloat(WEATHER_SIZE, v) }

    var forecastSize: Float
        get() = sp.getFloat(FORECAST_SIZE, 0.8f)
        set(v) = sp.edit { putFloat(FORECAST_SIZE, v) }

    /** Forecast hours below each other instead of side by side (for "custom"). */
    var forecastVertical: Boolean
        get() = sp.getBoolean(FORECAST_VERTICAL, false)
        set(v) = sp.edit { putBoolean(FORECAST_VERTICAL, v) }

    /** Custom positions: centers as fractions of the screen. */
    var weatherX: Float
        get() = sp.getFloat(WEATHER_X, 0.5f)
        set(v) = sp.edit { putFloat(WEATHER_X, v) }
    var weatherY: Float
        get() = sp.getFloat(WEATHER_Y, 0.62f)
        set(v) = sp.edit { putFloat(WEATHER_Y, v) }
    var forecastX: Float
        get() = sp.getFloat(FORECAST_X, 0.5f)
        set(v) = sp.edit { putFloat(FORECAST_X, v) }
    var forecastY: Float
        get() = sp.getFloat(FORECAST_Y, 0.72f)
        set(v) = sp.edit { putFloat(FORECAST_Y, v) }

    /** Horizontal center of the clock, as a fraction of the screen width. */
    var clockX: Float
        get() = sp.getFloat(CLOCK_X, 0.5f)
        set(v) = sp.edit { putFloat(CLOCK_X, v) }

    /** Place the clock automatically, just behind the main person's head. */
    var clockAuto: Boolean
        get() = sp.getBoolean(CLOCK_AUTO, true)
        set(v) = sp.edit { putBoolean(CLOCK_AUTO, v) }

    /** "location" (phone's approximate location) or "city". */
    var weatherSource: String
        get() = sp.getString(WEATHER_SOURCE, "location") ?: "location"
        set(v) = sp.edit { putString(WEATHER_SOURCE, v) }

    /** Where the weather is for; null when not known yet. */
    var weatherPlace: Triple<Double, Double, String>?
        get() {
            val lat = sp.getString(WEATHER_LAT, null)?.toDoubleOrNull() ?: return null
            val lon = sp.getString(WEATHER_LON, null)?.toDoubleOrNull() ?: return null
            return Triple(lat, lon, sp.getString(WEATHER_PLACE_NAME, "") ?: "")
        }
        set(v) = sp.edit {
            if (v == null) {
                remove(WEATHER_LAT); remove(WEATHER_LON); remove(WEATHER_PLACE_NAME)
            } else {
                putString(WEATHER_LAT, v.first.toString())
                putString(WEATHER_LON, v.second.toString())
                putString(WEATHER_PLACE_NAME, v.third)
            }
        }

    /** The last fetched weather, as JSON. */
    var weatherData: String?
        get() = sp.getString(WEATHER_DATA, null)
        set(v) = sp.edit { putString(WEATHER_DATA, v) }

    /** Put people in front of the clock. */
    var depthEnabled: Boolean
        get() = sp.getBoolean(DEPTH, true)
        set(v) = sp.edit { putBoolean(DEPTH, v) }

    var lockScreenEnabled: Boolean
        get() = sp.getBoolean(LOCK, false)
        set(v) = sp.edit { putBoolean(LOCK, v) }

    var doubleTapEnabled: Boolean
        get() = sp.getBoolean(DOUBLE_TAP, true)
        set(v) = sp.edit { putBoolean(DOUBLE_TAP, v) }

    var selectedBuckets: Set<String>
        get() = sp.getStringSet(BUCKETS, null)?.toSet() ?: emptySet()
        set(v) = sp.edit { putStringSet(BUCKETS, HashSet(v)) }

    var bucketsInitialized: Boolean
        get() = sp.getBoolean(BUCKETS_INIT, false)
        set(v) = sp.edit { putBoolean(BUCKETS_INIT, v) }

    var currentPhotoId: Long
        get() = sp.getLong(CURRENT, -1L)
        set(v) = sp.edit { putLong(CURRENT, v) }

    var scorerVersion: Int
        get() = sp.getInt(SCORER, 0)
        set(v) = sp.edit { putInt(SCORER, v) }

    /** The key of every setting that changes. */
    fun keyChanges(): Flow<String?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> trySend(key) }
        sp.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /** Emits once right away and again whenever any setting changes. */
    fun changes(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        sp.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        const val INTERVAL = "interval_minutes"
        const val POOL = "pool_size"
        const val MIN_FACE = "min_face_percent"
        const val DEDUPE = "dedupe_level"
        const val SCAN_SPEED = "scan_speed"
        const val FAMILY_ONLY = "family_only"
        const val FAMILY_THRESHOLD = "family_threshold"
        const val SCAN_CHARGING = "scan_only_charging"
        const val CLOCK = "clock_enabled"
        const val CLOCK_POS = "clock_position"
        const val CLOCK_FONT = "clock_font"
        const val CLOCK_LAYOUT = "clock_layout"
        const val CLOCK_SIZE = "clock_size"
        const val CLOCK_COLOR = "clock_color"
        const val CLOCK_OPACITY = "clock_opacity"
        const val CLOCK_DATE = "clock_date"

        const val WEATHER = "weather_enabled"
        const val WEATHER_FORECAST = "weather_forecast"
        const val WEATHER_PLACEMENT = "weather_placement"
        const val WEATHER_SIZE = "weather_size"
        const val FORECAST_SIZE = "forecast_size"
        const val FORECAST_VERTICAL = "forecast_vertical"
        const val WEATHER_X = "weather_x"
        const val WEATHER_Y = "weather_y"
        const val FORECAST_X = "forecast_x"
        const val FORECAST_Y = "forecast_y"
        const val CLOCK_X = "clock_x"
        const val CLOCK_AUTO = "clock_auto"
        const val WEATHER_SOURCE = "weather_source"
        const val WEATHER_LAT = "weather_lat"
        const val WEATHER_LON = "weather_lon"
        const val WEATHER_PLACE_NAME = "weather_place_name"
        const val WEATHER_DATA = "weather_data"

        /** Settings that only change what is drawn on top of the photo (no new photo needed). */
        val CLOCK_KEYS = setOf(
            "clock_enabled", "clock_position", CLOCK_FONT, CLOCK_LAYOUT,
            CLOCK_SIZE, CLOCK_COLOR, CLOCK_OPACITY, CLOCK_DATE,
            WEATHER, WEATHER_FORECAST, WEATHER_PLACEMENT, WEATHER_DATA,
            WEATHER_SIZE, FORECAST_SIZE, FORECAST_VERTICAL, WEATHER_X, WEATHER_Y, FORECAST_X, FORECAST_Y,
            CLOCK_X, CLOCK_AUTO,
        )
        const val DEPTH = "depth_enabled"
        const val LOCK = "lock_screen"
        const val DOUBLE_TAP = "double_tap"
        const val BUCKETS = "buckets"
        const val BUCKETS_INIT = "buckets_initialized"
        const val CURRENT = "current_photo"
        const val SCORER = "scorer_version"

        @Volatile private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }
    }
}
