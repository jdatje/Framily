package nl.jdatje.framily.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import nl.jdatje.framily.data.Photo
import kotlin.math.roundToInt

private val intervals = listOf(
    30 to "30 min", 60 to "1 h", 120 to "2 h", 180 to "3 h", 360 to "6 h", 720 to "12 h", 1440 to "1 day"
)

@Composable
fun MainScreen(
    vm: MainViewModel,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onSetWallpaper: () -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Photo?>(null) }
    var confirmRescan by remember { mutableStateOf(false) }

    Scaffold { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Framily", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Your best family photos as wallpaper",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!s.hasMedia || !s.hasNotifications) {
                item { PermissionCard(s, onRequestPermissions, onOpenAppSettings) }
            }
            if (!s.wallpaperActive) {
                item { WallpaperCard(onSetWallpaper) }
            }
            item { StatusCard(s, vm) }
            item { RotationCard(s, vm) }
            item { ClockCard(s, vm) }
            item { FoldersCard(s, vm) }

            item {
                Text(
                    "In the rotation (${s.pool.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Best first. Tap a photo to show it now or remove it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(s.pool.chunked(3)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { photo ->
                        PoolTile(photo, isCurrent = photo.id == s.current?.id, Modifier.weight(1f)) { selected = photo }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            item { MaintenanceCard(s, vm) { confirmRescan = true } }
        }
    }

    selected?.let { photo ->
        PhotoDialog(
            photo = photo,
            onDismiss = { selected = null },
            onShow = { vm.showNow(photo); selected = null },
            onRemove = { vm.remove(photo); selected = null },
        )
    }

    if (confirmRescan) {
        AlertDialog(
            onDismissRequest = { confirmRescan = false },
            title = { Text("Rescan all photos?") },
            text = { Text("This looks at every photo again. It can take a few hours; removed photos stay removed.") },
            confirmButton = { TextButton(onClick = { vm.rescanAll(); confirmRescan = false }) { Text("Rescan") } },
            dismissButton = { TextButton(onClick = { confirmRescan = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun PermissionCard(s: UiState, onRequest: () -> Unit, onSettings: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Permissions needed", style = MaterialTheme.typography.titleMedium)
            if (s.partialMedia) {
                Text("You allowed only some photos. Framily needs \"Allow all\" to find your family photos.")
                Button(onClick = onSettings) { Text("Open app settings") }
            } else {
                if (!s.hasMedia) Text("• Photos: to look through your pictures (everything stays on your phone).")
                if (!s.hasNotifications) Text("• Notifications: for the Skip / Not family buttons.")
                Button(onClick = onRequest) { Text("Allow") }
                TextButton(onClick = onSettings) { Text("Already declined? Open app settings") }
            }
        }
    }
}

@Composable
private fun WallpaperCard(onSet: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Set Framily as your wallpaper", style = MaterialTheme.typography.titleMedium)
            Text("When asked, choose \"Home screen\". The lock screen is a separate option below.")
            Button(onClick = onSet) { Text("Set wallpaper") }
        }
    }
}

@Composable
private fun StatusCard(s: UiState, vm: MainViewModel) {
    SectionCard("Status") {
        if (s.scanRunning) {
            val total = s.scanTotal
            Text(if (total > 0) "Scanning photos: ${s.scanDone} of $total" else "Preparing scan…")
            if (total > 0) {
                LinearProgressIndicator(progress = { s.scanDone / total.toFloat() }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Text(
                "Tip: plug in your phone. You can close the app, the scan continues.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${s.qualifying} photos meet the criteria · using the best ${minOf(s.qualifying, s.poolSize)}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "${s.scanned} photos scanned so far",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        s.current?.let { cur ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = cur.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 54.dp, height = 96.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Now on your home screen", style = MaterialTheme.typography.labelLarge)
                    Text("Score ${cur.score.roundToInt()} · ${cur.bucketName}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { vm.next() }, enabled = s.pool.isNotEmpty()) { Text("Next photo") }
            if (s.scanRunning) {
                OutlinedButton(onClick = { vm.stopScan() }) { Text("Stop scan") }
            } else {
                OutlinedButton(onClick = { vm.scanNow() }, enabled = s.hasMedia) { Text("Scan now") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RotationCard(s: UiState, vm: MainViewModel) {
    SectionCard("Rotation") {
        Text("Change every", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            intervals.forEach { (minutes, label) ->
                FilterChip(selected = s.interval == minutes, onClick = { vm.setInterval(minutes) }, label = { Text(label) })
            }
        }

        var pool by remember(s.poolSize) { mutableFloatStateOf(s.poolSize.toFloat()) }
        Text("Photos in rotation: ${pool.roundToInt()}", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = pool,
            onValueChange = { pool = it },
            onValueChangeFinished = { vm.setPoolSize(pool.roundToInt()) },
            valueRange = 10f..200f,
            steps = 37,
        )

        var face by remember(s.minFace) { mutableFloatStateOf(s.minFace.toFloat()) }
        Text("Minimum face size: ${face.roundToInt()}% of the photo height", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = face,
            onValueChange = { face = it },
            onValueChangeFinished = { vm.setMinFace(face.roundToInt()) },
            valueRange = 4f..30f,
            steps = 25,
        )

        SwitchRow("Also on lock screen", "Same photo, without the Framily clock", s.lock) { vm.setLock(it) }
        SwitchRow("Double-tap home screen", "Opens Skip / Not family / Never show", s.doubleTap) { vm.setDoubleTap(it) }
    }
}

@Composable
private fun ClockCard(s: UiState, vm: MainViewModel) {
    SectionCard("Clock") {
        SwitchRow("Show clock", null, s.clock) { vm.setClock(it) }
        if (s.clock) {
            SwitchRow("Depth effect", "People stand in front of the clock", s.depth) { vm.setDepth(it) }
            var pos by remember(s.clockPos) { mutableFloatStateOf(s.clockPos) }
            Text("Height on screen", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = pos,
                onValueChange = { pos = it },
                onValueChangeFinished = { vm.setClockPos(pos) },
                valueRange = 0.08f..0.55f,
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun FoldersCard(s: UiState, vm: MainViewModel) {
    SectionCard("Folders") {
        if (!s.hasMedia) {
            Text("Allow access to photos first.")
            return@SectionCard
        }
        if (s.buckets.isEmpty()) Text("Loading folders…")
        s.buckets.forEach { b ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.toggleBucket(b.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = b.id in s.selectedBuckets, onCheckedChange = { vm.toggleBucket(b.id) })
                Text(b.name, Modifier.weight(1f))
                Text(
                    "${b.count}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PoolTile(photo: Photo, isCurrent: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (isCurrent) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(10.dp))
                else Modifier
            )
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(photo.uri).size(360).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(topEnd = 8.dp),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Text(
                "${photo.score.roundToInt()}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun PhotoDialog(photo: Photo, onDismiss: () -> Unit, onShow: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Score ${photo.score.roundToInt()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AsyncImage(
                    model = photo.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Text(
                    "${photo.faceCount} face(s) · largest ${(photo.faceRatio * 100).roundToInt()}% of height\n" +
                        "Sharp ${pct(photo.sharp)} · Eyes open ${pct(photo.eyes)} · Smiling ${pct(photo.smile)}\n" +
                        photo.bucketName,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onShow) { Text("Show now") } },
        dismissButton = { TextButton(onClick = onRemove) { Text("Remove") } },
    )
}

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

@Composable
private fun MaintenanceCard(s: UiState, vm: MainViewModel, onRescan: () -> Unit) {
    SectionCard("More") {
        if (s.blocked > 0) {
            OutlinedButton(onClick = { vm.restoreRemoved() }) { Text("Restore ${s.blocked} removed photo(s)") }
        }
        OutlinedButton(onClick = onRescan, enabled = s.hasMedia && !s.scanRunning) { Text("Rescan all photos") }
        Text(
            "Face recognition (only your family) comes in the next version. For now, any clear face counts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
