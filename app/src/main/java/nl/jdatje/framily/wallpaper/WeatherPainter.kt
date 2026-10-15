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
import nl.jdatje.framily.weather.WeatherHour
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt

data class WeatherStyle(
    val enabled: Boolean,
    val forecast: Boolean,
    /** "under", "sides" or "custom". */
    val placement: String,
    val size: Float,
    val forecastSize: Float,
    val forecastVertical: Boolean,
    val x: Float,
    val y: Float,
    val forecastX: Float,
    val forecastY: Float,
) {
    companion object {
        fun from(p: Prefs) = WeatherStyle(
            enabled = p.weatherEnabled,
            forecast = p.weatherForecast,
            placement = p.weatherPlacement,
            size = p.weatherSize,
            forecastSize = p.forecastSize,
            forecastVertical = p.forecastVertical,
            x = p.weatherX,
            y = p.weatherY,
            forecastX = p.forecastX,
            forecastY = p.forecastY,
        )
    }
}

/**
 * Draws the two weather parts, each of which can be horizontal or vertical:
 *
 *   current, horizontal:  ⛅ 18°        current, vertical:   ⛅
 *                                                            18°
 *   forecast, horizontal:  16:00  17:00  18:00  19:00
 *                           ⛅     ☁️     🌧️     🌧️          forecast, vertical:  16:00
 *                           18°    17°    16°    15°                              ⛅ 18°
 *                                         60%    80%                              17:00 ...
 *
 * Chance of rain is only shown when it's 20% or more.
 */
class WeatherPainter(private val context: Context) {

    /** A measured part; [rect] says where it is. Move it with [at]. */
    class Part(
        val rect: RectF,
        val vertical: Boolean,
        val unit: Float,
        val hours: List<WeatherHour>,
        val showRain: Boolean,
        /** Extra small line under the current weather (rain alert). */
        val extra: String? = null,
    ) {
        /** The same part with its center at (cx, cy). */
        fun at(cx: Float, cy: Float) = Part(
            RectF(cx - rect.width() / 2, cy - rect.height() / 2, cx + rect.width() / 2, cy + rect.height() / 2),
            vertical, unit, hours, showRain, extra,
        )

        /** The same part with its top edge centered at (cx, top). */
        fun below(cx: Float, top: Float) = at(cx, top + rect.height() / 2)

        /** Shifted so it stays on screen. */
        fun keptInside(w: Int, h: Int): Part {
            val dx = when {
                rect.left < 0 -> -rect.left
                rect.right > w -> w - rect.right
                else -> 0f
            }
            val dy = when {
                rect.top < 0 -> -rect.top
                rect.bottom > h -> h - rect.bottom
                else -> 0f
            }
            return at(rect.centerX() + dx, rect.centerY() + dy)
        }
    }

    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 600, false)
    }

    private fun currentText(data: WeatherData): Pair<String, String> {
        val now = data.now()
        return WeatherCodes.emoji(now.code, now.isDay) to "${now.temp.roundToInt()}°"
    }

    private fun timeLabel(t: Long): String =
        DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h a", Date(t)).toString()

    // ------------------------------------------------------------ current weather

    fun measureCurrent(
        w: Int, style: WeatherStyle, clock: ClockStyle, data: WeatherData, vertical: Boolean, alert: String? = null,
    ): Part {
        val unit = w * 0.075f * style.size
        val (icon, temp) = currentText(data)
        text.typeface = ClockFonts.typeface(context, clock.font)
        text.textSize = unit
        val iconW = text.measureText(icon)
        val tempW = text.measureText(temp)
        var (pw, ph) = if (vertical) max(iconW, tempW) to unit * 2.5f else (iconW + unit * 0.3f + tempW) to unit * 1.3f
        if (alert != null) {
            small.textSize = unit * 0.42f
            pw = max(pw, small.measureText(alert))
            ph += unit * 0.42f * 1.5f
        }
        return Part(RectF(0f, 0f, pw, ph), vertical, unit, emptyList(), false, alert)
    }

    /** "🌧 Rain from 17:00" style line, or null. */
    fun rainAlert(data: WeatherData): String? = data.rainAlert({ timeLabel(it) })

    fun drawCurrent(canvas: Canvas, part: Part, data: WeatherData, clock: ClockStyle, colors: PhotoColors, outline: Boolean = false) {
        val (icon, temp) = currentText(data)
        val u = part.unit
        text.typeface = ClockFonts.typeface(context, clock.font)
        text.textSize = u
        text.color = ClockPainter.textColor(clock, colors)
        text.setShadowLayer(u * 0.08f, 0f, u * 0.02f, ClockPainter.shadowColor(clock, colors))
        val r = part.rect
        if (part.vertical) {
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(icon, r.centerX(), r.top + u, text)
            Ink.text(canvas, temp, r.centerX(), r.top + u * 2.2f, text, outline)
        } else {
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(icon, r.left, r.top + u, text)
            Ink.text(canvas, temp, r.left + text.measureText(icon) + u * 0.3f, r.top + u, text, outline)
        }
        part.extra?.let { line ->
            small.textSize = u * 0.42f
            small.textAlign = Paint.Align.CENTER
            small.color = text.color
            small.setShadowLayer(u * 0.1f, 0f, u * 0.02f, ClockPainter.shadowColor(clock, colors))
            Ink.text(canvas, line, r.centerX(), r.bottom - u * 0.12f, small, outline)
        }
    }

    // ------------------------------------------------------------ forecast

    /** Null when there's nothing to show (forecast off, or no hours available). */
    fun measureForecast(w: Int, style: WeatherStyle, data: WeatherData, vertical: Boolean): Part? {
        if (!style.forecast) return null
        val hours = data.nextHours(4)
        if (hours.isEmpty()) return null
        val u = w * 0.04f * style.forecastSize // base text size
        val showRain = hours.any { it.rain >= 20 }
        val (pw, ph) = if (vertical) {
            val unitH = u * 0.85f * 1.2f + u * 1.35f * 1.25f + (if (showRain) u * 0.85f * 1.2f else 0f) + u * 0.6f
            (u * 4.6f) to (unitH * hours.size)
        } else {
            (u * 4.6f * hours.size) to (u * 0.85f + u * 1.35f * 1.35f + u * 1.4f + (if (showRain) u * 0.85f * 1.5f else 0f) + u * 0.3f)
        }
        return Part(RectF(0f, 0f, pw, ph), vertical, u, hours, showRain)
    }

    fun drawForecast(canvas: Canvas, part: Part, clock: ClockStyle, colors: PhotoColors, outline: Boolean = false) {
        val u = part.unit
        val color = ClockPainter.textColor(clock, colors)
        val shadow = ClockPainter.shadowColor(clock, colors)
        val timeSize = u * 0.85f
        val iconSize = u * 1.35f
        small.color = color
        small.setShadowLayer(u * 0.2f, 0f, u * 0.04f, shadow)
        small.textAlign = Paint.Align.CENTER
        text.typeface = small.typeface
        text.color = color
        text.setShadowLayer(u * 0.2f, 0f, u * 0.04f, shadow)
        text.textAlign = Paint.Align.CENTER
        val r = part.rect

        if (part.vertical) {
            val unitH = r.height() / part.hours.size
            part.hours.forEachIndexed { i, h ->
                var y = r.top + unitH * i + timeSize
                small.textSize = timeSize
                Ink.text(canvas, timeLabel(h.time), r.centerX(), y, small, outline)
                y += iconSize * 1.25f
                // icon and temperature side by side
                text.textSize = iconSize
                val icon = WeatherCodes.emoji(h.code, h.isDay)
                val iconW = text.measureText(icon)
                small.textSize = u * 1.1f
                val temp = "${h.temp.roundToInt()}°"
                val tempW = small.measureText(temp)
                val gap = u * 0.25f
                val start = r.centerX() - (iconW + gap + tempW) / 2
                text.textAlign = Paint.Align.LEFT
                small.textAlign = Paint.Align.LEFT
                canvas.drawText(icon, start, y, text)
                Ink.text(canvas, temp, start + iconW + gap, y, small, outline)
                text.textAlign = Paint.Align.CENTER
                small.textAlign = Paint.Align.CENTER
                if (part.showRain && h.rain >= 20) {
                    small.textSize = timeSize
                    Ink.text(canvas, "${h.rain}%", r.centerX(), y + timeSize * 1.2f, small, outline)
                }
            }
        } else {
            val colW = r.width() / part.hours.size
            part.hours.forEachIndexed { i, h ->
                val x = r.left + colW * (i + 0.5f)
                var y = r.top + timeSize
                small.textSize = timeSize
                Ink.text(canvas, timeLabel(h.time), x, y, small, outline)
                y += iconSize * 1.35f
                text.textSize = iconSize
                canvas.drawText(WeatherCodes.emoji(h.code, h.isDay), x, y, text)
                y += u * 1.4f
                small.textSize = u * 1.05f
                Ink.text(canvas, "${h.temp.roundToInt()}°", x, y, small, outline)
                if (part.showRain && h.rain >= 20) {
                    small.textSize = timeSize
                    Ink.text(canvas, "${h.rain}%", x, y + timeSize * 1.5f, small, outline)
                }
            }
        }
    }
}
