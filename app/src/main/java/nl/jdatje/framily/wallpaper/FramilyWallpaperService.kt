package nl.jdatje.framily.wallpaper

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.jdatje.framily.data.Prefs
import nl.jdatje.framily.rotation.Rotator
import nl.jdatje.framily.weather.WeatherApps
import nl.jdatje.framily.weather.WeatherRepo
import kotlin.math.abs

/**
 * The home screen wallpaper. Draws three layers:
 * photo  ->  clock  ->  people cut out of the photo (so they stand in front of the clock).
 */
class FramilyWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = FramilyEngine()

    private inner class FramilyEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val ctx: Context = this@FramilyWallpaperService
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val prefs = Prefs.get(ctx)
        private val clock = ClockPainter(ctx)
        private val weatherPainter = WeatherPainter(ctx)
        private val handler = Handler(Looper.getMainLooper())

        private var width = 0
        private var height = 0
        private var current: Frame? = null
        private var previous: Frame? = null
        private var fadeStart = 0L
        private var loadJob: Job? = null
        private var timeReceiverRegistered = false
        private var segmenter: DepthSegmenter? = null

        /** Where the elements were drawn last, to know what a tap hits. */
        private var placed: WallpaperRenderer.Placed? = null
        private var lastTapTime = 0L
        private var lastTapX = 0
        private var lastTapY = 0

        private val fadeRunnable = Runnable { drawFrame() }
        private val timeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = drawFrame()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs.sp.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
            unregisterTime()
            handler.removeCallbacks(fadeRunnable)
            scope.cancel()
            segmenter?.close()
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            val sizeChanged = w != width || h != height
            width = w
            height = h
            if (sizeChanged) load(force = true) else drawFrame()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            drawFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                registerTime()
                if (current?.photoId != prefs.currentPhotoId) load() else drawFrame()
            } else {
                unregisterTime()
            }
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            when (key) {
                Prefs.CURRENT -> load()
                Prefs.DEPTH -> load(force = true)
                in Prefs.CLOCK_KEYS -> drawFrame()
            }
        }

        override fun onComputeColors(): WallpaperColors? = current?.colors

        override fun onCommand(
            action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean,
        ): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) handleTap(x, y)
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        /** Tap on the weather = open the weather app. Double-tap elsewhere = next photo. */
        private fun handleTap(x: Int, y: Int) {
            val p = placed
            val pad = width * 0.03f
            fun hit(r: android.graphics.RectF?) =
                r != null && x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad
            if (prefs.weatherEnabled && p != null && (hit(p.current) || hit(p.forecast))) {
                lastTapTime = 0
                WeatherApps.open(ctx, prefs.weatherApp)
                return
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastTapTime < 400 && abs(x - lastTapX) < 150 && abs(y - lastTapY) < 150) {
                lastTapTime = 0
                if (prefs.doubleTapEnabled) {
                    val app = ctx.applicationContext
                    CoroutineScope(Dispatchers.IO).launch { Rotator.next(app) }
                }
            } else {
                lastTapTime = now
                lastTapX = x
                lastTapY = y
            }
        }

        private fun registerTime() {
            if (timeReceiverRegistered) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            ctx.registerReceiver(timeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            timeReceiverRegistered = true
        }

        private fun unregisterTime() {
            if (!timeReceiverRegistered) return
            try {
                ctx.unregisterReceiver(timeReceiver)
            } catch (_: Exception) {
            }
            timeReceiverRegistered = false
        }

        private fun load(force: Boolean = false) {
            if (width == 0 || height == 0) return
            val id = prefs.currentPhotoId
            if (!force && current?.photoId == id) return
            loadJob?.cancel()
            val w = width
            val h = height
            val depth = prefs.depthEnabled
            loadJob = scope.launch {
                val frame = withContext(Dispatchers.Default) {
                    WallpaperRenderer.buildFrame(ctx, id, w, h, depth) {
                        segmenter ?: DepthSegmenter().also { segmenter = it }
                    }
                }
                previous = current
                current = frame
                fadeStart = if (previous?.bg != null) SystemClock.uptimeMillis() else 0L
                drawFrame()
                try {
                    notifyColorsChanged()
                } catch (_: Exception) {
                }
            }
        }

        private fun drawFrame() {
            handler.removeCallbacks(fadeRunnable)
            if (width == 0 || height == 0) return
            val holder = surfaceHolder
            val canvas = try {
                holder.lockHardwareCanvas()
            } catch (e: Exception) {
                null
            } ?: return
            val overlay = WallpaperRenderer.Overlay(
                ClockStyle.from(prefs), clock, WeatherStyle.from(prefs), WeatherRepo.current(ctx), weatherPainter,
                LookStyle.from(prefs), prefs.captionText,
            )
            var stillFading = false
            try {
                val cur = current
                val prev = previous
                val t = if (fadeStart > 0) ((SystemClock.uptimeMillis() - fadeStart) / 600f).coerceIn(0f, 1f) else 1f
                if (prev != null && t < 1f) {
                    WallpaperRenderer.draw(canvas, prev, width, height, overlay)
                    val save = canvas.saveLayerAlpha(null, (t * 255).toInt())
                    placed = WallpaperRenderer.draw(canvas, cur, width, height, overlay)
                    canvas.restoreToCount(save)
                    stillFading = true
                } else {
                    placed = WallpaperRenderer.draw(canvas, cur, width, height, overlay)
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            if (stillFading) {
                handler.postDelayed(fadeRunnable, 16)
            } else {
                fadeStart = 0L
                previous = null
            }
        }
    }
}
