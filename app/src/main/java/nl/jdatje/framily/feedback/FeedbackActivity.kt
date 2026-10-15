package nl.jdatje.framily.feedback

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.jdatje.framily.data.Photo
import nl.jdatje.framily.data.PhotoDb
import nl.jdatje.framily.data.Prefs
import nl.jdatje.framily.ui.FramilyTheme
import nl.jdatje.framily.ui.MainActivity

/** Small pop-up opened by double-tapping the home screen. */
class FeedbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FramilyTheme {
                var photo by remember { mutableStateOf<Photo?>(null) }
                LaunchedEffect(Unit) {
                    photo = withContext(Dispatchers.IO) {
                        PhotoDb.get(this@FeedbackActivity).get(Prefs.get(this@FeedbackActivity).currentPhotoId)
                    }
                }
                FeedbackSheet(
                    photo = photo,
                    onAction = { action -> act(action, photo?.id ?: -1L) },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    private fun act(action: String, photoId: Long) {
        val app = applicationContext
        // Not tied to this pop-up, which closes right away.
        CoroutineScope(Dispatchers.IO).launch { Feedback.handle(app, action, photoId) }
        finish()
    }
}

@Composable
private fun FeedbackSheet(
    photo: Photo?,
    onAction: (String) -> Unit,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Card(
            Modifier
                .padding(24.dp)
                .fillMaxWidth()
                // Swallow taps on the card itself, so only taps outside close the pop-up.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This wallpaper", style = MaterialTheme.typography.titleLarge)
                if (photo != null) {
                    AsyncImage(
                        model = photo.uri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
                Button(onClick = { onAction(Feedback.SKIP) }, Modifier.fillMaxWidth()) { Text("Show the next one") }
                FilledTonalButton(onClick = { onAction(Feedback.NOT_FAMILY) }, Modifier.fillMaxWidth()) {
                    Text("Not my family")
                }
                OutlinedButton(onClick = { onAction(Feedback.NEVER) }, Modifier.fillMaxWidth()) {
                    Text("Never show this one")
                }
                TextButton(onClick = onOpenApp, Modifier.fillMaxWidth()) { Text("Open Framily") }
            }
        }
    }
}
