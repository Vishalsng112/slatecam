package io.github.vishalsng112.slatecam.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureMeterTest {

    private val width = 10
    private val height = 10

    @Test
    fun `uniform scene measures the same value in every mode`() {
        val grid = FloatArray(width * height) { 0.45f }
        for (mode in MeteringMode.entries) {
            val sample = ExposureMeter.measure(mode, grid, width, height)!!
            assertEquals(0.45f, sample.measuredLuma, 0.001f)
        }
        assertEquals(
            ExposureMeter.NEUTRAL_TARGET,
            ExposureMeter.measure(MeteringMode.MULTI, grid, width, height)!!.targetLuma,
            0.001f,
        )
        // A flat scene has no highlight to protect; do not overexpose it.
        val flatHighlight = ExposureMeter.measure(MeteringMode.HIGHLIGHT, grid, width, height)!!
        assertEquals(0.45f, flatHighlight.measuredLuma, 0.001f)
        assertEquals(ExposureMeter.NEUTRAL_TARGET, flatHighlight.targetLuma, 0.001f)
    }

    @Test
    fun `spot follows the centre while average follows the whole frame`() {
        val grid = FloatArray(width * height) { 0.10f }
        for (y in 4..5) for (x in 4..5) grid[y * width + x] = 0.90f

        val spot = ExposureMeter.measure(MeteringMode.SPOT, grid, width, height)!!
        val center = ExposureMeter.measure(MeteringMode.CENTER, grid, width, height)!!
        val average = ExposureMeter.measure(MeteringMode.AVERAGE, grid, width, height)!!

        assertTrue("spot=${spot.measuredLuma} center=${center.measuredLuma}", spot.measuredLuma > center.measuredLuma)
        assertTrue("center=${center.measuredLuma} average=${average.measuredLuma}", center.measuredLuma > average.measuredLuma)
        assertTrue("spot should strongly favour the centre", spot.measuredLuma > 0.4f)
        assertTrue(average.measuredLuma < 0.2f)
    }

    @Test
    fun `spot ignores a bright corner`() {
        val grid = FloatArray(width * height) { 0.20f }
        for (y in 0..1) for (x in 0..1) grid[y * width + x] = 1.0f

        val spot = ExposureMeter.measure(MeteringMode.SPOT, grid, width, height)!!
        val average = ExposureMeter.measure(MeteringMode.AVERAGE, grid, width, height)!!

        assertEquals(0.20f, spot.measuredLuma, 0.01f)
        assertTrue(average.measuredLuma > spot.measuredLuma)
    }

    @Test
    fun `highlight metering follows the upper percentile`() {
        val grid = FloatArray(width * height) { 0.20f }
        // 30% of the frame is a clipped highlight, enough to reach p90.
        for (y in 0 until 3) for (x in 0 until width) grid[y * width + x] = 1.0f

        val highlight = ExposureMeter.measure(MeteringMode.HIGHLIGHT, grid, width, height)!!
        val average = ExposureMeter.measure(MeteringMode.AVERAGE, grid, width, height)!!

        assertEquals(1.0f, highlight.measuredLuma, 0.001f)
        assertEquals(ExposureMeter.HIGHLIGHT_TARGET, highlight.targetLuma, 0.001f)
        assertTrue(highlight.measuredLuma > average.measuredLuma)
    }

    @Test
    fun `multi rejects an extreme bright band`() {
        val grid = FloatArray(width * height) { 0.30f }
        // A small window highlight should not dominate matrix metering.
        for (x in 0 until width) grid[x] = 1.0f

        val multi = ExposureMeter.measure(MeteringMode.MULTI, grid, width, height)!!
        val average = ExposureMeter.measure(MeteringMode.AVERAGE, grid, width, height)!!

        assertTrue(multi.measuredLuma < average.measuredLuma)
    }

    @Test
    fun `empty or invalid grids return null`() {
        assertNull(ExposureMeter.measure(MeteringMode.MULTI, FloatArray(0), 0, 0))
        assertNull(ExposureMeter.measure(MeteringMode.MULTI, FloatArray(4), 10, 10))
    }

    @Test
    fun `metering modes round trip and reject unknown values`() {
        assertEquals(MeteringMode.SPOT, MeteringMode.fromWire("spot"))
        assertEquals(MeteringMode.HIGHLIGHT, MeteringMode.fromWire("Highlight"))
        assertEquals(MeteringMode.MULTI, MeteringMode.fromWire("pattern"))
        assertEquals(
            listOf("multi", "center", "spot", "average", "highlight"),
            MeteringMode.wireValues,
        )
    }
}
