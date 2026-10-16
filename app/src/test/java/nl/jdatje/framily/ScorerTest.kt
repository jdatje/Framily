package nl.jdatje.framily

import android.graphics.RectF
import nl.jdatje.framily.scan.Scorer
import org.junit.Assert.assertTrue
import org.junit.Test

/** RectF is an Android class; in plain unit tests we use a tiny stand-in with the same fields. */
class ScorerTest {
    private fun face(l: Float, t: Float, r: Float, b: Float) = RectF().apply { left = l; top = t; right = r; bottom = b }

    // All of these scored 98–99 with the old formula: big, sharp, smiling faces.
    private fun score(faces: List<RectF>, smile: Float = 0.97f, eyes: Float = 0.98f, family: Int = 1) =
        Scorer.parts(faces, faces.maxOf { it.bottom - it.top }, smile, eyes, 1f, family, hasFamily = true).total

    @Test
    fun goodPhotosNowSpreadOut() {
        val ideal = score(listOf(face(0.35f, 0.32f, 0.65f, 0.58f)), family = 2)          // room for the clock, two family members
        val oneFamily = score(listOf(face(0.35f, 0.32f, 0.65f, 0.58f)), family = 1)
        val headAtTop = score(listOf(face(0.30f, 0.05f, 0.70f, 0.40f)))                  // no room above the head
        val cutOff = score(listOf(face(0.0f, 0.30f, 0.35f, 0.60f)))                     // face cut by the left edge
        val squint = score(listOf(face(0.35f, 0.32f, 0.65f, 0.58f)), eyes = 0.6f)
        println("ideal=$ideal oneFamily=$oneFamily headAtTop=$headAtTop cutOff=$cutOff squint=$squint")
        assertTrue(ideal > oneFamily)
        assertTrue(ideal - headAtTop > 8)
        assertTrue(ideal - cutOff > 10)
        assertTrue(ideal - squint > 8)
        assertTrue(ideal <= 100f)
    }
}
