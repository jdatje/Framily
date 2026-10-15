package nl.jdatje.framily.weather

import nl.jdatje.framily.rotation.OnThisDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class WeatherExtrasTest {
    private val json = javaClass.classLoader!!.getResource("forecast.json")!!.readText()
    private val base = WeatherApi.parseForecast(json, "Amsterdam", 0L)

    @Test
    fun sunTimesParseAndNightWorks() {
        assertEquals(2, base.sun.size)
        val (rise, set) = base.sun[0]
        assertTrue(set > rise)
        assertFalse(base.isNight(rise + 60_000)!!)        // just after sunrise: day
        assertTrue(base.isNight(set + 60_000)!!)          // just after sunset: night
        assertTrue(base.isNight(rise - 60_000)!!)         // just before sunrise: night
        val back = WeatherData.fromJson(base.toJson())!!
        assertEquals(base.sun, back.sun)
    }

    @Test
    fun rainAlert() {
        val t0 = base.hours.first().time
        fun hour(i: Int, code: Int, rain: Int) = WeatherHour(t0 + i * 3_600_000L, 15f, code, true, rain)
        val label: (Long) -> String = { "h" + ((it - t0) / 3_600_000L) }
        // Dry now, rain in 2 hours
        val dry = base.copy(code = 1, fetchedAt = t0, hours = listOf(hour(0, 1, 0), hour(1, 2, 10), hour(2, 61, 80), hour(3, 63, 90)))
        assertEquals("🌧 Rain from h2", dry.rainAlert(label, t0 + 60_000))
        // Raining now, dry from hour 3
        val wet = base.copy(code = 61, fetchedAt = t0, hours = listOf(hour(0, 61, 90), hour(1, 61, 80), hour(2, 63, 70), hour(3, 2, 10)))
        assertEquals("🌤 Dry from h3", wet.rainAlert(label, t0 + 60_000))
        // Nothing changes
        val calm = base.copy(code = 1, fetchedAt = t0, hours = (0..6).map { hour(it, 1, 0) })
        assertNull(calm.rainAlert(label, t0 + 60_000))
    }

    @Test
    fun onThisDay() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 15, 12, 0) }.timeInMillis
        fun at(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { set(y, m, d, 9, 0) }.timeInMillis
        assertEquals(7, OnThisDay.yearsAgoToday(at(2019, Calendar.OCTOBER, 15), now))
        assertEquals(2, OnThisDay.yearsAgoToday(at(2024, Calendar.OCTOBER, 13), now))   // 2 days off: still counts
        assertNull(OnThisDay.yearsAgoToday(at(2024, Calendar.OCTOBER, 10), now))        // too far off
        assertNull(OnThisDay.yearsAgoToday(at(2026, Calendar.OCTOBER, 15), now))        // this year
        assertNotNull(OnThisDay.yearsAgoToday(at(2020, Calendar.DECEMBER, 31), Calendar.getInstance().apply { set(2026, Calendar.JANUARY, 1, 12, 0) }.timeInMillis))
    }
}
