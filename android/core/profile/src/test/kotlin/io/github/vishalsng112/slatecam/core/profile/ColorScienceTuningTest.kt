package io.github.vishalsng112.slatecam.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorScienceTuningTest {

    @Test
    fun `identity keeps exposure and white balance neutral`() {
        val identity = ColorScienceTuning.IDENTITY
        assertEquals(1f, identity.exposureMultiplier(), 0.001f)
        val gains = identity.whiteBalanceGains()
        assertEquals(1f, gains[0], 0.001f)
        assertEquals(1f, gains[1], 0.001f)
        assertEquals(1f, gains[2], 0.001f)
    }

    @Test
    fun `one stop doubles exposure`() {
        assertEquals(2f, ColorScienceTuning(exposureEv = 1f).exposureMultiplier(), 0.001f)
        assertEquals(0.5f, ColorScienceTuning(exposureEv = -1f).exposureMultiplier(), 0.001f)
    }

    @Test
    fun `warm raises red and lowers blue`() {
        val gains = ColorScienceTuning(temperature = 1f).whiteBalanceGains()
        assertTrue(gains[0] > 1f)
        assertTrue(gains[2] < 1f)
    }

    @Test
    fun `magenta tint reduces green`() {
        val gains = ColorScienceTuning(tint = 1f).whiteBalanceGains()
        assertTrue(gains[1] < 1f)
    }

    @Test
    fun `values are clamped to the UI ranges`() {
        val tuning = ColorScienceTuning(
            exposureEv = 99f,
            contrast = -4f,
            saturation = 9f,
            temperature = 4f,
            tint = -4f,
        ).normalized()
        assertEquals(ColorScienceTuning.MAX_EV, tuning.exposureEv, 0.001f)
        assertEquals(ColorScienceTuning.MIN_CONTRAST, tuning.contrast, 0.001f)
        assertEquals(ColorScienceTuning.MAX_SATURATION, tuning.saturation, 0.001f)
        assertEquals(1f, tuning.temperature, 0.001f)
        assertEquals(-1f, tuning.tint, 0.001f)
    }
}
