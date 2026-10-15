package nl.jdatje.framily.weather

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/** Opening a weather app when the weather on the wallpaper is tapped. */
object WeatherApps {
    /** Pixel's own weather app. */
    const val PIXEL_WEATHER = "com.google.android.apps.weather"

    class App(val packageName: String, val label: String)

    fun open(context: Context, chosen: String?) {
        val pm = context.packageManager
        val intent = listOfNotNull(chosen, PIXEL_WEATHER).firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=weather"))
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("WeatherApps", "Could not open weather app: ${e.message}")
        }
    }

    /** Apps that can be opened, likely weather apps first. */
    fun installed(context: Context): List<App> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
        fun weatherish(a: App) = listOf("weather", "weer", "buien", "rain", "meteo", "knmi").any {
            a.label.contains(it, ignoreCase = true) || a.packageName.contains(it, ignoreCase = true)
        }
        return apps.sortedWith(compareByDescending<App> { weatherish(it) }.thenBy { it.label.lowercase() })
    }

    fun label(context: Context, pkg: String?): String {
        val p = pkg ?: PIXEL_WEATHER
        return try {
            context.packageManager.getApplicationInfo(p, 0).loadLabel(context.packageManager).toString()
        } catch (e: Exception) {
            if (pkg == null) "Pixel Weather (or the web if it's not installed)" else p
        }
    }
}
