package io.github.vishalsng112.slatecam.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothZoomTest {

    private fun ramped(): SmoothZoom = SmoothZoom().apply { setBounds(1f, 10f) }

    private fun SmoothZoom.settle(maxSteps: Int = 120): Float {
        repeat(maxSteps) { advance(1f / 30f) }
        return value
    }

    @Test
    fun `snap sets both value and target`() {
        val zoom = ramped().apply { snapTo(3f) }
        assertEquals(3f, zoom.value, 0f)
        assertEquals(3f, zoom.target, 0f)
        assertFalse(zoom.isAnimating)
    }

    @Test
    fun `retarget animates instead of jumping`() {
        val zoom = ramped().apply { snapTo(1f) }
        assertTrue(zoom.retarget(2f))
        assertEquals(1f, zoom.value, 0f)

        zoom.advance(1f / 30f)
        assertTrue(zoom.value > 1f)
        assertTrue(zoom.value < 2f)
        assertTrue(zoom.isAnimating)
    }

    @Test
    fun `small adjustment settles on target exactly`() {
        val zoom = ramped().apply { snapTo(1f) }
        zoom.retarget(2f)
        assertEquals(2f, zoom.settle(), 0f)
        assertFalse(zoom.isAnimating)
    }

    @Test
    fun `large jump is rate limited on the first frame`() {
        val zoom = ramped().apply { snapTo(1f) }
        zoom.retarget(10f)
        zoom.advance(1f / 30f)
        // exp(6 / 30) - 1 = 0.221, so one 30 fps step cannot exceed ~1.23x.
        assertTrue(zoom.value <= 1.23f)
        assertTrue(zoom.isAnimating)
    }

    @Test
    fun `large jump still settles on target`() {
        val zoom = ramped().apply { snapTo(1f) }
        zoom.retarget(10f)
        assertEquals(10f, zoom.settle(), 0f)
    }

    @Test
    fun `pull out moves down monotonically`() {
        val zoom = ramped().apply { snapTo(5f) }
        zoom.retarget(1f)
        var previous = zoom.value
        repeat(10) {
            zoom.advance(1f / 30f)
            assertTrue(zoom.value <= previous)
            assertTrue(zoom.value >= 1f)
            previous = zoom.value
        }
        assertEquals(1f, zoom.settle(), 0f)
    }

    @Test
    fun `bounds clamp snap and target`() {
        val zoom = ramped()
        zoom.snapTo(9f)
        assertEquals(9f, zoom.value, 0f)

        zoom.setBounds(1f, 4f)
        assertEquals(4f, zoom.value, 0f)
        assertEquals(4f, zoom.target, 0f)

        assertTrue(zoom.retarget(0.5f))
        assertEquals(1f, zoom.target, 0f)
        assertEquals(1f, zoom.settle(), 0f)
    }

    @Test
    fun `zero dt tick does not drop the target`() {
        val zoom = ramped().apply { snapTo(1f) }
        zoom.retarget(5f)
        // The first ramp callback can land in the same millisecond as the tap.
        assertTrue(zoom.advance(0f))
        assertTrue(zoom.isAnimating)
        assertEquals(5f, zoom.settle(), 0f)
    }

    @Test
    fun `retarget to the current value is a no-op`() {
        val zoom = ramped().apply { snapTo(2f) }
        assertFalse(zoom.retarget(2f))
        assertFalse(zoom.isAnimating)
    }

    @Test
    fun `stop holds the animated value`() {
        val zoom = ramped().apply { snapTo(1f) }
        zoom.retarget(3f)
        zoom.advance(1f / 30f)
        val held = zoom.value
        zoom.stop()
        assertEquals(held, zoom.value, 0f)
        assertEquals(held, zoom.target, 0f)
        assertFalse(zoom.isAnimating)
    }
}
