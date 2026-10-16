package nl.jdatje.framily.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import nl.jdatje.framily.feedback.Feedback
import nl.jdatje.framily.rotation.PoolBuilder
import nl.jdatje.framily.wallpaper.ClockFonts
import kotlin.math.roundToInt

/** Callbacks into the Activity (permissions, other screens). */
class MainActions(
    val requestPermissions: () -> Unit,
    val openAppSettings: () -> Unit,
    val setWallpaper: () -> Unit,
    val requestLocation: () -> Unit,
    val requestMediaLocation: () -> Unit,
    val openFamily: () -> Unit,
    val arrange: () -> Unit,
    val exportSettings: () -> Unit,
    val importSettings: () -> Unit,
)

@Composable
fun MainScreen(vm: MainViewModel, actions: MainActions) {
    val s by vm.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("home") }
    if (page != "home") BackHandler { page = "home" }

    when (page) {
        "photos" -> SettingsPage("Photos & rotation", s, null, { page = "home" }) { photosPage(s, vm) }
        "clock" -> SettingsPage("Clock", s, actions.arrange, { page = "home" }) { clockPage(s, vm) }
        "weather" -> SettingsPage("Weather", s, actions.arrange, { page = "home" }) { weatherPage(s, vm, actions) }
        "look" -> SettingsPage("Readability", s, actions.arrange, { page = "home" }) { lookPage(s, vm) }
        "extras" -> {
            val backupMessage by vm.backupMessage.collectAsStateWithLifecycle()
            SettingsPage("Extras", s, actions.arrange, { page = "home" }) { extrasPage(s, vm, actions, backupMessage) }
        }
        "scan" -> SettingsPage("Scanning", s, null, { page = "home" }) { scanPage(s, vm) }
        "pool" -> PoolPage(s, vm) { page = "home" }
        else -> HomePage(s, vm, actions) { page = it }
    }
}

// ------------------------------------------------------------------ home

@Composable
private fun HomePage(s: UiState, vm: MainViewModel, actions: MainActions, open: (String) -> Unit) {
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
                Hint("Your best family photos as wallpaper")
            }
            s.crashReport?.let { report -> item { CrashCard(report, vm) } }
            if (!s.hasMedia || !s.hasNotifications) item { PermissionCard(s, actions) }
            if (!s.wallpaperActive) item { WallpaperCard(actions.setWallpaper) }
            item { NowCard(s, vm, actions.arrange) }
            item { FamilyCard(s, actions.openFamily) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        PageRow("🖼️", "Photos & rotation", photosSummary(s)) { open("photos") }
                        HorizontalDivider()
                        PageRow("🕐", "Clock", clockSummary(s)) { open("clock") }
                        HorizontalDivider()
                        PageRow("⛅", "Weather", weatherSummary(s)) { open("weather") }
                        HorizontalDivider()
                        PageRow("👓", "Readability", lookSummary(s)) { open("look") }
                        HorizontalDivider()
                        PageRow("✨", "Extras", extrasSummary(s)) { open("extras") }
                        HorizontalDivider()
                        PageRow("✋", "Arrange on screen", "Drag the clock, weather and caption") { actions.arrange() }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        PageRow("📷", "In the rotation (${s.pool.size})", "See, show or remove photos") { open("pool") }
                        HorizontalDivider()
                        PageRow("🔋", "Scanning", scanSummary(s)) { open("scan") }
                    }
                }
            }
        }
    }
}

private fun photosSummary(s: UiState): String {
    val every = intervals.firstOrNull { it.first == s.interval }?.second ?: "${s.interval} min"
    val folders = s.buckets.filter { it.id in s.selectedBuckets }.joinToString(", ") { it.name }.ifEmpty { "no folders" }
    return "Every $every · best ${s.poolSize} · $folders"
}

private fun clockSummary(s: UiState): String {
    val c = s.clock ?: return ""
    if (!c.enabled) return "Off"
    val font = ClockFonts.options.firstOrNull { it.id == c.font }?.label ?: c.font
    return "$font · ${if (c.stacked) "stacked" else "one line"} · ${if (c.auto) "behind heads" else "fixed height"}"
}

private fun weatherSummary(s: UiState): String {
    val w = s.weather ?: return ""
    if (!w.enabled) return "Off"
    val where = when (w.placement) { "sides" -> "beside the clock"; "under" -> "under the clock"; else -> "own spot" }
    return listOfNotNull(s.weatherSummary?.substringBefore(" ·"), where, s.weatherPlace?.takeIf { it.isNotBlank() }).joinToString(" · ")
}

private fun lookSummary(s: UiState): String {
    val l = s.look ?: return ""
    val bg = when (l.bgStyle) { "pill" -> "Soft background"; "frosted" -> "Frosted background"; else -> "No background" }
    return listOfNotNull(
        "Auto opacity".takeIf { l.autoOpacity },
        if (l.bgStyle != "none" && !l.autoOpacity) "$bg ${(l.bgOpacity * 100).roundToInt()}%" else bg,
        "outline".takeIf { l.outline },
        "night dimming".takeIf { l.nightDim },
    ).joinToString(" · ")
}

private fun extrasSummary(s: UiState): String =
    listOfNotNull(
        "Caption".takeIf { s.look?.caption == true },
        "On this day".takeIf { s.onThisDay },
        "Double-tap = next".takeIf { s.doubleTap },
        "Lock screen".takeIf { s.lock },
    ).joinToString(" · ").ifEmpty { "Caption, on this day, double-tap" }

private fun scanSummary(s: UiState): String = when {
    s.scanRunning && s.scanTotal > 0 -> "Scanning: ${s.scanDone} of ${s.scanTotal}"
    s.scanRunning -> "Scanning…"
    s.scanWaiting -> s.scanWaitReason ?: "Waiting to continue"
    else -> "${s.scanned} photos scanned · " + (if (s.scanOnlyCharging) "only while charging" else "also on battery")
}

/** The photo on screen (as the real wallpaper looks), with the most-used actions. */
@Composable
private fun NowCard(s: UiState, vm: MainViewModel, onArrange: () -> Unit) {
    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            s.preview?.let { WallpaperPreview(it, 110.dp, Modifier.clickable(onClick = onArrange)) }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text("${s.qualifying} photos qualify", style = MaterialTheme.typography.titleMedium)
                val grouped = s.qualifying - s.unique
                Hint((if (grouped > 0) "$grouped similar grouped · " else "") + "using the best ${minOf(s.unique, s.poolSize)}")
                s.current?.let { Hint("Now: score ${it.score.roundToInt()} · ${it.bucketName}") }
                if (s.scanRunning) {
                    if (s.scanTotal > 0) {
                        LinearProgressIndicator(progress = { s.scanDone / s.scanTotal.toFloat() }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Hint(if (s.scanPaused) "Scan paused: phone is warm" else "Scanning ${s.scanDone} of ${s.scanTotal}")
                }
                FilledTonalButton(onClick = { vm.next() }, enabled = s.pool.isNotEmpty()) { Text("Next photo") }
                s.current?.let { cur ->
                    Row {
                        TextButton(onClick = { vm.feedback(cur, Feedback.NOT_FAMILY) }) { Text("Not family") }
                        TextButton(onClick = { vm.feedback(cur, Feedback.NEVER) }) { Text("Never show") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FamilyCard(s: UiState, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            PageRow(
                "👨‍👩‍👧",
                "Family",
                when {
                    s.familyCount == 0 -> "Not set up yet: any clear face counts"
                    s.familyOnly -> "${s.familyCount} member(s) · only photos with family"
                    else -> "${s.familyCount} member(s) · \"only family\" is off"
                },
                onOpen,
            )
        }
    }
}

@Composable
private fun CrashCard(report: String, vm: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Framily ran into a problem", style = MaterialTheme.typography.titleMedium)
            Text(report.lineSequence().take(4).joinToString("\n"), style = MaterialTheme.typography.bodySmall, maxLines = 6)
            Text("Copy the details and paste them in the chat, so the cause can be fixed.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { clipboard.setText(AnnotatedString(report)); copied = true }) {
                    Text(if (copied) "Copied" else "Copy details")
                }
                TextButton(onClick = { vm.clearCrash() }) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun PermissionCard(s: UiState, actions: MainActions) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Permissions needed", style = MaterialTheme.typography.titleMedium)
            if (s.partialMedia) {
                Text("You allowed only some photos. Framily needs \"Allow all\" to find your family photos.")
                Button(onClick = actions.openAppSettings) { Text("Open app settings") }
            } else {
                if (!s.hasMedia) Text("• Photos: to look through your pictures (everything stays on your phone).")
                if (!s.hasNotifications) Text("• Notifications: for the Skip / Not family buttons.")
                Button(onClick = actions.requestPermissions) { Text("Allow") }
                TextButton(onClick = actions.openAppSettings) { Text("Already declined? Open app settings") }
            }
        }
    }
}

@Composable
private fun WallpaperCard(onSet: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Set Framily as your wallpaper", style = MaterialTheme.typography.titleMedium)
            Text("When asked, choose \"Home screen\". The lock screen is an option under Extras.")
            Button(onClick = onSet) { Text("Set wallpaper") }
        }
    }
}

// ------------------------------------------------------------------ settings page frame

/**
 * A settings page. Look-related pages ([onArrange] set) keep a live preview pinned at the top,
 * so every change is visible right away.
 */
@Composable
private fun SettingsPage(
    title: String,
    s: UiState,
    onArrange: (() -> Unit)?,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Scaffold { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back") }
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (onArrange != null) {
                s.preview?.let { preview ->
                    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            WallpaperPreview(preview, 92.dp, Modifier.clickable(onClick = onArrange))
                            Spacer(Modifier.width(14.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Hint("Live preview of your wallpaper")
                                OutlinedButton(onClick = onArrange) { Text("Arrange on screen") }
                            }
                        }
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

// ------------------------------------------------------------------ "In the rotation" page

@Composable
private fun PoolPage(s: UiState, vm: MainViewModel, onBack: () -> Unit) {
    var selected by remember { mutableStateOf<PoolBuilder.Entry?>(null) }
    SettingsPage("In the rotation (${s.pool.size})", s, null, onBack) {
        item { Hint("Best first. \"+2\" means 2 similar photos were left out for this one. Tap a photo for options.") }
        items(s.pool.chunked(3)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { entry ->
                    PoolTile(entry, isCurrent = entry.photo.id == s.current?.id, Modifier.weight(1f)) { selected = entry }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
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
        if (entry.similar > 0) Badge("+${entry.similar}", Modifier.align(Alignment.TopEnd), RoundedCornerShape(bottomStart = 8.dp))
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier, shape: RoundedCornerShape) {
    Surface(color = Color.Black.copy(alpha = 0.55f), shape = shape, modifier = modifier) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
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
                        photo.bucketName + (if (entry.similar > 0) " · best of ${entry.similar + 1} similar photos" else ""),
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
