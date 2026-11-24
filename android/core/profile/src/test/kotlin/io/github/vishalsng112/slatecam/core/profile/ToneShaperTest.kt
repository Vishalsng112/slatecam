package io.github.vishalsng112.slatecam.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneShaperTest {

    @Test
    fun `neutral settings are identity`() {
        for (x in listOf(0f, 0.05f, 0.18f, 0.5f, 1f)) {
            assertEquals(x, ToneShaper.shape(x, 1f, 0.18f, 1f, 0f), 0.001f)
        }
    }

    @Test
    fun `contrast expands around the pivot`() {
        val mid = ToneShaper.shape(0.36f, 2f, 0.18f, 1f, 0f)
        val low = ToneShaper.shape(0.09f, 2f, 0.18f, 1f, 0f)
        assertTrue(mid > 0.36f)
        assertTrue(low < 0.09f)
    }

    @Test
    fun `highlight knee compresses but stays below white`() {
        val out = ToneShaper.shape(1f, 1f, 0.18f, 0.8f, 0f)
        assertTrue(out > 0.8f)
        assertTrue(out < 1f)
    }

    @Test
    fun `shadow toe lifts black without moving the pivot`() {
        val black = ToneShaper.shape(0f, 1f, 0.18f, 1f, 0.5f)
        val pivot = ToneShaper.shape(0.18f, 1f, 0.18f, 1f, 0.5f)
        assertTrue(black > 0f)
        assertEquals(0.18f, pivot, 0.001f)
    }

    @Test
    fun `output is clamped and monotonic`() {
        var previous = -1f
        for (i in 0..100) {
            val x = i / 100f
            val out = ToneShaper.shape(x, 1.2f, 0.18f, 0.8f, 0.2f)
            assertTrue(out in 0f..1f)
            assertTrue(out >= previous)
            previous = out
        }
    }
}
