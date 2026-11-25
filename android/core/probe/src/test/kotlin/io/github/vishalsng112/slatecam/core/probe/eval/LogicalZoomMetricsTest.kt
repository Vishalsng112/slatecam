package io.github.vishalsng112.slatecam.core.probe.eval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicalZoomMetricsTest {

    private fun sample(
        index: Int,
        exposure: Long = 10_000_000L,
        iso: Int = 100,
        wbR: Float = 2f,
        wbG: Float = 1f,
        wbB: Float = 1.5f,
        physical: String = if (index < 25) "2" else "0",
    ) = LogicalZoomMetrics.Sample(
        timestampNs = index * 33_333_333L,
        exposureNs = exposure,
        iso = iso,
        wbRed = wbR,
        wbGreen = wbG,
        wbBlue = wbB,
        activePhysicalId = physical,
    )

    @Test
    fun `steady stream across a handoff passes`() {
        val result = LogicalZoomMetrics.analyze((0 until 60).map { sample(it) })

        assertTrue(result.reason, result.pass)
        assertTrue(result.physicalIds.containsAll(listOf("2", "0")))
        assertTrue(result.maxGapMs < 100)
    }

    @Test
    fun `a timestamp gap fails`() {
        val samples = (0 until 60).map { sample(it) }.toMutableList()
        samples[30] = samples[30].copy(timestampNs = samples[30].timestampNs + 2_000_000_000L)

        val result = LogicalZoomMetrics.analyze(samples)

        assertFalse(result.pass)
        assertTrue(result.reason.contains("frame gap"))
    }

    @Test
    fun `a large exposure jump fails`() {
        val samples = (0 until 60).map { index ->
            sample(index, exposure = if (index < 30) 10_000_000L else 40_000_000L)
        }

        val result = LogicalZoomMetrics.analyze(samples)

        assertFalse(result.pass)
        assertTrue(result.reason.contains("exposure jump"))
    }

    @Test
    fun `a large white balance jump fails`() {
        val samples = (0 until 60).map { index ->
            sample(index, wbR = if (index < 30) 2f else 4f)
        }

        val result = LogicalZoomMetrics.analyze(samples)

        assertFalse(result.pass)
        assertTrue(result.reason.contains("WB jump"))
    }

    @Test
    fun `a single physical lens fails`() {
        val result = LogicalZoomMetrics.analyze(
            (0 until 60).map { sample(it, physical = "0") },
        )

        assertFalse(result.pass)
        assertTrue(result.reason.contains("did not change"))
    }

    @Test
    fun `too few frames fails`() {
        val result = LogicalZoomMetrics.analyze((0 until 10).map { sample(it) })

        assertFalse(result.pass)
        assertTrue(result.reason.contains("frames"))
    }
}
