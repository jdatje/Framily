package nl.jdatje.framily.ui

import android.app.Application
import android.app.WallpaperManager
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
import nl.jdatje.framily.Permissions
import nl.jdatje.framily.Scheduler
import nl.jdatje.framily.data.Bucket
import nl.jdatje.framily.data.MediaRepo
import nl.jdatje.framily.data.Photo
import nl.jdatje.framily.data.PhotoDb
import nl.jdatje.framily.data.Prefs
import nl.jdatje.framily.rotation.LockScreen
import nl.jdatje.framily.rotation.Rotator
import nl.jdatje.framily.scan.ScanWorker

data class UiState(
    val hasMedia: Boolean = false,
    val partialMedia: Boolean = false,
    val hasNotifications: Boolean = false,
    val wallpaperActive: Boolean = false,
    val buckets: List<Bucket> = emptyList(),
    val selectedBuckets: Set<String> = emptySet(),
    val scanRunning: Boolean = false,
    val scanDone: Int = 0,
    val scanTotal: Int = 0,
    val scanned: Int = 0,
    val qualifying: Int = 0,
    val pool: List<Photo> = emptyList(),
    val current: Photo? = null,
    val blocked: Int = 0,
    val interval: Int = 120,
    val poolSize: Int = 40,
    val minFace: Int = 10,
    val clock: Boolean = true,
    val clockPos: Float = 0.18f,
    val depth: Boolean = true,
    val lock: Boolean = false,
    val doubleTap: Boolean = true,
)

@OptIn(FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    private val prefs = Prefs.get(app)
    private val db = PhotoDb.get(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var lastRefreshAt = 0

    init {
        viewModelScope.launch {
            prefs.changes().debounce(200).collect { refreshData() }
        }
        viewModelScope.launch {
            WorkManager.getInstance(app)
                .getWorkInfosFlow(WorkQuery.fromUniqueWorkNames(Scheduler.SCAN_NOW, Scheduler.SCAN_PERIODIC))
                .collect { infos ->
                    val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    val wasRunning = _state.value.scanRunning
                    val done = running?.progress?.getInt(ScanWorker.DONE, 0) ?: 0
                    val total = running?.progress?.getInt(ScanWorker.TOTAL, 0) ?: 0
                    _state.update { it.copy(scanRunning = running != null, scanDone = done, scanTotal = total) }
                    // Refresh counts now and then while scanning, and when it finishes.
                    if ((wasRunning && running == null) || done - lastRefreshAt >= 100) {
                        lastRefreshAt = done
                        refreshData()
                    }
                    if (running == null) lastRefreshAt = 0
                }
        }
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
    }

    private fun refreshBuckets() = viewModelScope.launch(Dispatchers.IO) {
        val buckets = MediaRepo.buckets(MediaRepo.allItems(ctx))
        if (!prefs.bucketsInitialized && buckets.isNotEmpty()) {
            prefs.selectedBuckets = MediaRepo.defaultBuckets(buckets)
            prefs.bucketsInitialized = true
            Scheduler.scanNow(ctx) // first run: start right away
        }
        _state.update { it.copy(buckets = buckets) }
    }

    fun refreshData() = viewModelScope.launch(Dispatchers.IO) {
        val buckets = prefs.selectedBuckets
        val minRatio = prefs.minFacePercent / 100f
        val pool = db.pool(buckets, minRatio, prefs.poolSize)
        _state.update {
            it.copy(
                selectedBuckets = buckets,
                scanned = db.scannedCount(),
                qualifying = db.qualifyingCount(buckets, minRatio),
                pool = pool,
                current = prefs.currentPhotoId.takeIf { id -> id >= 0 }?.let { id -> db.get(id) },
                blocked = db.blockedCount(),
                interval = prefs.intervalMinutes,
                poolSize = prefs.poolSize,
                minFace = prefs.minFacePercent,
                clock = prefs.clockEnabled,
                clockPos = prefs.clockPosition,
                depth = prefs.depthEnabled,
                lock = prefs.lockScreenEnabled,
                doubleTap = prefs.doubleTapEnabled,
            )
        }
    }

    fun setInterval(minutes: Int) {
        prefs.intervalMinutes = minutes
        Scheduler.scheduleRotation(ctx)
    }

    fun setPoolSize(n: Int) { prefs.poolSize = n }
    fun setMinFace(p: Int) { prefs.minFacePercent = p }
    fun setClock(on: Boolean) { prefs.clockEnabled = on }
    fun setClockPos(p: Float) { prefs.clockPosition = p }
    fun setDepth(on: Boolean) { prefs.depthEnabled = on }
    fun setDoubleTap(on: Boolean) { prefs.doubleTapEnabled = on }

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

    fun remove(photo: Photo) = viewModelScope.launch(Dispatchers.IO) {
        db.block(photo.id, "never")
        if (photo.id == prefs.currentPhotoId) Rotator.next(ctx)
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
