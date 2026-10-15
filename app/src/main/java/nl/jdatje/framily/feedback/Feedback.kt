package nl.jdatje.framily.feedback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.jdatje.framily.data.PhotoDb
import nl.jdatje.framily.family.FamilyIndex
import nl.jdatje.framily.rotation.Rotator

object Feedback {
    const val SKIP = "skip"
    const val NOT_FAMILY = "not_family"
    const val NEVER = "never"

    /**
     * "Not family" and "Never show" both remove the photo from the rotation.
     * "Not family" is stored separately, so face recognition can learn from it later.
     */
    suspend fun handle(context: Context, action: String, photoId: Long) {
        withContext(Dispatchers.IO) {
            val db = PhotoDb.get(context)
            if (photoId >= 0) {
                when (action) {
                    NOT_FAMILY -> {
                        db.block(photoId, NOT_FAMILY)
                        // Teach recognition: the faces taken for family on this photo aren't family.
                        FamilyIndex.markPhotoNotFamily(context, photoId)
                    }
                    NEVER -> db.block(photoId, NEVER)
                }
            }
            Rotator.next(context)
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
