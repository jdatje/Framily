package nl.jdatje.framily

import nl.jdatje.framily.data.Photo
import nl.jdatje.framily.rotation.OnThisDay
import nl.jdatje.framily.rotation.Rotator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RotationTest {
    private fun at(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { set(y, m, d, 12, 0, 0) }.timeInMillis

    @Test
    fun onThisDayWindow() {
        val now = at(2026, Calendar.NOVEMBER, 21)
        assertEquals(3, OnThisDay.match(at(2023, Calendar.NOVEMBER, 21), now, 7)!!.years)
        assertEquals(6, OnThisDay.match(at(2020, Calendar.NOVEMBER, 27), now, 7)!!.days)
        assertNull(OnThisDay.match(at(2020, Calendar.NOVEMBER, 30), now, 7))   // 9 days off
        assertNotNull(OnThisDay.match(at(2020, Calendar.NOVEMBER, 30), now, 10))
        assertNull(OnThisDay.match(at(2026, Calendar.NOVEMBER, 20), now, 7))   // this year
        // Around New Year: 28 Dec 2023 is 5 days before 2 Jan, two years earlier.
        val jan = at(2026, Calendar.JANUARY, 2)
        val m = OnThisDay.match(at(2023, Calendar.DECEMBER, 28), jan, 7)!!
        assertEquals(5, m.days)
        assertEquals(2, m.years)
    }

    private fun photo(id: Long, rating: Int) = Photo(
        id = id, bucketId = "b", bucketName = "Camera", width = 1, height = 2, faceCount = 1, faceRatio = 0.3f,
        score = 80f, faces = emptyList(), smile = 1f, eyes = 1f, sharp = 1f, blocked = false,
        lastShown = 0L, dateTaken = 0L, phash = null, rating = rating,
    )

    @Test
    fun likedPhotosComeAboutOneAndAHalfTimesAsOften() {
        val shown = HashMap<Long, Int>()
        var photos = (1L..20L).map { photo(it, if (it <= 5) 1 else 0) }
        var now = 1_000_000_000L
        repeat(20_000) {
            now += 3_600_000L
            val p = Rotator.pick(photos, now)
            shown[p.id] = (shown[p.id] ?: 0) + 1
            photos = photos.map { if (it.id == p.id) it.copy(lastShown = now) else it }
        }
        val liked = (1L..5L).sumOf { shown[it] ?: 0 } / 5.0
        val normal = (6L..20L).sumOf { shown[it] ?: 0 } / 15.0
        println("liked=$liked normal=$normal ratio=${liked / normal}")
        assertTrue(liked / normal in 1.4..1.6)
    }
}
