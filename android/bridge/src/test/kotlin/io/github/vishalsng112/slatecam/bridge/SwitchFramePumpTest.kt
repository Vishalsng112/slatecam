package io.github.vishalsng112.slatecam.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchFramePumpTest {
    private val pump = SwitchFramePump(frameIntervalMs = 33L)

    @Test
    fun `repeats while no camera frame arrives`() {
        pump.start(nowMs = 0L, frameCount = 10L)

        assertEquals(SwitchFramePump.Decision.REPEAT, pump.tick(33L, false, 10L))
        assertEquals(SwitchFramePump.Decision.REPEAT, pump.tick(66L, false, 10L))
    }

    @Test
    fun `does not duplicate when a new camera frame arrived`() {
        pump.start(nowMs = 0L, frameCount = 10L)

        assertEquals(SwitchFramePump.Decision.HOLD, pump.tick(33L, false, 11L))
        assertEquals(SwitchFramePump.Decision.REPEAT, pump.tick(66L, false, 11L))
    }

    @Test
    fun `stops after the new session has settled`() {
        pump.start(nowMs = 0L, frameCount = 10L)

        assertEquals(SwitchFramePump.Decision.HOLD, pump.tick(100L, true, 11L))
        assertEquals(SwitchFramePump.Decision.HOLD, pump.tick(133L, true, 12L))
        assertEquals(SwitchFramePump.Decision.DONE, pump.tick(166L, true, 13L))
        assertFalse(pump.isActive)
    }

    @Test
    fun `times out when the switch never becomes ready`() {
        pump.start(nowMs = 0L, frameCount = 10L)

        assertEquals(SwitchFramePump.Decision.DONE, pump.tick(2500L, false, 10L))
        assertFalse(pump.isActive)
    }

    @Test
    fun `cancel stops further repeats`() {
        pump.start(nowMs = 0L, frameCount = 10L)
        pump.cancel()

        assertEquals(SwitchFramePump.Decision.DONE, pump.tick(33L, false, 10L))
        assertFalse(pump.isActive)
    }

    @Test
    fun `restarting resets the baseline and deadline`() {
        pump.start(nowMs = 0L, frameCount = 10L)
        assertEquals(SwitchFramePump.Decision.REPEAT, pump.tick(33L, false, 10L))

        pump.start(nowMs = 2000L, frameCount = 42L)

        assertTrue(pump.isActive)
        assertEquals(SwitchFramePump.Decision.REPEAT, pump.tick(2033L, false, 42L))
        assertEquals(SwitchFramePump.Decision.DONE, pump.tick(4500L + 1, false, 42L))
    }
}
