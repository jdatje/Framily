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
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import nl.jdatje.framily.R
import nl.jdatje.framily.data.Prefs
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Everything about how the clock looks. */
data class ClockStyle(
    val enabled: Boolean,
    val font: String,
    val layout: String,
    val size: Float,
    val color: String,
    val opacity: Float,
    val showDate: Boolean,
    val position: Float,
    val depth: Boolean,
) {
    val stacked: Boolean get() = layout != "line"

    companion object {
        fun from(p: Prefs) = ClockStyle(
            enabled = p.clockEnabled,
            font = p.clockFont,
            layout = p.clockLayout,
            size = p.clockSize,
            color = p.clockColor,
            opacity = p.clockOpacity,
            showDate = p.clockDate,
            position = p.clockPosition,
            depth = p.depthEnabled,
        )
    }
}

/** The clock fonts. The bundled ones are free Google Fonts (Open Font License). */
object ClockFonts {
    class Option(val id: String, val label: String)

    val options = listOf(
        Option("bold", "Bold"),
        Option("thin", "Thin"),
        Option("gsans", "Google Sans"),
        Option("anton", "Anton"),
        Option("bebas", "Bebas Neue"),
        Option("righteous", "Righteous"),
        Option("serif", "DM Serif"),
        Option("fatface", "Abril Fatface"),
        Option("script", "Pacifico"),
        Option("mono", "Mono"),
    )

    private val cache = HashMap<String, Typeface>()

    private fun system(family: String, weight: Int) =
        Typeface.create(Typeface.create(family, Typeface.NORMAL), weight, false)

    @Synchronized
    fun typeface(context: Context, id: String): Typeface = cache.getOrPut(id) {
        fun res(resId: Int) = ResourcesCompat.getFont(context, resId) ?: system("sans-serif", 800)
        when (id) {
            "thin" -> system("sans-serif", 200)
            "gsans" -> system("google-sans", 700) // Pixel's own font; falls back to the default font elsewhere
            "anton" -> res(R.font.anton)
            "bebas" -> res(R.font.bebas_neue)
            "righteous" -> res(R.font.righteous)
            "serif" -> res(R.font.dm_serif)
            "fatface" -> res(R.font.abril_fatface)
            "script" -> res(R.font.pacifico)
            "mono" -> system("monospace", 700)
            else -> system("sans-serif", 800)
        }
    }
}

/** Light and dark clock colors picked from a photo. */
class PhotoColors(val light: Int, val dark: Int) {
    companion object {
        val DEFAULT = PhotoColors(Color.WHITE, 0xFF1C1B1F.toInt())

        fun from(photo: Bitmap): PhotoColors {
            val palette = Palette.from(photo).maximumColorCount(16).generate()
            val base = palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb ?: Color.WHITE
            val light = palette.lightVibrantSwatch?.rgb ?: palette.lightMutedSwatch?.rgb ?: base
            val dark = palette.darkVibrantSwatch?.rgb ?: palette.darkMutedSwatch?.rgb
                ?: ColorUtils.blendARGB(base, Color.BLACK, 0.6f)
            return PhotoColors(ColorUtils.blendARGB(light, Color.WHITE, 0.3f), dark)
        }
    }
}

/** Draws the clock (and optional date) onto a canvas. */
class ClockPainter(private val context: Context) {

    class Layout(
        val rect: RectF,
        val centerX: Float,
        val line1: Float,
        val line2: Float,
        val dateLine: Float,
        val textSize: Float,
        val dateSize: Float,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 500, false)
    }
    private val bounds = Rect()

    fun layout(w: Int, h: Int, style: ClockStyle): Layout {
        paint.typeface = ClockFonts.typeface(context, style.font)
        val size = w * (if (style.stacked) 0.40f else 0.24f) * style.size
        paint.textSize = size
        paint.getTextBounds("08", 0, 2, bounds)
        val digitH = bounds.height().toFloat()
        val textW = paint.measureText(if (style.stacked) "00" else "00:00")

        val top = h * style.position
        val line1 = top + digitH
        val line2 = if (style.stacked) line1 + digitH * 1.15f else line1
        val dateSize = w * 0.05f * max(0.8f, style.size)
        val dateLine = line2 + dateSize * 1.7f
        val bottom = if (style.showDate) dateLine + dateSize * 0.3f else line2
        datePaint.textSize = dateSize
        val dateW = if (style.showDate) datePaint.measureText(dateText()) else 0f
        val halfW = max(textW, dateW) / 2f
        val cx = w / 2f
        return Layout(RectF(cx - halfW, top, cx + halfW, bottom), cx, line1, line2, dateLine, size, dateSize)
    }

    fun draw(canvas: Canvas, layout: Layout, style: ClockStyle, colors: PhotoColors) {
        val base = when (style.color) {
            "white" -> Color.WHITE
            "black" -> 0xFF1C1B1F.toInt()
            "photo_dark" -> colors.dark
            else -> colors.light
        }
        val color = ColorUtils.setAlphaComponent(base, (style.opacity * 255).roundToInt().coerceIn(0, 255))
        val darkText = ColorUtils.calculateLuminance(base) < 0.4
        val shadow = if (darkText) 0x33FFFFFF else 0x55000000

        val now = Calendar.getInstance()
        val hour = if (DateFormat.is24HourFormat(context)) {
            now.get(Calendar.HOUR_OF_DAY)
        } else {
            now.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        }
        val hh = String.format(Locale.US, "%02d", hour)
        val mm = String.format(Locale.US, "%02d", now.get(Calendar.MINUTE))

        paint.typeface = ClockFonts.typeface(context, style.font)
        paint.textSize = layout.textSize
        paint.color = color
        paint.setShadowLayer(layout.textSize * 0.04f, 0f, layout.textSize * 0.01f, shadow)
        if (style.stacked) {
            canvas.drawText(hh, layout.centerX, layout.line1, paint)
            canvas.drawText(mm, layout.centerX, layout.line2, paint)
        } else {
            canvas.drawText("$hh:$mm", layout.centerX, layout.line1, paint)
        }

        if (style.showDate) {
            datePaint.textSize = layout.dateSize
            datePaint.color = color
            datePaint.setShadowLayer(layout.dateSize * 0.2f, 0f, layout.dateSize * 0.05f, shadow)
            canvas.drawText(dateText(), layout.centerX, layout.dateLine, datePaint)
        }
    }

    /** e.g. "Thursday 15 October" (in the phone's language). */
    private fun dateText(): String {
        val locale = Locale.getDefault()
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
        return DateFormat.format(pattern, Calendar.getInstance()).toString()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}
