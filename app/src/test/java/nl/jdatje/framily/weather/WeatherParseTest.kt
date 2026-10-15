package nl.jdatje.framily.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherParseTest {
    private val json = javaClass.classLoader!!.getResource("forecast.json")!!.readText()

    @Test
    fun parsesForecastAndRoundTrips() {
        // Pretend it's 30 minutes after the first forecast hour, so the saved file never gets "old".
        val first = WeatherApi.parseForecast(json, "Amsterdam", 0L).hours.first().time
        val now = first + 30 * 60_000L
        val data = WeatherApi.parseForecast(json, "Amsterdam", now)
        assertEquals(12, data.hours.size)
        // Hours are one hour apart and in order
        data.hours.zipWithNext().forEach { (a, b) -> assertEquals(3_600_000L, b.time - a.time) }
        val next = data.nextHours(4, now)
        assertEquals(4, next.size)
        assertTrue(next.all { it.time > now })
        assertTrue("first forecast hour is within the next hour", next.first().time - now <= 3_600_000L)
        val back = WeatherData.fromJson(data.toJson())
        assertNotNull(back)
        assertEquals(data, back)
        println("now=${data.now(now)} next=${next.map { it.temp }} place=${back!!.place}")
    }

    @Test
    fun usesForecastHourWhenCurrentIsOld() {
        val fetched = WeatherApi.parseForecast(json, "x", 0L).hours.first().time + 30 * 60_000L
        val data = WeatherApi.parseForecast(json, "x", fetched)
        val later = fetched + 3 * 3_600_000L
        val now = data.now(later)
        assertTrue(now.time <= later && later - now.time < 3_600_000L)
    }
}
