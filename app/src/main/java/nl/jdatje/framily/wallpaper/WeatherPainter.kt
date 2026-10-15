package nl.jdatje.framily.wallpaper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.format.DateFormat
import nl.jdatje.framily.data.Prefs
import nl.jdatje.framily.weather.WeatherCodes
import nl.jdatje.framily.weather.WeatherData
import java.util.Date
import kotlin.math.roundToInt

data class WeatherStyle(
    val enabled: Boolean,
    val forecast: Boolean,
    /** "clock" or "separate". */
    val placement: String,
    val position: Float,
) {
    companion object {
        fun from(p: Prefs) = WeatherStyle(p.weatherEnabled, p.weatherForecast, p.weatherPlacement, p.weatherPosition)
    }
}

/**
 * Current weather ("⛅ 18°") and an optional strip with the next 4 hours:
 *
 *     16:00  17:00  18:00  19:00
 *      ⛅     ☁️     🌧️     🌧️
 *      18°    17°    16°    15°
 *                    60%    80%     <- chance of rain, only shown when it's 20% or more
 */
class WeatherPainter(private val context: Context) {

    class Layout(
        val rect: RectF,
        val centerX: Float,
        val currentLine: Float,
        val currentSize: Float,
        val stripTop: Float,
        val colW: Float,
        val smallSize: Float,
        val iconSize: Float,
        val tempSize: Float,
        val showRain: Boolean,
        val hours: Int,
    )

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 600, false)
    }

    fun layout(w: Int, top: Float, style: WeatherStyle, data: WeatherData): Layout {
        val currentSize = w * 0.075f
        val currentLine = top + currentSize
        var bottom = currentLine + currentSize * 0.3f

        val hours = if (style.forecast) data.nextHours(4) else emptyList()
        val smallSize = w * 0.034f
        val iconSize = w * 0.055f
        val tempSize = w * 0.042f
        val showRain = hours.any { it.rain >= 20 }
        val stripTop = bottom + currentSize * 0.4f
        if (hours.isNotEmpty()) {
            bottom = stripTop + smallSize + iconSize * 1.35f + tempSize * 1.4f + (if (showRain) smallSize * 1.5f else 0f) + smallSize * 0.3f
        }
        val colW = w * 0.19f
        val halfW = maxOf(w * 0.18f, colW * hours.size / 2f)
        val cx = w / 2f
        return Layout(
            RectF(cx - halfW, top, cx + halfW, bottom), cx, currentLine, currentSize,
            stripTop, colW, smallSize, iconSize, tempSize, showRain, hours.size,
        )
    }

    fun draw(canvas: Canvas, layout: Layout, data: WeatherData, clock: ClockStyle, colors: PhotoColors) {
        val color = ClockPainter.textColor(clock, colors)
        val shadow = ClockPainter.shadowColor(clock, colors)
        val alpha = (clock.opacity * 255).roundToInt().coerceIn(0, 255)

        // Current: icon + temperature, centered together.
        val now = data.now()
        val icon = WeatherCodes.emoji(now.code, now.isDay)
        val temp = "${now.temp.roundToInt()}°"
        text.typeface = ClockFonts.typeface(context, clock.font)
        text.textSize = layout.currentSize
        text.setShadowLayer(layout.currentSize * 0.08f, 0f, layout.currentSize * 0.02f, shadow)
        val iconW = text.measureText(icon)
        val tempW = text.measureText(temp)
        val gap = layout.currentSize * 0.3f
        val start = layout.centerX - (iconW + gap + tempW) / 2f
        text.color = color
        text.alpha = alpha
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(icon, start, layout.currentLine, text)
        text.color = color
        canvas.drawText(temp, start + iconW + gap, layout.currentLine, text)
        text.textAlign = Paint.Align.CENTER

        if (layout.hours == 0) return
        val hours = data.nextHours(layout.hours)
        val is24 = DateFormat.is24HourFormat(context)
        val left = layout.centerX - layout.colW * hours.size / 2f
        small.color = color
        small.setShadowLayer(layout.smallSize * 0.25f, 0f, layout.smallSize * 0.05f, shadow)
        hours.forEachIndexed { i, h ->
            val x = left + layout.colW * (i + 0.5f)
            var y = layout.stripTop + layout.smallSize

            small.textSize = layout.smallSize
            canvas.drawText(DateFormat.format(if (is24) "HH:mm" else "h a", Date(h.time)).toString(), x, y, small)

            y += layout.iconSize * 1.35f
            text.textSize = layout.iconSize
            text.color = color
            text.alpha = alpha
            canvas.drawText(WeatherCodes.emoji(h.code, h.isDay), x, y, text)

            y += layout.tempSize * 1.4f
            small.textSize = layout.tempSize
            canvas.drawText("${h.temp.roundToInt()}°", x, y, small)

            if (layout.showRain && h.rain >= 20) {
                small.textSize = layout.smallSize
                canvas.drawText("${h.rain}%", x, y + layout.smallSize * 1.5f, small)
            }
        }
    }
}
