package io.github.vishalsng112.slatecam.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroAlignerTest {

    private fun samples(count: Int, stepNs: Long, rate: Float): List<GyroSample> =
        (0 until count).map { index ->
            GyroSample(
                timestampNs = index * stepNs,
                x = rate,
                y = 0f,
                z = 0f,
            )
        }

    @Test
    fun `constant rate integrates to rate times interval`() {
        // 1 rad/s, frames every 100 ms -> 0.1 rad per frame
        val frames = longArrayOf(0, 100_000_000, 200_000_000, 300_000_000)
        val rotations = GyroAligner.align(frames, samples(31, 10_000_000, 1f))

        assertEquals(3, rotations.size)
        for (rotation in rotations) {
            assertEquals(0.1f, rotation.rx, 0.005f)
            assertEquals(0f, rotation.ry, 0f)
        }
    }

    @Test
    fun `rotation is zero without samples in the interval`() {
        val frames = longArrayOf(0, 100_000_000)
        val rotations = GyroAligner.align(frames, emptyList())
        assertTrue(rotations.isEmpty())
    }

    @Test
    fun `single frame yields no rotations`() {
        assertTrue(GyroAligner.align(longArrayOf(0), samples(10, 1_000_000, 1f)).isEmpty())
    }

    @Test
    fun `magnitude reflects all axes`() {
        val frames = longArrayOf(0, 1_000_000_000)
        val rotations = GyroAligner.align(
            frames,
            listOf(GyroSample(0, 0f, 0f, 1f)),
        )
        assertEquals(1, rotations.size)
        assertEquals(1f, rotations[0].magnitude(), 0.001f)
    }
}
