package nl.jdatje.framily

import nl.jdatje.framily.wallpaper.AutoOpacity
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoOpacityTest {
    private val white = 1f
    private val mid = 0.5f

    @Test
    fun whiteTextOnWhiteSkyGetsSolidTextAndBackground() {
        val r = AutoOpacity.pick(photoLum = 0.9f, busyness = 0.05f, textLum = white, target = 0.5f)
        println("white sky: text=${r.text} bg=${r.background}")
        assertTrue(r.text > 0.9f)
        assertTrue(r.background > 0.5f)
    }

    @Test
    fun whiteTextOnDarkCalmPhotoStaysLight() {
        val r = AutoOpacity.pick(photoLum = 0.08f, busyness = 0.03f, textLum = white, target = 0.5f)
        println("dark calm: text=${r.text} bg=${r.background}")
        assertTrue(r.text < 0.75f)
        assertTrue(r.background < 0.1f)
    }

    @Test
    fun busySpotNeedsMoreBackgroundThanCalmOne() {
        val calm = AutoOpacity.pick(0.3f, 0.02f, white, 0.5f)
        val busy = AutoOpacity.pick(0.3f, 0.25f, white, 0.5f)
        println("calm bg=${calm.background} busy bg=${busy.background}")
        assertTrue(busy.background > calm.background + 0.15f)
    }

    @Test
    fun clearerTargetNeverGivesLess() {
        for (lum in listOf(0.05f, 0.3f, mid, 0.8f)) {
            val subtle = AutoOpacity.pick(lum, 0.1f, white, 0f)
            val clear = AutoOpacity.pick(lum, 0.1f, white, 1f)
            assertTrue(clear.background >= subtle.background && clear.text >= subtle.text)
        }
    }

    @Test
    fun darkTextOnWhiteSkyIsFine() {
        // "Auto contrast" switches to dark text on bright photos: then little help is needed.
        val r = AutoOpacity.pick(photoLum = 0.9f, busyness = 0.05f, textLum = 0.02f, target = 0.5f)
        println("dark text on sky: text=${r.text} bg=${r.background}")
        assertTrue(r.background < 0.15f)
    }
}
