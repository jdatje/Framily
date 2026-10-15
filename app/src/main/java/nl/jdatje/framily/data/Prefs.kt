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

    var clockEnabled: Boolean
        get() = sp.getBoolean(CLOCK, true)
        set(v) = sp.edit { putBoolean(CLOCK, v) }

    /** Top of the clock, as a fraction of the screen height. */
    var clockPosition: Float
        get() = sp.getFloat(CLOCK_POS, 0.18f)
        set(v) = sp.edit { putFloat(CLOCK_POS, v) }

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
        const val CLOCK = "clock_enabled"
        const val CLOCK_POS = "clock_position"
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
