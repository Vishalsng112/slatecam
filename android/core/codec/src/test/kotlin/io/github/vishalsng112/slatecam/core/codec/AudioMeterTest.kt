package io.github.vishalsng112.slatecam.core.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMeterTest {

    private fun pcm16(samples: IntArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample and 0xFF).toByte()
            bytes[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun stereo(left: Int, right: Int, frames: Int): ByteArray =
        pcm16(IntArray(frames * 2) { if (it % 2 == 0) left else right })

    @Test
    fun `silence sits on the meter floor`() {
        val level = AudioMeter.measure(stereo(0, 0, 512), 512 * 4, 2)

        assertEquals(AudioMeter.FLOOR_DB, level.peakDbL, 0f)
        assertEquals(AudioMeter.FLOOR_DB, level.peakDbR, 0f)
        assertEquals(AudioMeter.FLOOR_DB, level.rmsDbL, 0f)
        assertFalse(level.clipped)
    }

    @Test
    fun `half scale reads minus six dBFS`() {
        val level = AudioMeter.measure(stereo(16_384, 16_384, 512), 512 * 4, 2)

        assertEquals(-6.02f, level.peakDbL, 0.05f)
        assertEquals(-6.02f, level.peakDbR, 0.05f)
        assertEquals(-6.02f, level.rmsDbL, 0.05f)
        assertFalse(level.clipped)
    }

    @Test
    fun `full scale reads zero and reports a clip`() {
        val level = AudioMeter.measure(stereo(32_767, -32_768, 512), 512 * 4, 2)

        assertEquals(0f, level.peakDbL, 0.02f)
        assertEquals(0f, level.peakDbR, 0.02f)
        assertTrue(level.clipped)
    }

    @Test
    fun `mono input is shown on both channels`() {
        val mono = pcm16(IntArray(256) { 8_192 })
        val level = AudioMeter.measure(mono, mono.size, 1)

        assertEquals(level.peakDbL, level.peakDbR, 0f)
        assertEquals(-12.04f, level.peakDbL, 0.05f)
    }

    @Test
    fun `gain lifts the signal and flags clipping`() {
        val buffer = stereo(8_192, 8_192, 256)
        val level = AudioMeter.process(buffer, buffer.size, 2, 12f)

        // 8192 * 3.981 = 32610, just below full scale.
        assertFalse(level.clipped)
        assertEquals(-0.04f, level.peakDbL, 0.1f)
    }

    @Test
    fun `gain that exceeds full scale clamps and clips`() {
        val buffer = stereo(16_384, 16_384, 256)
        val level = AudioMeter.process(buffer, buffer.size, 2, 12f)

        assertTrue(level.clipped)
        assertEquals(0f, level.peakDbL, 0.02f)
        // Clamped to 32767, not wrapped to a negative sample.
        assertEquals(32_767, readSample(buffer, 0))
    }

    @Test
    fun `negative gain lowers the measured peak`() {
        val buffer = stereo(16_384, 16_384, 256)
        val level = AudioMeter.process(buffer, buffer.size, 2, -6f)

        assertEquals(-12.04f, level.peakDbL, 0.1f)
        assertFalse(level.clipped)
    }

    @Test
    fun `zero gain still reports an already clipped sample`() {
        val clipped = AudioMeter.applyGain(stereo(32_767, 0, 32), 32 * 4, 0f)
        val quiet = AudioMeter.applyGain(stereo(1_000, 0, 32), 32 * 4, 0f)

        assertTrue(clipped)
        assertFalse(quiet)
    }

    @Test
    fun `partial buffers ignore an incomplete trailing sample`() {
        val bytes = pcm16(intArrayOf(1_000, 1_000, 1_000))
        val level = AudioMeter.measure(bytes, bytes.size - 1, 2)

        assertTrue(level.peakDbL.isFinite())
        assertTrue(level.rmsDbL.isFinite())
    }

    private fun readSample(pcm: ByteArray, index: Int): Int {
        val value = (pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)
        return if (value >= 0x8000) value - 0x10000 else value
    }
}
