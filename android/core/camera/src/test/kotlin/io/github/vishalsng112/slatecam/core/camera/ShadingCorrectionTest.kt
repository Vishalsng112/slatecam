package io.github.vishalsng112.slatecam.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadingCorrectionTest {

    @Test
    fun `correction prefers high quality then fast`() {
        assertEquals(
            ShadingCorrection.HIGH_QUALITY,
            ShadingCorrection.requestedMode(
                listOf("off", "fast", "high_quality"),
                enabled = true,
            ),
        )
        assertEquals(
            ShadingCorrection.FAST,
            ShadingCorrection.requestedMode(listOf("off", "fast"), enabled = true),
        )
    }

    @Test
    fun `disabling correction requests off`() {
        assertEquals(
            ShadingCorrection.OFF,
            ShadingCorrection.requestedMode(
                listOf("off", "fast", "high_quality"),
                enabled = false,
            ),
        )
    }

    @Test
    fun `camera with only off is not supported`() {
        assertFalse(ShadingCorrection.isSupported(listOf("off")))
        assertEquals(
            ShadingCorrection.OFF,
            ShadingCorrection.requestedMode(listOf("off"), enabled = true),
        )
        assertNull(ShadingCorrection.requestedMode(emptyList(), enabled = true))
    }

    @Test
    fun `fast or high quality marks the sensor supported`() {
        assertTrue(ShadingCorrection.isSupported(listOf("off", "fast")))
        assertTrue(ShadingCorrection.isSupported(listOf("off", "high_quality")))
        assertFalse(ShadingCorrection.isSupported(listOf("off")))
    }
}
