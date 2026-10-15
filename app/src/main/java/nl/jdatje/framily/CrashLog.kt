package nl.jdatje.framily

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers the last crash (or serious error) so the app can show it and you can copy it.
 * Stays on the phone; nothing is sent anywhere.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    /** Catch crashes from now on (called once when the app starts). */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                record(app, e, fatal = true)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun record(context: Context, e: Throwable, fatal: Boolean) {
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        file(context).writeText(
            "Framily $version · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n" +
                "$time · ${if (fatal) "crash" else "error (app kept running)"}\n\n" +
                trace.take(8000)
        )
    }

    /** The last report, or null. */
    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }
}
