package nl.jdatje.framily.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** A folder on the phone, as Android's media index sees it (Camera, WhatsApp Images, ...). */
data class Bucket(val id: String, val name: String, val count: Int)

data class MediaItem(
    val id: Long,
    val bucketId: String,
    val bucketName: String,
    val dateModified: Long,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    /** True/false when the size is known, null when the photo has to be opened to find out. */
    val portraitByMetadata: Boolean?
        get() {
            if (width <= 0 || height <= 0) return null
            val rotated = orientation == 90 || orientation == 270
            val w = if (rotated) height else width
            val h = if (rotated) width else height
            return h > w * 1.05f
        }
}

object MediaRepo {
    private val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.BUCKET_ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.ORIENTATION,
    )

    /** Every image on the phone (metadata only, fast). */
    fun allItems(context: Context): List<MediaItem> {
        val result = ArrayList<MediaItem>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, null
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bId = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bName = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val mod = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val taken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val w = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val h = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val o = c.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
            while (c.moveToNext()) {
                result += MediaItem(
                    id = c.getLong(id),
                    bucketId = c.getString(bId) ?: "unknown",
                    bucketName = c.getString(bName) ?: "Unknown",
                    dateModified = c.getLong(mod),
                    dateTaken = c.getLong(taken),
                    width = c.getInt(w),
                    height = c.getInt(h),
                    orientation = c.getInt(o),
                )
            }
        }
        return result
    }

    fun buckets(items: List<MediaItem>): List<Bucket> =
        items.groupBy { it.bucketId }
            .map { (id, list) -> Bucket(id, list.first().bucketName, list.size) }
            .sortedByDescending { it.count }

    /** First-run choice: the camera folder and WhatsApp images. */
    fun defaultBuckets(buckets: List<Bucket>): Set<String> {
        val picked = buckets.filter {
            it.name.equals("Camera", ignoreCase = true) ||
                it.name.contains("WhatsApp Images", ignoreCase = true)
        }.map { it.id }.toSet()
        return picked.ifEmpty { buckets.take(1).map { it.id }.toSet() }
    }
}
