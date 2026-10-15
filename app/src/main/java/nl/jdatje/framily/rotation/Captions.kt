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
            val years = OnThisDay.yearsAgoToday(photo.dateTaken)
            parts += when {
                years == 1 -> "1 year ago today"
                years != null && years > 1 -> "$years years ago today"
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
    /** How many years ago today (±2 days) this was taken, or null if it wasn't around this date. */
    fun yearsAgoToday(taken: Long, now: Long = System.currentTimeMillis()): Int? {
        val t = Calendar.getInstance().apply { timeInMillis = taken }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        val years = n.get(Calendar.YEAR) - t.get(Calendar.YEAR)
        if (years < 1) return null
        // Same date in this year, to compare day-of-year safely (leap years).
        t.set(Calendar.YEAR, n.get(Calendar.YEAR))
        val days = kotlin.math.abs(t.get(Calendar.DAY_OF_YEAR) - n.get(Calendar.DAY_OF_YEAR))
        return if (days <= 2 || days >= 363) years else null
    }
}
