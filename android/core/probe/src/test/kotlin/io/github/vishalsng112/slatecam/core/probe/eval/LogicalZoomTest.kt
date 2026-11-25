package io.github.vishalsng112.slatecam.core.probe.eval

import io.github.vishalsng112.slatecam.core.probe.model.FloatRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicalZoomTest {

    private val range = FloatRange(0.5f, 10f)

    private fun lens(
        id: String,
        role: String,
        ratio: Double?,
        confidence: String = "high",
    ) = LogicalZoom.PhysicalLens(id, role, ratio, confidence)

    @Test
    fun `logical camera with wide normal and tele is eligible`() {
        val info = LogicalZoom.evaluate(
            logicalMultiCamera = true,
            physicalIds = listOf("0", "1", "2"),
            lenses = listOf(
                lens("0", "ultrawide", 0.6),
                lens("1", "default", 1.0),
                lens("2", "tele", 3.0),
            ),
            ratioRange = range,
        )

        assertTrue(info.handoffEligible)
        assertEquals("", info.reason)
        assertEquals(listOf(0.6, 1.0, 3.0), info.stops.map { it.ratio })
        assertEquals(
            listOf("ultrawide", "default", "tele"),
            info.stops.map { it.role },
        )
        assertTrue(info.uncoveredRatios.isEmpty())
    }

    @Test
    fun `non logical camera is rejected with a reason`() {
        val info = LogicalZoom.evaluate(
            logicalMultiCamera = false,
            physicalIds = listOf("0", "1"),
            lenses = listOf(lens("0", "ultrawide", 0.6), lens("1", "default", 1.0)),
            ratioRange = range,
        )

        assertFalse(info.handoffEligible)
        assertEquals("not_logical_multi_camera", info.reason)
        assertTrue(info.stops.isEmpty())
    }

    @Test
    fun `zoom range above one is not an ultra-wide handoff candidate`() {
        val info = LogicalZoom.evaluate(
            logicalMultiCamera = true,
            physicalIds = listOf("0", "1"),
            lenses = listOf(lens("0", "default", 1.0), lens("1", "tele", 2.0)),
            ratioRange = FloatRange(1f, 10f),
        )

        assertFalse(info.handoffEligible)
        assertEquals("zoom_range_does_not_cover_ultrawide", info.reason)
    }

    @Test
    fun `reports unreachable physical lenses separately`() {
        val info = LogicalZoom.evaluate(
            logicalMultiCamera = true,
            physicalIds = listOf("0", "1", "2", "3"),
            lenses = listOf(
                lens("0", "ultrawide", 0.6),
                lens("1", "default", 1.0),
                lens("2", "tele", 3.0),
                lens("3", "tele", 5.0),
            ),
            ratioRange = FloatRange(0.5f, 4f),
        )

        assertTrue(info.handoffEligible)
        assertEquals(listOf(0.6, 1.0, 3.0), info.stops.map { it.ratio })
        assertEquals(listOf(5.0), info.uncoveredRatios)
    }

    @Test
    fun `duplicate ratios keep the higher confidence stop`() {
        val info = LogicalZoom.evaluate(
            logicalMultiCamera = true,
            physicalIds = listOf("0", "1", "2"),
            lenses = listOf(
                lens("0", "ultrawide", 0.6),
                lens("1", "default", 1.0, confidence = "low"),
                lens("2", "default", 1.0, confidence = "high"),
            ),
            ratioRange = range,
        )

        assertEquals(2, info.stops.size)
        val default = info.stops.first { it.ratio == 1.0 }
        assertEquals("2", default.physicalId)
        assertEquals("high", default.roleConfidence)
        assertTrue(info.handoffEligible)
    }

    @Test
    fun `default stop is synthesised when physical ratios are missing`() {
        val info = LogicalZoom.evaluate(
            logicalMultiCamera = true,
            physicalIds = listOf("0", "1"),
            lenses = listOf(
                lens("0", "unknown", null),
                lens("1", "unknown", null),
            ),
            ratioRange = range,
        )

        assertEquals(1, info.stops.size)
        assertEquals(1.0, info.stops.first().ratio, 0.0001)
        assertEquals("logical_default", info.stops.first().basis)
        assertEquals("", info.stops.first().physicalId)
        assertFalse(info.handoffEligible)
        assertEquals("fewer_than_two_reachable_stops", info.reason)
    }

    @Test
    fun `one reachable stop is not eligible`() {
        val info = LogicalZoom.evaluate(
            logicalMultiCamera = true,
            physicalIds = listOf("0", "1"),
            lenses = listOf(
                lens("0", "default", 1.0),
                lens("1", "unknown", null),
            ),
            ratioRange = range,
        )

        assertEquals(listOf(1.0), info.stops.map { it.ratio })
        assertFalse(info.handoffEligible)
        assertEquals("fewer_than_two_reachable_stops", info.reason)
    }

    @Test
    fun `missing ratio range is rejected`() {
        val info = LogicalZoom.evaluate(
            logicalMultiCamera = true,
            physicalIds = listOf("0", "1"),
            lenses = listOf(lens("0", "ultrawide", 0.6), lens("1", "default", 1.0)),
            ratioRange = null,
        )

        assertFalse(info.handoffEligible)
        assertEquals("no_zoom_ratio_range", info.reason)
    }
}
