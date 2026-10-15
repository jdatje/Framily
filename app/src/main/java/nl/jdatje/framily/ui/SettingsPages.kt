package nl.jdatje.framily.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import nl.jdatje.framily.rotation.PoolBuilder
import nl.jdatje.framily.wallpaper.ClockFonts
import nl.jdatje.framily.weather.WeatherApps
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.Typeface as ComposeTypeface

val intervals = listOf(
    30 to "30 min", 60 to "1 h", 120 to "2 h", 180 to "3 h", 360 to "6 h", 720 to "12 h", 1440 to "1 day"
)

private val dedupeOptions = listOf(
    PoolBuilder.OFF to "Keep all",
    PoolBuilder.STRICT to "Very similar",
    PoolBuilder.LOOSE to "Also somewhat similar",
)

private val clockColors = listOf(
    "auto" to "Auto contrast",
    "photo" to "Light from photo",
    "photo_dark" to "Dark from photo",
    "white" to "White",
    "black" to "Black",
)

// ------------------------------------------------------------------ Photos & rotation

fun LazyListScope.photosPage(s: UiState, vm: MainViewModel) {
    item {
        SectionCard("Rotation") {
            Label("Change every")
            Choices(intervals, s.interval) { vm.setInterval(it) }
            Label("Photos in rotation: ${s.poolSize}")
            LiveSlider(s.poolSize.toFloat(), 10f..200f, steps = 37) { vm.setPoolSize(it.roundToInt()) }
            Label("Minimum face size: ${s.minFace}% of the photo height")
            LiveSlider(s.minFace.toFloat(), 4f..30f, steps = 25) { vm.setMinFace(it.roundToInt()) }
            Label("Group similar photos (keeps only the best one)")
            Choices(dedupeOptions, s.dedupe) { vm.setDedupe(it) }
        }
    }
    item {
        SectionCard("Lock screen") {
            SwitchRow("Also on lock screen", "A photo without the Framily clock (the phone shows its own)", s.lock) { vm.setLock(it) }
            if (s.lock) {
                SwitchRow("Different photo than home screen", "Two photos at once: one on the lock screen, one at home", s.lockDifferent) {
                    vm.setLockDifferent(it)
                }
            }
        }
    }
    item {
        SectionCard("Folders") {
            if (!s.hasMedia) Hint("Allow access to photos first.")
            else if (s.buckets.isEmpty()) Hint("Loading folders…")
            s.buckets.forEach { b ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.toggleBucket(b.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = b.id in s.selectedBuckets, onCheckedChange = { vm.toggleBucket(b.id) })
                    Text(b.name, Modifier.weight(1f))
                    Hint("${b.count}")
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Clock

@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.clockPage(s: UiState, vm: MainViewModel) {
    val c = s.clock ?: return
    item {
        SectionCard(null) {
            SwitchRow("Show clock", null, c.enabled) { vm.setClock(it) }
        }
    }
    if (!c.enabled) return
    item {
        SectionCard("Style") {
            val context = LocalContext.current
            Label("Font")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClockFonts.options.forEach { opt ->
                    val family = remember(opt.id) { FontFamily(ComposeTypeface(ClockFonts.typeface(context, opt.id))) }
                    FilterChip(selected = c.font == opt.id, onClick = { vm.setClockFont(opt.id) }, label = { Text(opt.label, fontFamily = family) })
                }
            }
            Label("Layout")
            Choices(listOf("stacked" to "Stacked 07 / 50", "line" to "One line 07:50"), if (c.stacked) "stacked" else "line") {
                vm.setClockLayout(it)
            }
            Label("Color")
            Choices(clockColors, c.color) { vm.setClockColor(it) }
            if (c.color == "auto") Hint("Light or dark per spot, depending on the photo behind it. Also used for weather and caption.")
            Label("Size")
            LiveSlider(c.size, 0.5f..1.4f) { vm.setClockSize(it) }
            Label("Opacity: ${(c.opacity * 100).roundToInt()}%")
            LiveSlider(c.opacity, 0.3f..1f) { vm.setClockOpacity(it) }
            SwitchRow("Show date", null, c.showDate) { vm.setClockDate(it) }
        }
    }
    item {
        SectionCard("Position") {
            Choices(listOf(true to "Behind heads (auto)", false to "Fixed height"), c.auto) { vm.setClockAuto(it) }
            if (c.auto) {
                Hint("Placed just behind the main person's head, like on an iPhone. Without a clear person it uses the fixed height.")
            } else {
                LiveSlider(c.position, 0.04f..0.8f) { vm.setClockPos(it) }
            }
            SwitchRow("Depth effect", "People stand in front of the clock and weather", c.depth) { vm.setDepth(it) }
        }
    }
}

// ------------------------------------------------------------------ Weather

fun LazyListScope.weatherPage(s: UiState, vm: MainViewModel, actions: MainActions) {
    val w = s.weather ?: return
    item {
        SectionCard(null) {
            SwitchRow("Show weather", null, w.enabled) { vm.setWeather(it) }
            if (w.enabled) {
                s.weatherSummary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                s.weatherError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (!w.enabled) return
    item {
        SectionCard("Location") {
            Choices(listOf("location" to "My location", "city" to "A city"), s.weatherSource) { vm.setWeatherSource(it) }
            if (s.weatherSource == "location") {
                if (!s.hasLocation) {
                    Hint("Only your approximate location. It updates when you open Framily, so it follows you when you travel.")
                    Button(onClick = actions.requestLocation) { Text("Allow location") }
                } else {
                    Text("📍 " + (s.weatherPlace?.ifBlank { "Your location" } ?: "Finding your location…"))
                }
            } else {
                s.weatherPlace?.let { Text("📍 $it") }
                var query by rememberSaveable { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("City") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.searchCity(query) }),
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = { vm.searchCity(query) }, enabled = !s.citySearching) {
                        Text(if (s.citySearching) "…" else "Search")
                    }
                }
                s.cityResults.forEach { city ->
                    Text(city.label, modifier = Modifier.fillMaxWidth().clickable { vm.pickCity(city) }.padding(vertical = 10.dp))
                }
            }
        }
    }
    item {
        SectionCard("What to show") {
            SwitchRow("Next 4 hours", "Temperature and chance of rain per hour", w.forecast) { vm.setWeatherForecast(it) }
            SwitchRow("Rain alert", "A line like \"🌧 Rain from 17:00\" when the weather changes soon", s.look?.rainAlert == true) {
                vm.setRainAlert(it)
            }
            Label("Current weather size")
            LiveSlider(w.size, 0.5f..1.6f) { vm.setWeatherSize(it) }
            if (w.forecast) {
                Label("Forecast size")
                LiveSlider(w.forecastSize, 0.5f..1.4f) { vm.setForecastSize(it) }
            }
        }
    }
    item {
        SectionCard("Position") {
            Choices(listOf("sides" to "Beside the clock", "under" to "Under the clock", "custom" to "My own spot"), w.placement) {
                vm.setWeatherPlacement(it)
            }
            Hint(
                when (w.placement) {
                    "sides" -> "Current weather left of the clock, the next hours on the right."
                    "under" -> "Right below the clock."
                    else -> "Drag them anywhere with \"Arrange on screen\"."
                }
            )
            if (w.placement == "custom" && w.forecast) {
                Choices(listOf(false to "Hours side by side", true to "Hours below each other"), w.forecastVertical) {
                    vm.setForecastVertical(it)
                }
            }
        }
    }
    item { WeatherAppCard(s, vm) }
}

@Composable
private fun WeatherAppCard(s: UiState, vm: MainViewModel) {
    var apps by remember { mutableStateOf<List<WeatherApps.App>?>(null) }
    SectionCard("Tap to open") {
        Hint("Tap the weather on your home screen to open:")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.weatherAppLabel, Modifier.weight(1f))
            OutlinedButton(onClick = { vm.weatherApps { apps = it } }) { Text("Change") }
        }
        Hint("Only works on an empty part of the home screen: an app icon on top of the weather gets the tap instead.")
    }
    apps?.let { list ->
        AlertDialog(
            onDismissRequest = { apps = null },
            title = { Text("Weather app") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        Text(
                            "Pixel Weather (default)",
                            modifier = Modifier.fillMaxWidth().clickable { vm.setWeatherApp(null); apps = null }.padding(vertical = 12.dp),
                        )
                        HorizontalDivider()
                    }
                    items(list) { app ->
                        Text(
                            app.label,
                            modifier = Modifier.fillMaxWidth().clickable { vm.setWeatherApp(app.packageName); apps = null }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { apps = null }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ Readability

fun LazyListScope.lookPage(s: UiState, vm: MainViewModel) {
    val l = s.look ?: return
    item {
        SectionCard("Background") {
            Hint("Behind the weather and the caption, so they stay readable on any photo.")
            Choices(listOf("none" to "None", "pill" to "Soft shape", "frosted" to "Frosted glass"), l.bgStyle) { vm.setBgStyle(it) }
            if (l.bgStyle != "none") {
                Label("Opacity: ${(l.bgOpacity * 100).roundToInt()}%")
                LiveSlider(l.bgOpacity, 0.1f..0.9f) { vm.setBgOpacity(it) }
                Label("Roundness")
                LiveSlider(l.bgRound, 0f..1f) { vm.setBgRound(it) }
                Label("Color")
                Choices(listOf("auto" to "Automatic", "dark" to "Dark", "light" to "Light"), l.bgColor) { vm.setBgColor(it) }
                SwitchRow("Also behind the clock", null, l.bgClock) { vm.setBgClock(it) }
            }
        }
    }
    item {
        SectionCard("Text") {
            SwitchRow("Outline", "A thin contrasting edge around all letters", l.outline) { vm.setOutline(it) }
            val auto = s.clock?.color == "auto"
            SwitchRow("Auto contrast", "Light or dark text depending on the photo behind it", auto) {
                vm.setClockColor(if (it) "auto" else "photo")
            }
        }
    }
    item {
        SectionCard("Night") {
            SwitchRow(
                "Dim after sunset",
                "Darker photo at night, easier on the eyes. Uses sunset at your weather location, otherwise 21:00–07:00.",
                l.nightDim,
            ) { vm.setNightDim(it) }
            if (l.nightDim) {
                Label("Strength: ${(l.nightDimStrength * 100).roundToInt()}%")
                LiveSlider(l.nightDimStrength, 0.1f..0.7f) { vm.setNightDimStrength(it) }
            }
        }
    }
}

// ------------------------------------------------------------------ Extras

fun LazyListScope.extrasPage(s: UiState, vm: MainViewModel, actions: MainActions) {
    item {
        SectionCard("Caption") {
            SwitchRow("Show caption", "When and where, e.g. \"June 2021 · Texel\"", s.look?.caption == true) {
                vm.setCaption(it)
                if (it && !s.hasMediaLocation) actions.requestMediaLocation()
            }
            if (s.look?.caption == true && !s.hasMediaLocation) {
                Hint("To show the place, Framily needs to read where photos were taken.")
                OutlinedButton(onClick = actions.requestMediaLocation) { Text("Allow photo locations") }
            }
            if (s.look?.caption == true) Hint("Drag it with \"Arrange on screen\". WhatsApp photos usually have no place.")
        }
    }
    item {
        SectionCard("Rotation") {
            SwitchRow("On this day", "Photos from this date in earlier years go first (\"7 years ago today\")", s.onThisDay) {
                vm.setOnThisDay(it)
            }
            SwitchRow("Double-tap home screen", "Shows the next photo", s.doubleTap) { vm.setDoubleTap(it) }
        }
    }
}

// ------------------------------------------------------------------ Scanning

fun LazyListScope.scanPage(s: UiState, vm: MainViewModel) {
    item {
        SectionCard("Status") {
            Text(
                when {
                    s.scanRunning && s.scanPaused -> "Paused: your phone is warm. It continues when it has cooled down."
                    s.scanRunning && s.scanTotal > 0 -> "Scanning photos: ${s.scanDone} of ${s.scanTotal}"
                    s.scanRunning -> "Preparing scan…"
                    s.scanWaiting -> if (s.scanOnlyCharging) "Waiting: plug in your phone to continue." else "Paused to save battery. It continues later."
                    else -> "Not scanning. ${s.scanned} photos scanned so far."
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.scanRunning || s.scanWaiting) {
                    OutlinedButton(onClick = { vm.stopScan() }) { Text("Stop scan") }
                } else {
                    OutlinedButton(onClick = { vm.scanNow() }, enabled = s.hasMedia) { Text("Scan now") }
                }
            }
        }
    }
    item {
        SectionCard("Battery") {
            SwitchRow("Only while charging", "Pauses when you unplug, continues when you plug in again", s.scanOnlyCharging) {
                vm.setScanOnlyCharging(it)
            }
            Label("Speed")
            Choices(listOf(0 to "Gentle", 1 to "Normal", 2 to "Fast"), s.scanSpeed) { vm.setScanSpeed(it) }
            Hint(
                when (s.scanSpeed) {
                    0 -> "Takes longer, barely uses battery."
                    1 -> "A balance between speed and battery use."
                    else -> "As fast as possible. Use a fast charger."
                } + " Always: slower while you use the phone, pauses when it gets warm."
            )
        }
    }
    item { MaintenanceCard(s, vm) }
}

@Composable
private fun MaintenanceCard(s: UiState, vm: MainViewModel) {
    var confirmRescan by remember { mutableStateOf(false) }
    SectionCard("More") {
        if (s.blocked > 0) OutlinedButton(onClick = { vm.restoreRemoved() }) { Text("Restore ${s.blocked} removed photo(s)") }
        OutlinedButton(onClick = { confirmRescan = true }, enabled = s.hasMedia && !s.scanRunning) { Text("Rescan all photos") }
        Hint("\"Not family\" answers teach face recognition, so similar-looking people are skipped too.")
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
