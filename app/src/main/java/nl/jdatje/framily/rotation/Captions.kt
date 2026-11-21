package nl.jdatje.framily.rotation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import nl.jdatje.framily.data.Photo
import nl.jdatje.framily.data.Prefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/** Builds the small caption under a wallpaper, e.g. "June 2021 · Texel" or "7 years ago today". */
object Captions {

    suspend fun forPhoto(context: Context, photo: Photo): String? {
        val parts = ArrayList<String>()
        if (photo.dateTaken > 0) {
            val m = OnThisDay.match(photo.dateTaken, window = Prefs.get(context).onThisDayDays)
            parts += when {
                m != null -> {
                    val ago = if (m.years == 1) "1 year ago" else "${m.years} years ago"
                    when {
                        m.days <= 1 -> "$ago today"
                        m.days <= 7 -> "$ago this week"
                        else -> "Around this time, $ago"
                    }
                }
                else -> SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(photo.dateTaken))
                    .replaceFirstChar { it.titlecase(Locale.getDefault()) }
            }
        }
        place(context, photo)?.let { parts += it }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** Town where the photo was taken (from its GPS data), or null. WhatsApp photos usually have none. */
    private suspend fun place(context: Context, photo: Photo): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val latLong = try {
            context.contentResolver.openInputStream(MediaStore.setRequireOriginal(photo.uri))?.use {
                ExifInterface(it).latLong
            }
        } catch (e: Exception) {
            null
        } ?: return null
        if (!Geocoder.isPresent()) return null
        return withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                try {
                    Geocoder(context).getFromLocation(latLong[0], latLong[1], 1) { list ->
                        val a = list.firstOrNull()
                        cont.resume(a?.locality ?: a?.subAdminArea ?: a?.adminArea ?: a?.countryName)
                    }
                } catch (e: Exception) {
                    cont.resume(null)
                }
            }
        }
    }
}

/** "On this day": photos taken around today's date in earlier years. */
object OnThisDay {
    /** [years] ago, [days] before or after today's date. */
    class Match(val years: Int, val days: Int)

    /** When this photo was taken within ±[window] days of today's date in an earlier year, or null. */
    fun match(taken: Long, now: Long = System.currentTimeMillis(), window: Int = 7): Match? {
        val t = Calendar.getInstance().apply { timeInMillis = taken }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        var best: Match? = null
        // Compare with the same date one year before and after too, so late December / early January work.
        for (shift in -1..1) {
            val candidate = (t.clone() as Calendar).apply { set(Calendar.YEAR, n.get(Calendar.YEAR) + shift) }
            val days = kotlin.math.abs(((candidate.timeInMillis - now) / 86_400_000.0)).let { kotlin.math.round(it).toInt() }
            val years = n.get(Calendar.YEAR) + shift - t.get(Calendar.YEAR)
            if (years >= 1 && days <= window && (best == null || days < best.days)) best = Match(years, days)
        }
        return best
    }

    /** Kept for older callers: years ago, within ±2 days. */
    fun yearsAgoToday(taken: Long, now: Long = System.currentTimeMillis()): Int? = match(taken, now, 2)?.years
}
