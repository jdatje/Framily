package nl.jdatje.framily.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import nl.jdatje.framily.family.FamilyIndex
import kotlin.math.max
import kotlin.math.roundToInt

/** Cuts a face (box in fractions of the photo) out of the loaded photo, with a little margin. */
private class FaceCrop(private val box: RectF) : Transformation {
    override val cacheKey: String = "face:${box.left},${box.top},${box.right},${box.bottom}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        val side = max(box.width() * w, box.height() * h) * 1.4f
        val cx = box.centerX() * w
        val cy = box.centerY() * h
        val l = (cx - side / 2).roundToInt().coerceIn(0, w - 1)
        val t = (cy - side / 2).roundToInt().coerceIn(0, h - 1)
        val s = side.roundToInt().coerceAtMost(minOf(w - l, h - t)).coerceAtLeast(1)
        return Bitmap.createBitmap(input, l, t, s, s)
    }
}

@Composable
fun FaceThumb(face: FamilyIndex.FaceRef, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(face.uri)
            .size(800)
            .transformations(FaceCrop(face.box))
            .memoryCacheKey("face-${face.faceId}")
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(CircleShape),
    )
}
