package nl.jdatje.framily

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import nl.jdatje.framily.data.Photo
import nl.jdatje.framily.feedback.Feedback
import nl.jdatje.framily.feedback.FeedbackReceiver
import nl.jdatje.framily.ui.MainActivity
import nl.jdatje.framily.util.ImageUtils
import kotlin.math.roundToInt

object Notifications {
    private const val CH_WALLPAPER = "wallpaper"
    private const val CH_SCAN = "scan"
    const val ID_CURRENT = 1
    const val ID_SCAN = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_WALLPAPER, "Current wallpaper", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Buttons to skip or remove the current photo"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SCAN, "Photo scan", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while Framily looks through your photos"
            }
        )
    }

    private fun openAppIntent(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun actionIntent(context: Context, action: String, photoId: Long, requestCode: Int) =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, FeedbackReceiver::class.java)
                .putExtra(FeedbackReceiver.EXTRA_ACTION, action)
                .putExtra(FeedbackReceiver.EXTRA_PHOTO, photoId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    @SuppressLint("MissingPermission")
    fun showCurrent(context: Context, photo: Photo) {
        if (!Permissions.hasNotifications(context)) return
        val thumb = ImageUtils.decodeScaled(context, photo.uri, 256)
        val n = NotificationCompat.Builder(context, CH_WALLPAPER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Current wallpaper")
            .setContentText("Score ${photo.score.roundToInt()} · ${photo.bucketName}")
            .setLargeIcon(thumb)
            .setContentIntent(openAppIntent(context))
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Skip", actionIntent(context, Feedback.SKIP, photo.id, 10))
            .addAction(0, "Not family", actionIntent(context, Feedback.NOT_FAMILY, photo.id, 11))
            .addAction(0, "Never show", actionIntent(context, Feedback.NEVER, photo.id, 12))
            .build()
        NotificationManagerCompat.from(context).notify(ID_CURRENT, n)
    }

    fun scanNotification(context: Context, done: Int, total: Int): Notification =
        NotificationCompat.Builder(context, CH_SCAN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Looking through your photos")
            .setContentText(if (total > 0) "$done of $total" else "Preparing…")
            .setProgress(total, done, total == 0)
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setSilent(true)
            .build()
}
