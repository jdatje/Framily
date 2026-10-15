package nl.jdatje.framily.wallpaper

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.jdatje.framily.data.PhotoDb
import nl.jdatje.framily.data.Prefs
import nl.jdatje.framily.feedback.FeedbackActivity
import nl.jdatje.framily.util.ImageUtils
import kotlin.math.abs

/**
 * The home screen wallpaper. Draws three layers:
 * photo  ->  clock  ->  people cut out of the photo (so they stand in front of the clock).
 */
class FramilyWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = FramilyEngine()

    private class Frame(
        val photoId: Long,
        val bg: Bitmap?,
        val fg: Foreground?,
        val clockColor: Int,
        val colors: WallpaperColors?,
    )

    private inner class FramilyEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val ctx: Context = this@FramilyWallpaperService
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val prefs = Prefs.get(ctx)
        private val clock = ClockPainter(ctx)
        private val handler = Handler(Looper.getMainLooper())
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        private var width = 0
        private var height = 0
        private var current: Frame? = null
        private var previous: Frame? = null
        private var fadeStart = 0L
        private var loadJob: Job? = null
        private var timeReceiverRegistered = false
        private var segmenter: DepthSegmenter? = null

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
                Prefs.CLOCK, Prefs.CLOCK_POS -> drawFrame()
            }
        }

        override fun onComputeColors(): WallpaperColors? = current?.colors

        override fun onCommand(
            action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean,
        ): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) handleTap(x, y)
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        private fun handleTap(x: Int, y: Int) {
            val now = SystemClock.uptimeMillis()
            if (now - lastTapTime < 400 && abs(x - lastTapX) < 150 && abs(y - lastTapY) < 150) {
                lastTapTime = 0
                if (prefs.doubleTapEnabled) openFeedback()
            } else {
                lastTapTime = now
                lastTapX = x
                lastTapY = y
            }
        }

        private fun openFeedback() {
            try {
                startActivity(Intent(ctx, FeedbackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w(TAG, "Could not open feedback: ${e.message}")
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
            loadJob = scope.launch {
                val frame = withContext(Dispatchers.Default) { buildFrame(id, w, h) }
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

        private suspend fun buildFrame(id: Long, w: Int, h: Int): Frame {
            val empty = Frame(id, null, null, Color.WHITE, null)
            if (id < 0) return empty
            val photo = PhotoDb.get(ctx).get(id) ?: return empty
            val bg = ImageUtils.renderPhoto(ctx, photo, w, h) ?: return empty
            val fg = if (prefs.depthEnabled) {
                try {
                    (segmenter ?: DepthSegmenter().also { segmenter = it }).foreground(bg)
                } catch (e: Exception) {
                    Log.w(TAG, "Segmentation failed: ${e.message}")
                    null
                }
            } else null
            val colors = try {
                WallpaperColors.fromBitmap(bg)
            } catch (_: Exception) {
                null
            }
            return Frame(id, bg, fg, ClockPainter.colorFor(bg), colors)
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
            var stillFading = false
            try {
                val cur = current
                val prev = previous
                val t = if (fadeStart > 0) ((SystemClock.uptimeMillis() - fadeStart) / 600f).coerceIn(0f, 1f) else 1f
                if (prev != null && t < 1f) {
                    drawComposite(canvas, prev)
                    val save = canvas.saveLayerAlpha(null, (t * 255).toInt())
                    drawComposite(canvas, cur)
                    canvas.restoreToCount(save)
                    stillFading = true
                } else {
                    drawComposite(canvas, cur)
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

        private fun drawComposite(canvas: Canvas, frame: Frame?) {
            val full = Rect(0, 0, width, height)
            val bg = frame?.bg
            if (bg == null) {
                canvas.drawColor(0xFF1B1B1F.toInt())
                if (prefs.clockEnabled) clock.draw(canvas, clock.layout(width, height, prefs.clockPosition), Color.WHITE)
                return
            }
            canvas.drawBitmap(bg, null, full, bitmapPaint)
            if (!prefs.clockEnabled) return
            val layout = clock.layout(width, height, prefs.clockPosition)
            clock.draw(canvas, layout, frame.clockColor)
            val fg = frame.fg
            // If people would hide most of the time, keep the clock on top instead.
            if (fg != null && prefs.depthEnabled && fg.coverage(layout.rect, width, height) < 0.45f) {
                canvas.drawBitmap(fg.bitmap, null, full, bitmapPaint)
            }
        }
    }

    companion object {
        private const val TAG = "FramilyWallpaper"
    }
}
