package io.github.vishalsng112.slatecam.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureControllerTest {

    private val loop = ExposureController()

    private fun sample(measured: Float): MeterSample =
        MeterSample(measured, ExposureMeter.NEUTRAL_TARGET)

    @Test
    fun `dark scene raises ISO`() {
        val next = loop.nextIso(sample(0.10f), 400, 100, 3200, 0.2f, 0.5f)
        assertTrue("expected ISO above 400, was $next", next > 400)
    }

    @Test
    fun `bright scene lowers ISO`() {
        val next = loop.nextIso(sample(0.80f), 400, 100, 3200, 0.2f, 0.5f)
        assertTrue("expected ISO below 400, was $next", next < 400)
    }

    @Test
    fun `ISO stays clamped to the reported range`() {
        assertEquals(3200, loop.nextIso(sample(0.01f), 3200, 100, 3200, 1f, 1f))
        assertEquals(100, loop.nextIso(sample(1f), 100, 100, 3200, 1f, 1f))
    }

    @Test
    fun `dead band suppresses small corrections`() {
        val next = loop.nextIso(sample(0.44f), 400, 100, 3200, 0.2f, 0.5f)
        assertEquals(400, next)
        assertEquals(0f, loop.correctionEv(sample(0.44f), 0.2f, 0.5f))
    }

    @Test
    fun `change is rate limited per second`() {
        val next = loop.nextIso(sample(0.01f), 400, 100, 3200, 0.1f, 0.5f)
        assertTrue("expected a bounded first step, was $next", next in 401..700)
    }

    @Test
    fun `fast speed corrects more than slow`() {
        val slow = loop.correctionEv(sample(0.10f), 0.2f, 0f)
        val fast = loop.correctionEv(sample(0.10f), 0.2f, 1f)
        assertTrue("slow=$slow fast=$fast", fast > slow)
    }

    @Test
    fun `speed is clamped to the unit range`() {
        val below = loop.correctionEv(sample(0.10f), 0.2f, -3f)
        val zero = loop.correctionEv(sample(0.10f), 0.2f, 0f)
        val above = loop.correctionEv(sample(0.10f), 0.2f, 9f)
        val one = loop.correctionEv(sample(0.10f), 0.2f, 1f)
        assertEquals(zero, below)
        assertEquals(one, above)
    }
}
