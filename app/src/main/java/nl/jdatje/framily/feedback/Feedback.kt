package nl.jdatje.framily.feedback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.jdatje.framily.Notifications
import nl.jdatje.framily.data.PhotoDb
import nl.jdatje.framily.data.Prefs
import nl.jdatje.framily.family.FamilyIndex
import nl.jdatje.framily.rotation.Rotator

object Feedback {
    const val SKIP = "skip"
    const val NOT_FAMILY = "not_family"
    const val NEVER = "never"
    const val LIKE = "like"
    const val DISLIKE = "dislike"
    /** Undo a 👍 or 👎 (from the app). */
    const val UNRATE = "unrate"

    /**
     * Handle a button for a photo.
     * - 👍 Like: +15 and shown 1.5× as often; the photo stays on screen.
     * - 👎 Dislike: −30 (usually out of the top N), then the next photo.
     * - Not family / Never show: removed from the rotation, then the next photo.
     * - Skip: the next photo.
     * [lock]: the photo is the one on the lock screen, so only that one changes.
     */
    suspend fun handle(context: Context, action: String, photoId: Long, lock: Boolean = false) {
        withContext(Dispatchers.IO) {
            val db = PhotoDb.get(context)
            val prefs = Prefs.get(context)
            var moveOn = true
            if (photoId >= 0) {
                when (action) {
                    LIKE -> { db.setRating(photoId, 1); moveOn = false }
                    UNRATE -> { db.setRating(photoId, 0); moveOn = false }
                    DISLIKE -> db.setRating(photoId, -1)
                    NOT_FAMILY -> {
                        db.block(photoId, NOT_FAMILY)
                        // Teach recognition: the faces taken for family on this photo aren't family.
                        FamilyIndex.markPhotoNotFamily(context, photoId)
                    }
                    NEVER -> db.block(photoId, NEVER)
                }
            }
            val onLock = lock && prefs.lockDifferent && photoId == prefs.lockPhotoId
            if (moveOn) {
                if (onLock) Rotator.nextLock(context) else Rotator.next(context)
            } else if (photoId == prefs.currentPhotoId) {
                // Refresh the notification so it shows the 👍.
                db.get(photoId)?.let { Notifications.showCurrent(context, it) }
            }
            if (action == NOT_FAMILY && photoId >= 0) FamilyIndex.rematch(context)
        }
    }
}

/** Handles the buttons on the "Current wallpaper" notification. */
class FeedbackReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_PHOTO = "photo"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val photoId = intent.getLongExtra(EXTRA_PHOTO, -1L)
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Feedback.handle(app, action, photoId)
            } finally {
                pending.finish()
            }
        }
    }
}
