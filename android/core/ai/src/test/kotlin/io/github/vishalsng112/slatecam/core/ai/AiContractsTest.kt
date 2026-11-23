package io.github.vishalsng112.slatecam.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiContractsTest {

    @Test
    fun `frame gate accepts the first frame`() {
        val gate = AiFrameGate(10)
        assertTrue(gate.accept(0L))
    }

    @Test
    fun `frame gate enforces the frame-rate cap`() {
        val gate = AiFrameGate(10)
        assertTrue(gate.accept(0L))
        assertFalse(gate.accept(50_000_000L))
        assertFalse(gate.accept(99_999_999L))
        assertTrue(gate.accept(100_000_000L))
        assertFalse(gate.accept(150_000_000L))
        assertTrue(gate.accept(200_000_000L))
    }

    @Test
    fun `duplicate and backwards timestamps are rejected`() {
        val gate = AiFrameGate(30)
        assertTrue(gate.accept(1_000L))
        assertFalse(gate.accept(1_000L))
        assertFalse(gate.accept(999L))
    }

    @Test
    fun `reset allows an immediate frame`() {
        val gate = AiFrameGate(10)
        assertTrue(gate.accept(0L))
        assertFalse(gate.accept(1L))
        gate.reset()
        assertTrue(gate.accept(1L))
    }

    @Test
    fun `frame gate rejects a non-positive cap`() {
        assertThrows(IllegalArgumentException::class.java) { AiFrameGate(0) }
        assertThrows(IllegalArgumentException::class.java) { AiFrameGate(-1) }
    }

    @Test
    fun `AI is disabled by default`() {
        val config = AiConfig()
        assertFalse(config.enabled)
        assertEquals(AiConfig.DEFAULT_MAX_FPS, config.maxFramesPerSecond)
        assertEquals(setOf(AiFeature.SUBJECT_TRACKING, AiFeature.FOCUS_ASSIST), config.features)
    }

    @Test
    fun `config rejects a non-positive frame cap`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiConfig(maxFramesPerSecond = 0)
        }
    }

    @Test
    fun `normalised rect rejects invalid bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedRect(left = 0.8f, top = 0.1f, right = 0.2f, bottom = 0.9f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedRect(left = -0.1f, top = 0.1f, right = 0.2f, bottom = 0.9f)
        }
    }

    @Test
    fun `no-op engine reports unavailable and is inert`() {
        val engine = NoOpAiEngine()
        assertFalse(engine.capabilities.available)
        assertTrue(engine.capabilities.features.isEmpty())

        val config = AiConfig(enabled = true, maxFramesPerSecond = 5)
        engine.configure(config)
        assertEquals(config, engine.currentConfig())
        engine.setListener { error("no result should be delivered") }
        engine.submit(
            AiFrame(
                textureId = 7,
                width = 640,
                height = 360,
                timestampNs = 1_000L,
            ),
        )
        engine.reset()
        engine.close()
    }
}
