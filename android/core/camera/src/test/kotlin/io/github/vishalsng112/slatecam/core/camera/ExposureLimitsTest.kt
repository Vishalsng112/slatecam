package io.github.vishalsng112.slatecam.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureLimitsTest {

    @Test
    fun `default exposure mode is HAL AE`() {
        assertEquals(ExposureMode.AE, ExposureMode.fromWire("AE"))
        assertEquals(listOf("AE", "P", "S", "M"), ExposureMode.wireValues)
    }

    @Test
    fun `shutter never falls below 48 fps`() {
        assertEquals(1_000_000_000L / 48, ExposureLimits.MAX_EXPOSURE_NS)
        assertEquals(
            ExposureLimits.MAX_EXPOSURE_NS,
            ExposureLimits.clampExposure(1_000_000_000L / 30),
        )
        assertTrue(
            ExposureLimits.clampExposure(1_000_000_000L / 60) <
                ExposureLimits.MAX_EXPOSURE_NS,
        )
    }
}
