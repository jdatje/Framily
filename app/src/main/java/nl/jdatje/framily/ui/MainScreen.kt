package nl.jdatje.framily.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import nl.jdatje.framily.data.Photo
import nl.jdatje.framily.feedback.Feedback
import nl.jdatje.framily.rotation.PoolBuilder
import nl.jdatje.framily.wallpaper.ClockFonts
import nl.jdatje.framily.wallpaper.ClockStyle
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.Typeface as ComposeTypeface

private val intervals = listOf(
    30 to "30 min", 60 to "1 h", 120 to "2 h", 180 to "3 h", 360 to "6 h", 720 to "12 h", 1440 to "1 day"
)

private val dedupeOptions = listOf(
    PoolBuilder.OFF to "Keep all",
    PoolBuilder.STRICT to "Very similar",
    PoolBuilder.LOOSE to "Also somewhat similar",
)

private val clockColors = listOf(
    "photo" to "Light from photo",
    "photo_dark" to "Dark from photo",
    "white" to "White",
    "black" to "Black",
)

@Composable
fun MainScreen(
    vm: MainViewModel,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onSetWallpaper: () -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<PoolBuilder.Entry?>(null) }
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
                    "Best first. \"+2\" means 2 similar photos were left out for this one. Tap a photo for options.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(s.pool.chunked(3)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { entry ->
                        PoolTile(entry, isCurrent = entry.photo.id == s.current?.id, Modifier.weight(1f)) {
                            selected = entry
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            item { MaintenanceCard(s, vm) { confirmRescan = true } }
        }
    }

    selected?.let { entry ->
        PhotoDialog(
            entry = entry,
            onDismiss = { selected = null },
            onShow = { vm.showNow(entry.photo); selected = null },
            onNotFamily = { vm.feedback(entry.photo, Feedback.NOT_FAMILY); selected = null },
            onRemove = { vm.feedback(entry.photo, Feedback.NEVER); selected = null },
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
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
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
            "${s.qualifying} photos meet the criteria",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        val left = s.qualifying - s.unique
        Text(
            (if (left > 0) "$left similar photos grouped · " else "") + "using the best ${minOf(s.unique, s.poolSize)}",
            style = MaterialTheme.typography.bodyMedium,
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Now on your home screen", style = MaterialTheme.typography.labelLarge)
                    Text("Score ${cur.score.roundToInt()} · ${cur.bucketName}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.feedback(cur, Feedback.NOT_FAMILY) }) { Text("Not family") }
                        TextButton(onClick = { vm.feedback(cur, Feedback.NEVER) }) { Text("Never show") }
                    }
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
        Label("Change every")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            intervals.forEach { (minutes, label) ->
                FilterChip(selected = s.interval == minutes, onClick = { vm.setInterval(minutes) }, label = { Text(label) })
            }
        }

        var pool by remember(s.poolSize) { mutableFloatStateOf(s.poolSize.toFloat()) }
        Label("Photos in rotation: ${pool.roundToInt()}")
        Slider(
            value = pool,
            onValueChange = { pool = it },
            onValueChangeFinished = { vm.setPoolSize(pool.roundToInt()) },
            valueRange = 10f..200f,
            steps = 37,
        )

        var face by remember(s.minFace) { mutableFloatStateOf(s.minFace.toFloat()) }
        Label("Minimum face size: ${face.roundToInt()}% of the photo height")
        Slider(
            value = face,
            onValueChange = { face = it },
            onValueChangeFinished = { vm.setMinFace(face.roundToInt()) },
            valueRange = 4f..30f,
            steps = 25,
        )

        Label("Group similar photos (keeps only the best one)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dedupeOptions.forEach { (level, label) ->
                FilterChip(selected = s.dedupe == level, onClick = { vm.setDedupe(level) }, label = { Text(label) })
            }
        }

        SwitchRow("Also on lock screen", "Same photo, without the Framily clock", s.lock) { vm.setLock(it) }
        SwitchRow("Double-tap home screen", "Shows the next photo", s.doubleTap) { vm.setDoubleTap(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClockCard(s: UiState, vm: MainViewModel) {
    val c: ClockStyle = s.clock ?: return
    val context = LocalContext.current
    SectionCard("Clock") {
        s.preview?.let { preview ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = preview,
                    contentDescription = "Preview",
                    modifier = Modifier
                        .width(170.dp)
                        .aspectRatio(preview.width.toFloat() / preview.height)
                        .clip(RoundedCornerShape(16.dp))
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(16.dp)),
                )
            }
        }
        SwitchRow("Show clock", null, c.enabled) { vm.setClock(it) }
        if (!c.enabled) return@SectionCard

        Label("Font")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClockFonts.options.forEach { opt ->
                val family = remember(opt.id) { FontFamily(ComposeTypeface(ClockFonts.typeface(context, opt.id))) }
                FilterChip(
                    selected = c.font == opt.id,
                    onClick = { vm.setClockFont(opt.id) },
                    label = { Text(opt.label, fontFamily = family) },
                )
            }
        }

        Label("Layout")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = c.stacked, onClick = { vm.setClockLayout("stacked") }, label = { Text("Stacked 07 / 50") })
            FilterChip(selected = !c.stacked, onClick = { vm.setClockLayout("line") }, label = { Text("One line 07:50") })
        }

        Label("Color")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            clockColors.forEach { (id, label) ->
                FilterChip(selected = c.color == id, onClick = { vm.setClockColor(id) }, label = { Text(label) })
            }
        }

        var size by remember(c.size) { mutableFloatStateOf(c.size) }
        Label("Size")
        Slider(value = size, onValueChange = { size = it }, onValueChangeFinished = { vm.setClockSize(size) }, valueRange = 0.5f..1.4f)

        var opacity by remember(c.opacity) { mutableFloatStateOf(c.opacity) }
        Label("Opacity: ${(opacity * 100).roundToInt()}%")
        Slider(value = opacity, onValueChange = { opacity = it }, onValueChangeFinished = { vm.setClockOpacity(opacity) }, valueRange = 0.3f..1f)

        var pos by remember(c.position) { mutableFloatStateOf(c.position) }
        Label("Height on screen")
        Slider(value = pos, onValueChange = { pos = it }, onValueChangeFinished = { vm.setClockPos(pos) }, valueRange = 0.06f..0.6f)

        SwitchRow("Show date", null, c.showDate) { vm.setClockDate(it) }
        SwitchRow("Depth effect", "People stand in front of the clock", c.depth) { vm.setDepth(it) }
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
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Folders", style = MaterialTheme.typography.titleMedium)
                    val names = s.buckets.filter { it.id in s.selectedBuckets }.joinToString(", ") { it.name }
                    Text(
                        if (!s.hasMedia) "Allow access to photos first" else names.ifEmpty { "None selected" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (expanded) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(visible = expanded && s.hasMedia) {
                Column {
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
        }
    }
}

@Composable
private fun PoolTile(entry: PoolBuilder.Entry, isCurrent: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val photo = entry.photo
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
        Badge("${photo.score.roundToInt()}", Modifier.align(Alignment.BottomStart), RoundedCornerShape(topEnd = 8.dp))
        if (entry.similar > 0) {
            Badge("+${entry.similar}", Modifier.align(Alignment.TopEnd), RoundedCornerShape(bottomStart = 8.dp))
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier, shape: RoundedCornerShape) {
    Surface(color = Color.Black.copy(alpha = 0.55f), shape = shape, modifier = modifier) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PhotoDialog(
    entry: PoolBuilder.Entry,
    onDismiss: () -> Unit,
    onShow: () -> Unit,
    onNotFamily: () -> Unit,
    onRemove: () -> Unit,
) {
    val photo = entry.photo
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
                        photo.bucketName +
                        (if (entry.similar > 0) " · best of ${entry.similar + 1} similar photos" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onShow) { Text("Show now") } },
        dismissButton = {
            Row {
                TextButton(onClick = onNotFamily) { Text("Not family") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        },
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
            "Face recognition (only your family) comes in a next version. \"Not family\" choices are saved so it can learn from them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
