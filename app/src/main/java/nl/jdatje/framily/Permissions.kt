package nl.jdatje.framily

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {
    private fun granted(context: Context, p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun hasMedia(context: Context) = granted(context, Manifest.permission.READ_MEDIA_IMAGES)

    /** The user chose "Select photos" instead of "Allow all". */
    fun partialMedia(context: Context) =
        Build.VERSION.SDK_INT >= 34 && !hasMedia(context) &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    fun hasNotifications(context: Context) = granted(context, Manifest.permission.POST_NOTIFICATIONS)

    val requestList: Array<String>
        get() = buildList {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }.toTypedArray()
}
