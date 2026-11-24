package io.github.vishalsng112.slatecam.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SoftwareFocusEngineTest {

    private val maxDiopters = 10f

    private fun scoreAt(diopters: Float, peak: Float): Float =
        (1f - abs(diopters - peak) / maxDiopters).coerceAtLeast(0f)

    private fun converge(
        peak: Float,
        speed: Float,
        start: Float = 0f,
        steps: Int = 80,
    ): Float {
        val engine = SoftwareFocusEngine()
        engine.reset(start)
        var current = start
        var target = start
        var now = 0L
        repeat(steps) {
            now += 100L
            val update = engine.update(
                score = scoreAt(current, peak),
                currentDiopters = current,
                settled = true,
                nowMs = now,
                maxDiopters = maxDiopters,
                settings = SoftwareFocusSettings(0.5f, speed),
            )
            target = update.targetDiopters
            current = target
        }
        return target
    }

    @Test
    fun `hill climb converges on the sharpness peak`() {
        val result = converge(peak = 6f, speed = 0.5f)
        assertTrue("expected near 6 D, was $result", abs(result - 6f) < 0.5f)
    }

    @Test
    fun `fast transition takes a larger first step`() {
        val engine = SoftwareFocusEngine()
        engine.reset(0f)
        val slow = engine.update(
            scoreAt(0f, 6f), 0f, true, 0, maxDiopters,
            SoftwareFocusSettings(0.5f, 0f),
        )
        engine.reset(0f)
        val fast = engine.update(
            scoreAt(0f, 6f), 0f, true, 0, maxDiopters,
            SoftwareFocusSettings(0.5f, 1f),
        )
        assertTrue(fast.targetDiopters > slow.targetDiopters)
    }

    @Test
    fun `flat scene does not hunt`() {
        val engine = SoftwareFocusEngine()
        engine.reset(3f)
        val update = engine.update(
            score = 0.0001f,
            currentDiopters = 3f,
            settled = true,
            nowMs = 0,
            maxDiopters = maxDiopters,
            settings = SoftwareFocusSettings(0.5f, 0.5f),
        )
        assertTrue(update.locked)
        assertEquals(3f, update.targetDiopters, 0.001f)
    }

    @Test
    fun `fixed focus lens holds`() {
        val engine = SoftwareFocusEngine()
        engine.reset(0f)
        val update = engine.update(
            score = 0.5f,
            currentDiopters = 0f,
            settled = true,
            nowMs = 0,
            maxDiopters = 0f,
            settings = SoftwareFocusSettings(0.5f, 0.5f),
        )
        assertTrue(update.locked)
        assertEquals(0f, update.targetDiopters, 0.001f)
    }

    /** Converge and return an engine locked near [peak]. */
    private fun lockedAt(peak: Float, sensitivity: Float, speed: Float): SoftwareFocusEngine {
        val engine = SoftwareFocusEngine()
        engine.reset(0f)
        var current = 0f
        var now = 0L
        repeat(600) {
            now += 100L
            val update = engine.update(
                scoreAt(current, peak),
                current,
                settled = true,
                nowMs = now,
                maxDiopters = maxDiopters,
                settings = SoftwareFocusSettings(sensitivity, speed),
            )
            current = update.targetDiopters
            if (update.locked && abs(current - peak) < 0.5f) return engine
        }
        error("engine did not lock near $peak, last $current")
    }

    @Test
    fun `high sensitivity starts a search after a shorter hold`() {
        val engine = lockedAt(peak = 5f, sensitivity = 0.9f, speed = 0.5f)
        var now = 0L
        var searching = false
        repeat(10) {
            now += 100L
            val update = engine.update(
                0.4f, 5f, true, now, maxDiopters,
                SoftwareFocusSettings(0.9f, 0.5f),
            )
            if (update.searching) searching = true
        }
        assertTrue(searching)
    }

    @Test
    fun `low sensitivity holds longer than high sensitivity`() {
        fun searchAfter(ms: Long, sensitivity: Float): Boolean {
            val engine = lockedAt(peak = 5f, sensitivity = sensitivity, speed = 0.5f)
            var now = 0L
            while (now < ms) {
                now += 100L
                val update = engine.update(
                    0.4f, 5f, true, now, maxDiopters,
                    SoftwareFocusSettings(sensitivity, 0.5f),
                )
                if (update.searching) return true
            }
            return false
        }
        assertFalse(searchAfter(900L, 0f))
        assertTrue(searchAfter(900L, 1f))
    }

    @Test
    fun `scenario presets stay normalized and round trip`() {
        for (scenario in FocusScenario.entries) {
            assertTrue(scenario.shiftSensitivity in 0f..1f)
            assertTrue(scenario.transitionSpeed in 0f..1f)
            assertEquals(scenario, FocusScenario.fromWire(scenario.wireValue))
        }
        assertEquals(FocusScenario.WEDDING, FocusScenario.fromWire("unknown"))
    }
}
