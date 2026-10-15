package nl.jdatje.framily.weather

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/** One hour of forecast. [time] is when the hour starts (epoch millis). */
data class WeatherHour(val time: Long, val temp: Float, val code: Int, val isDay: Boolean, val rain: Int)

data class WeatherData(
    val temp: Float,
    val code: Int,
    val isDay: Boolean,
    val hours: List<WeatherHour>,
    val fetchedAt: Long,
    val place: String,
    /** Sunrise/sunset pairs (epoch millis) for today and tomorrow. */
    val sun: List<Pair<Long, Long>> = emptyList(),
) {
    /** Night = before sunrise or after sunset; null when unknown. */
    fun isNight(at: Long = System.currentTimeMillis()): Boolean? {
        if (sun.isEmpty()) return null
        val day = sun.firstOrNull { (rise, set) -> at in rise..set }
        return day == null
    }

    /**
     * A short heads-up about rain in the coming hours, e.g. "🌧 Rain from 17:00" or "🌤 Dry from 18:00".
     * Null when nothing changes in the next 6 hours.
     */
    fun rainAlert(timeLabel: (Long) -> String, at: Long = System.currentTimeMillis()): String? {
        val next = nextHours(6, at)
        if (next.isEmpty()) return null
        val nowWet = WeatherCodes.isWet(now(at).code)
        return if (!nowWet) {
            next.firstOrNull { WeatherCodes.isWet(it.code) || it.rain >= 60 }?.let { "🌧 Rain from ${timeLabel(it.time)}" }
        } else {
            next.firstOrNull { !WeatherCodes.isWet(it.code) && it.rain < 30 }?.let { "🌤 Dry from ${timeLabel(it.time)}" }
        }
    }

    /** The weather right now: the "current" block if fresh, otherwise the matching forecast hour. */
    fun now(at: Long = System.currentTimeMillis()): WeatherHour {
        if (at - fetchedAt < 90 * 60_000L) return WeatherHour(at, temp, code, isDay, 0)
        return hours.lastOrNull { it.time <= at } ?: WeatherHour(at, temp, code, isDay, 0)
    }

    /** The next [n] whole hours after now. */
    fun nextHours(n: Int, at: Long = System.currentTimeMillis()): List<WeatherHour> =
        hours.filter { it.time > at }.take(n)

    fun toJson(): String = JSONObject().apply {
        put("temp", temp.toDouble()); put("code", code); put("isDay", isDay)
        put("fetchedAt", fetchedAt); put("place", place)
        put("sun", JSONArray().apply { sun.forEach { (r, st) -> put(JSONArray().apply { put(r); put(st) }) } })
        put("hours", JSONArray().apply {
            hours.forEach { h ->
                put(JSONObject().apply {
                    put("time", h.time); put("temp", h.temp.toDouble()); put("code", h.code)
                    put("isDay", h.isDay); put("rain", h.rain)
                })
            }
        })
    }.toString()

    companion object {
        fun fromJson(s: String?): WeatherData? = try {
            if (s.isNullOrBlank()) null else {
                val o = JSONObject(s)
                val arr = o.getJSONArray("hours")
                WeatherData(
                    temp = o.getDouble("temp").toFloat(),
                    code = o.getInt("code"),
                    isDay = o.getBoolean("isDay"),
                    hours = (0 until arr.length()).map { i ->
                        val h = arr.getJSONObject(i)
                        WeatherHour(h.getLong("time"), h.getDouble("temp").toFloat(), h.getInt("code"), h.getBoolean("isDay"), h.getInt("rain"))
                    },
                    fetchedAt = o.getLong("fetchedAt"),
                    place = o.optString("place"),
                    sun = o.optJSONArray("sun")?.let { a ->
                        (0 until a.length()).map { i -> a.getJSONArray(i).let { it.getLong(0) to it.getLong(1) } }
                    } ?: emptyList(),
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** WMO weather codes (what Open-Meteo returns) to an emoji. */
object WeatherCodes {
    /** Drizzle, rain, showers or thunder. */
    fun isWet(code: Int) = code in 51..67 || code in 80..82 || code in 95..99

    fun emoji(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1 -> if (isDay) "🌤️" else "🌙"
        2 -> if (isDay) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> if (isDay) "🌦️" else "🌧️"
        61, 63, 65, 66, 67 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"
        80, 81, 82 -> if (isDay) "🌦️" else "🌧️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }
}

data class City(val name: String, val region: String, val lat: Double, val lon: Double) {
    val label: String get() = if (region.isBlank()) name else "$name, $region"
}

/** Open-Meteo: free weather data, no account or key needed. */
object WeatherApi {

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun forecast(lat: Double, lon: Double, place: String): WeatherData {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,weather_code,is_day" +
                "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
                "&daily=sunrise,sunset&forecast_days=2" +
                "&forecast_hours=12&timezone=auto",
            lat, lon,
        )
        return parseForecast(get(url), place, System.currentTimeMillis())
    }

    fun parseForecast(json: String, place: String, now: Long): WeatherData {
        val o = JSONObject(json)
        val offset = ZoneOffset.ofTotalSeconds(o.getInt("utc_offset_seconds"))
        val cur = o.getJSONObject("current")
        val hourly = o.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val codes = hourly.getJSONArray("weather_code")
        val rain = hourly.optJSONArray("precipitation_probability")
        val day = hourly.getJSONArray("is_day")
        val hours = (0 until times.length()).map { i ->
            WeatherHour(
                time = LocalDateTime.parse(times.getString(i)).toInstant(offset).toEpochMilli(),
                temp = temps.getDouble(i).toFloat(),
                code = codes.getInt(i),
                isDay = day.getInt(i) == 1,
                rain = rain?.optInt(i, 0) ?: 0,
            )
        }
        val sun = o.optJSONObject("daily")?.let { d ->
            val rise = d.getJSONArray("sunrise")
            val set = d.getJSONArray("sunset")
            (0 until minOf(rise.length(), set.length())).map { i ->
                LocalDateTime.parse(rise.getString(i)).toInstant(offset).toEpochMilli() to
                    LocalDateTime.parse(set.getString(i)).toInstant(offset).toEpochMilli()
            }
        } ?: emptyList()
        return WeatherData(
            sun = sun,
            temp = cur.getDouble("temperature_2m").toFloat(),
            code = cur.getInt("weather_code"),
            isDay = cur.getInt("is_day") == 1,
            hours = hours,
            fetchedAt = now,
            place = place,
        )
    }

    fun searchCity(query: String): List<City> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val lang = Locale.getDefault().language.ifBlank { "en" }
        val o = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=6&language=$lang&format=json"))
        val arr = o.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val region = listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
            City(r.getString("name"), region, r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }
}
