package io.github.vishalsng112.slatecam.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoIsoControllerTest {

    private val loop = AutoIsoController()

    @Test
    fun `dark scene raises ISO`() {
        val next = loop.update(histogramAt(10), currentIso = 400, isoMin = 100, isoMax = 3200, 0.2f)
        assertTrue("expected ISO above 400, was $next", next > 400)
    }

    @Test
    fun `bright scene lowers ISO`() {
        val next = loop.update(histogramAt(50), currentIso = 400, isoMin = 100, isoMax = 3200, 0.2f)
        assertTrue("expected ISO below 400, was $next", next < 400)
    }

    @Test
    fun `ISO stays clamped to the reported range`() {
        val dark = loop.update(histogramAt(0), currentIso = 3200, isoMin = 100, isoMax = 3200, 1f)
        assertEquals(3200, dark)

        val bright = loop.update(histogramAt(63), currentIso = 100, isoMin = 100, isoMax = 3200, 1f)
        assertEquals(100, bright)
    }

    @Test
    fun `dead band suppresses small corrections`() {
        // Bin 28 sits just below the default 0.45 target.
        val next = loop.update(histogramAt(28), currentIso = 400, isoMin = 100, isoMax = 3200, 0.2f)
        assertEquals(400, next)
    }

    @Test
    fun `empty or zero histograms leave ISO alone`() {
        assertEquals(400, loop.update(IntArray(0), 400, 100, 3200, 0.2f))
        assertEquals(400, loop.update(IntArray(64), 400, 100, 3200, 0.2f))
    }

    @Test
    fun `change is rate limited per second`() {
        // A very dark frame would ask for several stops at once.
        val next = loop.update(histogramAt(0), currentIso = 400, isoMin = 100, isoMax = 3200, 0.1f)
        assertTrue("expected a bounded first step, was $next", next in 401..600)
    }

    @Test
    fun `mode labels round trip and reject unknown values`() {
        assertEquals(ExposureMode.PROGRAM, ExposureMode.fromWire("P"))
        assertEquals(ExposureMode.SHUTTER_PRIORITY, ExposureMode.fromWire("s"))
        assertEquals(ExposureMode.MANUAL, ExposureMode.fromWire("M"))
        assertEquals(ExposureMode.PROGRAM, ExposureMode.fromWire("av"))
        assertEquals(listOf("P", "S", "M"), ExposureMode.wireValues)
    }

    private fun histogramAt(bin: Int, bins: Int = 64): IntArray =
        IntArray(bins).also { it[bin] = 1_000 }
}
