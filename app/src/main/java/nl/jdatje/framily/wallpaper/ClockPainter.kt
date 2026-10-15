package nl.jdatje.framily.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.format.DateFormat
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import java.util.Calendar

/** Big stacked clock: hours on top, minutes below (like your example). */
class ClockPainter(private val context: Context) {

    class Layout(val rect: RectF, val centerX: Float, val line1: Float, val line2: Float, val textSize: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 800, false)
        textAlign = Paint.Align.CENTER
    }
    private val bounds = Rect()

    fun layout(w: Int, h: Int, position: Float): Layout {
        val size = w * 0.40f
        paint.textSize = size
        paint.getTextBounds("08", 0, 2, bounds)
        val digitH = bounds.height().toFloat()
        val textW = paint.measureText("00")
        val top = h * position
        val line1 = top + digitH
        val line2 = line1 + digitH * 1.15f
        val cx = w / 2f
        return Layout(RectF(cx - textW / 2f, top, cx + textW / 2f, line2), cx, line1, line2, size)
    }

    fun draw(canvas: Canvas, layout: Layout, color: Int) {
        val now = Calendar.getInstance()
        val hour = if (DateFormat.is24HourFormat(context)) {
            now.get(Calendar.HOUR_OF_DAY)
        } else {
            now.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        }
        val minute = now.get(Calendar.MINUTE)
        paint.textSize = layout.textSize
        paint.color = color
        paint.setShadowLayer(layout.textSize * 0.04f, 0f, layout.textSize * 0.01f, 0x55000000)
        canvas.drawText(String.format("%02d", hour), layout.centerX, layout.line1, paint)
        canvas.drawText(String.format("%02d", minute), layout.centerX, layout.line2, paint)
    }

    companion object {
        /** A light, slightly see-through color taken from the photo itself. */
        fun colorFor(photo: Bitmap): Int {
            val palette = Palette.from(photo).maximumColorCount(16).generate()
            val base = palette.lightVibrantSwatch?.rgb
                ?: palette.lightMutedSwatch?.rgb
                ?: palette.vibrantSwatch?.rgb
                ?: palette.dominantSwatch?.rgb
                ?: Color.WHITE
            val light = ColorUtils.blendARGB(base, Color.WHITE, 0.3f)
            return ColorUtils.setAlphaComponent(light, 235)
        }
    }
}
