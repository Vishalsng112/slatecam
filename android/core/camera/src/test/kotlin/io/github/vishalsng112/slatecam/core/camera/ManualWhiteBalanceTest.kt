package io.github.vishalsng112.slatecam.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualWhiteBalanceTest {

    private val allPresets = listOf(
        "auto",
        "incandescent",
        "fluorescent",
        "warm_fluorescent",
        "daylight",
        "cloudy",
        "twilight",
        "shade",
        "off",
    )

    @Test
    fun `preset picks the nearest supported temperature`() {
        assertEquals("incandescent", ManualWhiteBalance.presetFor(2800, allPresets)?.mode)
        assertEquals("fluorescent", ManualWhiteBalance.presetFor(4200, allPresets)?.mode)
        assertEquals("daylight", ManualWhiteBalance.presetFor(5600, allPresets)?.mode)
        assertEquals("shade", ManualWhiteBalance.presetFor(9000, allPresets)?.mode)
    }

    @Test
    fun `preset ignores modes the sensor does not offer`() {
        val modes = listOf("auto", "daylight", "cloudy")
        assertEquals("daylight", ManualWhiteBalance.presetFor(5600, modes)?.mode)
        assertEquals("cloudy", ManualWhiteBalance.presetFor(8000, modes)?.mode)
    }

    @Test
    fun `manual is unsupported without a preset`() {
        assertNull(ManualWhiteBalance.presetFor(5600, listOf("auto", "off")))
        assertNull(ManualWhiteBalance.presetFor(5600, emptyList()))
    }

    @Test
    fun `preset mode exposes its nominal temperature`() {
        assertEquals(5500, ManualWhiteBalance.presetForMode("daylight")?.cct)
        assertEquals(7500, ManualWhiteBalance.presetForMode("shade")?.cct)
        assertNull(ManualWhiteBalance.presetForMode("auto"))
        assertNull(ManualWhiteBalance.presetForMode("manual"))
    }

    @Test
    fun `every preset is a no-op residual against itself`() {
        for (mode in listOf("incandescent", "daylight", "cloudy", "shade")) {
            val preset = ManualWhiteBalance.presetForMode(mode)!!
            val gains = ManualWhiteBalance.residualGains(preset.cct, 0, preset.cct)
            assertEquals(1f, gains.red, 1e-4f)
            assertEquals(1f, gains.blue, 1e-4f)
        }
    }

    @Test
    fun `kelvin and tint clamp to the supported range`() {
        assertEquals(2000, ManualWhiteBalance.clampKelvin(1200))
        assertEquals(10000, ManualWhiteBalance.clampKelvin(14000))
        assertEquals(5600, ManualWhiteBalance.clampKelvin(5600))
        assertEquals(-100, ManualWhiteBalance.clampTint(-400))
        assertEquals(100, ManualWhiteBalance.clampTint(400))
    }

    @Test
    fun `matching the preset is a no-op residual`() {
        val gains = ManualWhiteBalance.residualGains(5500, 0, 5500)
        assertEquals(1f, gains.red, 1e-4f)
        assertEquals(1f, gains.green, 0f)
        assertEquals(1f, gains.blue, 1e-4f)
    }

    @Test
    fun `lower kelvin cools the image and higher kelvin warms it`() {
        val warm = ManualWhiteBalance.residualGains(3200, 0, 5500)
        val cool = ManualWhiteBalance.residualGains(8000, 0, 5500)
        assertTrue(warm.red < 1f)
        assertTrue(warm.blue > 1f)
        assertTrue(cool.red > 1f)
        assertTrue(cool.blue < 1f)
    }

    @Test
    fun `residual is always green normalised`() {
        for (kelvin in listOf(2000, 3200, 5600, 8000, 10000)) {
            assertEquals(1f, ManualWhiteBalance.residualGains(kelvin, 0, 5500).green, 0f)
        }
    }

    @Test
    fun `positive tint adds magenta and negative tint adds green`() {
        val neutral = ManualWhiteBalance.residualGains(5600, 0, 5500)
        val magenta = ManualWhiteBalance.residualGains(5600, 60, 5500)
        val green = ManualWhiteBalance.residualGains(5600, -60, 5500)
        assertTrue(magenta.red > neutral.red)
        assertTrue(magenta.blue > neutral.blue)
        assertTrue(green.red < neutral.red)
        assertTrue(green.blue < neutral.blue)
    }

    @Test
    fun `residual stays within the safe shader range`() {
        for (kelvin in listOf(2000, 3200, 5600, 8000, 10000)) {
            for (tint in listOf(-100, 0, 100)) {
                val gains = ManualWhiteBalance.residualGains(kelvin, tint, 5500)
                assertTrue("kelvin=$kelvin tint=$tint", gains.red in 0.25f..4f)
                assertTrue("kelvin=$kelvin tint=$tint", gains.blue in 0.25f..4f)
            }
        }
    }

    @Test
    fun `illuminant stays inside the unit range at every step`() {
        var kelvin = ManualWhiteBalance.MIN_KELVIN
        while (kelvin <= ManualWhiteBalance.MAX_KELVIN) {
            val rgb = ManualWhiteBalance.illuminantRgb(kelvin)
            for (channel in rgb) {
                assertTrue("kelvin=$kelvin", channel in 0f..1f)
            }
            kelvin += 100
        }
    }

    @Test
    fun `restore keeps manual when the sensor supports it`() {
        assertEquals(
            ManualWhiteBalance.WIRE_VALUE,
            ManualWhiteBalance.restoreMode(ManualWhiteBalance.WIRE_VALUE, allPresets, true),
        )
        assertEquals(
            "auto",
            ManualWhiteBalance.restoreMode(ManualWhiteBalance.WIRE_VALUE, listOf("auto"), false),
        )
    }

    @Test
    fun `restore maps a stale off mode to manual`() {
        assertEquals(
            ManualWhiteBalance.WIRE_VALUE,
            ManualWhiteBalance.restoreMode("off", allPresets, true),
        )
    }

    @Test
    fun `restore falls back when a preset is missing`() {
        assertEquals(
            "auto",
            ManualWhiteBalance.restoreMode("tungsten", listOf("auto", "off"), true),
        )
        assertEquals(
            "daylight",
            ManualWhiteBalance.restoreMode("tungsten", listOf("daylight"), true),
        )
    }
}
