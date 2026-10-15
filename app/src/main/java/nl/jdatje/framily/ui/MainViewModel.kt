package nl.jdatje.framily.ui

import android.app.Application
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.jdatje.framily.Permissions
import nl.jdatje.framily.Scheduler
import nl.jdatje.framily.data.Bucket
import nl.jdatje.framily.data.MediaRepo
import nl.jdatje.framily.data.Photo
import nl.jdatje.framily.data.PhotoDb
import nl.jdatje.framily.data.Prefs
import nl.jdatje.framily.family.FamilyIndex
import nl.jdatje.framily.feedback.Feedback
import nl.jdatje.framily.rotation.LockScreen
import nl.jdatje.framily.rotation.PoolBuilder
import nl.jdatje.framily.rotation.Rotator
import nl.jdatje.framily.scan.ScanWorker
import nl.jdatje.framily.util.ImageUtils
import nl.jdatje.framily.wallpaper.ClockPainter
import nl.jdatje.framily.wallpaper.ClockStyle
import nl.jdatje.framily.wallpaper.DepthSegmenter
import nl.jdatje.framily.wallpaper.Frame
import nl.jdatje.framily.wallpaper.WallpaperRenderer
import kotlin.math.roundToInt
import android.text.format.DateFormat
import java.util.Date
import nl.jdatje.framily.wallpaper.WeatherPainter
import nl.jdatje.framily.wallpaper.WeatherStyle
import nl.jdatje.framily.weather.City
import nl.jdatje.framily.weather.WeatherApi
import nl.jdatje.framily.weather.WeatherCodes
import nl.jdatje.framily.weather.WeatherRepo

data class UiState(
    val hasMedia: Boolean = false,
    val partialMedia: Boolean = false,
    val hasNotifications: Boolean = false,
    val wallpaperActive: Boolean = false,
    val buckets: List<Bucket> = emptyList(),
    val selectedBuckets: Set<String> = emptySet(),
    val scanRunning: Boolean = false,
    /** A scan is queued but waiting (for a charger, or to retry later). */
    val scanWaiting: Boolean = false,
    /** Paused because the phone is warm. */
    val scanPaused: Boolean = false,
    val scanSpeed: Int = 0,
    val scanOnlyCharging: Boolean = true,
    val scanDone: Int = 0,
    val scanTotal: Int = 0,
    val scanned: Int = 0,
    val qualifying: Int = 0,
    val unique: Int = 0,
    val pool: List<PoolBuilder.Entry> = emptyList(),
    val current: Photo? = null,
    val blocked: Int = 0,
    val interval: Int = 120,
    val poolSize: Int = 40,
    val minFace: Int = 10,
    val dedupe: Int = PoolBuilder.STRICT,
    val lock: Boolean = false,
    val doubleTap: Boolean = true,
    val clock: ClockStyle? = null,
    val preview: ImageBitmap? = null,
    val weather: WeatherStyle? = null,
    val weatherSource: String = "location",
    val weatherPlace: String? = null,
    val weatherSummary: String? = null,
    val hasLocation: Boolean = false,
    val cityResults: List<City> = emptyList(),
    val citySearching: Boolean = false,
    val weatherError: String? = null,
    val familyCount: Int = 0,
    val familyOnly: Boolean = false,
)

@OptIn(FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    private val prefs = Prefs.get(app)
    private val db = PhotoDb.get(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var lastRefreshAt = 0

    // Clock preview: a small copy of the wallpaper, drawn by the same code as the real one.
    private val previewMutex = Mutex()
    private val previewW = 480
    private val previewH: Int = ImageUtils.screenSize(app).let { (w, h) -> (previewW * h.toFloat() / w).roundToInt() }
    private val clockPainter = ClockPainter(app)
    private val weatherPainter = WeatherPainter(app)
    private var previewFrame: Frame? = null
    private var previewFrameDepth = false
    private var segmenter: DepthSegmenter? = null

    init {
        viewModelScope.launch {
            prefs.changes().debounce(150).collect { refreshData() }
        }
        viewModelScope.launch {
            WorkManager.getInstance(app)
                .getWorkInfosFlow(WorkQuery.fromUniqueWorkNames(Scheduler.SCAN_NOW, Scheduler.SCAN_PERIODIC))
                .collect { infos ->
                    val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    val waiting = running == null && infos.any {
                        it.state == WorkInfo.State.ENQUEUED && Scheduler.SCAN_NOW in it.tags
                    }
                    val wasRunning = _state.value.scanRunning
                    val done = running?.progress?.getInt(ScanWorker.DONE, 0) ?: 0
                    val total = running?.progress?.getInt(ScanWorker.TOTAL, 0) ?: 0
                    val paused = running?.progress?.getBoolean(ScanWorker.PAUSED, false) ?: false
                    _state.update {
                        it.copy(
                            scanRunning = running != null, scanWaiting = waiting, scanPaused = paused,
                            scanDone = done, scanTotal = total,
                        )
                    }
                    // Refresh counts now and then while scanning, and when it finishes.
                    if ((wasRunning && running == null) || done - lastRefreshAt >= 100) {
                        lastRefreshAt = done
                        refreshData()
                    }
                    if (running == null) lastRefreshAt = 0
                }
        }
    }

    override fun onCleared() {
        segmenter?.close()
        super.onCleared()
    }

    /** Called whenever the app comes to the front. */
    fun onResume() {
        val hasMedia = Permissions.hasMedia(ctx)
        _state.update {
            it.copy(
                hasMedia = hasMedia,
                partialMedia = Permissions.partialMedia(ctx),
                hasNotifications = Permissions.hasNotifications(ctx),
                wallpaperActive = runCatching {
                    WallpaperManager.getInstance(ctx).wallpaperInfo?.packageName == ctx.packageName
                }.getOrDefault(false),
            )
        }
        if (hasMedia) refreshBuckets()
        refreshData()
        if (prefs.weatherEnabled) updateWeather(force = false)
    }

    private fun refreshBuckets() = viewModelScope.launch(Dispatchers.IO) {
        val buckets = MediaRepo.buckets(MediaRepo.allItems(ctx))
        if (!prefs.bucketsInitialized && buckets.isNotEmpty()) {
            prefs.selectedBuckets = MediaRepo.defaultBuckets(buckets)
            prefs.bucketsInitialized = true
            Scheduler.scanNow(ctx) // first run: start right away
        } else if (db.missingHash(prefs.selectedBuckets).isNotEmpty() ||
            db.missingEmbeddings(prefs.selectedBuckets).isNotEmpty()
        ) {
            Scheduler.scanNow(ctx) // after an update: give photos scanned by an old version the new data
        }
        _state.update { it.copy(buckets = buckets) }
    }

    fun refreshData() = viewModelScope.launch(Dispatchers.IO) {
        val result = PoolBuilder.build(ctx)
        val current = prefs.currentPhotoId.takeIf { it >= 0 }?.let { db.get(it) }
        _state.update {
            it.copy(
                selectedBuckets = prefs.selectedBuckets,
                scanned = db.scannedCount(),
                qualifying = result.qualifying,
                unique = result.unique,
                pool = result.entries,
                current = current,
                blocked = db.blockedCount(),
                interval = prefs.intervalMinutes,
                poolSize = prefs.poolSize,
                minFace = prefs.minFacePercent,
                dedupe = prefs.dedupeLevel,
                scanSpeed = prefs.scanSpeed,
                scanOnlyCharging = prefs.scanOnlyCharging,
                lock = prefs.lockScreenEnabled,
                doubleTap = prefs.doubleTapEnabled,
                clock = ClockStyle.from(prefs),
                weather = WeatherStyle.from(prefs),
                weatherSource = prefs.weatherSource,
                weatherPlace = prefs.weatherPlace?.third,
                weatherSummary = WeatherRepo.current(ctx)?.let { w ->
                    val now = w.now()
                    val time = DateFormat.getTimeFormat(ctx).format(Date(w.fetchedAt))
                    "${WeatherCodes.emoji(now.code, now.isDay)} ${now.temp.roundToInt()}° · updated $time"
                },
                hasLocation = WeatherRepo.hasLocationPermission(ctx),
                familyCount = FamilyIndex.familyCount(ctx),
                familyOnly = prefs.familyOnly,
            )
        }
        refreshPreview()
    }

    private suspend fun refreshPreview() = previewMutex.withLock {
        val style = ClockStyle.from(prefs)
        val id = prefs.currentPhotoId
        val frame = previewFrame
        if (frame == null || frame.photoId != id || previewFrameDepth != style.depth) {
            previewFrame = WallpaperRenderer.buildFrame(ctx, id, previewW, previewH, style.depth) {
                segmenter ?: DepthSegmenter().also { segmenter = it }
            }
            previewFrameDepth = style.depth
        }
        val bmp = Bitmap.createBitmap(previewW, previewH, Bitmap.Config.ARGB_8888)
        val overlay = WallpaperRenderer.Overlay(
            style, clockPainter, WeatherStyle.from(prefs), WeatherRepo.current(ctx), weatherPainter,
        )
        WallpaperRenderer.draw(Canvas(bmp), previewFrame, previewW, previewH, overlay)
        _state.update { it.copy(preview = bmp.asImageBitmap()) }
    }

    fun setInterval(minutes: Int) {
        prefs.intervalMinutes = minutes
        Scheduler.scheduleRotation(ctx)
    }

    fun setPoolSize(n: Int) { prefs.poolSize = n }
    fun setMinFace(p: Int) { prefs.minFacePercent = p }
    fun setDedupe(level: Int) { prefs.dedupeLevel = level }
    fun setDoubleTap(on: Boolean) { prefs.doubleTapEnabled = on }

    fun setScanSpeed(speed: Int) {
        prefs.scanSpeed = speed
        restartScanIfPending()
    }

    fun setScanOnlyCharging(on: Boolean) {
        prefs.scanOnlyCharging = on
        restartScanIfPending()
    }

    /** Apply changed scan settings right away (the scan continues where it was). */
    private fun restartScanIfPending() = viewModelScope.launch(Dispatchers.IO) {
        if (Scheduler.scanPending(ctx)) Scheduler.scanNow(ctx, restart = true)
    }

    fun setClock(on: Boolean) { prefs.clockEnabled = on }
    fun setClockPos(p: Float) { prefs.clockPosition = p }
    fun setDepth(on: Boolean) { prefs.depthEnabled = on }
    fun setClockFont(id: String) { prefs.clockFont = id }
    fun setClockLayout(layout: String) { prefs.clockLayout = layout }
    fun setClockSize(size: Float) { prefs.clockSize = size }
    fun setClockColor(color: String) { prefs.clockColor = color }
    fun setClockOpacity(o: Float) { prefs.clockOpacity = o }
    fun setClockDate(on: Boolean) { prefs.clockDate = on }

    fun setWeather(on: Boolean) {
        prefs.weatherEnabled = on
        WeatherRepo.schedule(ctx)
        if (on) updateWeather(force = true)
    }

    fun setWeatherForecast(on: Boolean) { prefs.weatherForecast = on }
    fun setWeatherPlacement(p: String) { prefs.weatherPlacement = p }
    fun setWeatherPosition(p: Float) { prefs.weatherPosition = p }

    fun setWeatherSource(source: String) {
        prefs.weatherSource = source
        if (source == "location") {
            prefs.weatherPlace = null
            updateWeather(force = true)
        }
        _state.update { it.copy(cityResults = emptyList(), weatherError = null) }
    }

    /** Get the phone's location (if that's the source) and fresh weather. */
    fun updateWeather(force: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val moved = WeatherRepo.updateFromPhoneLocation(ctx)
        if (prefs.weatherSource == "location" && prefs.weatherPlace == null) {
            _state.update {
                it.copy(
                    weatherError = if (WeatherRepo.hasLocationPermission(ctx)) {
                        "Couldn't get your location. Is location turned on?"
                    } else null
                )
            }
            return@launch
        }
        val stale = WeatherRepo.current(ctx).let { it == null || System.currentTimeMillis() - it.fetchedAt > 45 * 60_000L }
        if (force || moved || stale) {
            val ok = WeatherRepo.refresh(ctx)
            _state.update { it.copy(weatherError = if (ok) null else "Couldn't load the weather. Check your internet.") }
        }
        refreshData()
    }

    fun searchCity(query: String) {
        if (query.isBlank()) return
        _state.update { it.copy(citySearching = true, weatherError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val results = runCatching { WeatherApi.searchCity(query) }
            _state.update {
                it.copy(
                    citySearching = false,
                    cityResults = results.getOrDefault(emptyList()),
                    weatherError = when {
                        results.isFailure -> "Search failed. Check your internet."
                        results.getOrNull().isNullOrEmpty() -> "No place found with that name."
                        else -> null
                    },
                )
            }
        }
    }

    fun pickCity(city: City) {
        prefs.weatherPlace = Triple(city.lat, city.lon, city.name)
        _state.update { it.copy(cityResults = emptyList()) }
        updateWeather(force = true)
    }

    fun setLock(on: Boolean) {
        prefs.lockScreenEnabled = on
        viewModelScope.launch(Dispatchers.IO) {
            if (on) _state.value.current?.let { LockScreen.apply(ctx, it) } else LockScreen.clear(ctx)
        }
    }

    fun toggleBucket(id: String) {
        val set = prefs.selectedBuckets.toMutableSet()
        val added = set.add(id)
        if (!added) set.remove(id)
        prefs.selectedBuckets = set
        if (added) Scheduler.scanNow(ctx)
    }

    fun scanNow() = Scheduler.scanNow(ctx)
    fun stopScan() = Scheduler.stopScan(ctx)

    fun next() = viewModelScope.launch { Rotator.next(ctx) }

    fun showNow(photo: Photo) = viewModelScope.launch { Rotator.show(ctx, photo) }

    /** "Not family" / "Never show": removes the photo, and moves on if it was on screen. */
    fun feedback(photo: Photo, action: String) = viewModelScope.launch(Dispatchers.IO) {
        if (photo.id == prefs.currentPhotoId) {
            Feedback.handle(ctx, action, photo.id)
        } else {
            db.block(photo.id, action)
        }
        refreshData()
    }

    fun restoreRemoved() = viewModelScope.launch(Dispatchers.IO) {
        db.unblockAll()
        refreshData()
    }

    fun rescanAll() = viewModelScope.launch(Dispatchers.IO) {
        db.invalidateAll()
        Scheduler.scanNow(ctx)
        refreshData()
    }
}
