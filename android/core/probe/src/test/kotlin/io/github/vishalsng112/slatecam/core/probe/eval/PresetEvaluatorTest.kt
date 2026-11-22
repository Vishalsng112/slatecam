package io.github.vishalsng112.slatecam.core.probe.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetEvaluatorTest {

    private val uhd24TenBit = PresetCatalog.v1.first { it.id == "uhd24_10bit" }
    private val uhd30EightBit = PresetCatalog.v1.first { it.id == "uhd30_8bit" }
    private val fhd120EightBit = PresetCatalog.v1.first { it.id == "fhd120_8bit" }

    private fun uhdAt30Fps() = StreamEntry(
        width = 3840,
        height = 2160,
        format = StreamFormat.PRIVATE,
        minFrameDurationNs = 33_333_333L,
    )

    @Test
    fun `8-bit UHD at 30fps supports 24fps`() {
        val evaluator = PresetEvaluator(listOf(uhdAt30Fps()), emptyList(), tenBitClaimed = false)
        val verdict = evaluator.evaluate(Preset("uhd24_8bit", 3840, 2160, 24, false))
        assertTrue(verdict.supported)
        assertEquals("stream_map", verdict.basis)
    }

    @Test
    fun `8-bit UHD at 30fps rejects 60fps`() {
        val evaluator = PresetEvaluator(listOf(uhdAt30Fps()), emptyList(), tenBitClaimed = false)
        val verdict = evaluator.evaluate(Preset("uhd60_8bit", 3840, 2160, 60, false))
        assertFalse(verdict.supported)
        assertEquals("fps_out_of_range", verdict.reason)
    }

    @Test
    fun `missing size is reported as no_stream_config`() {
        val evaluator = PresetEvaluator(listOf(uhdAt30Fps()), emptyList(), tenBitClaimed = true)
        val verdict = evaluator.evaluate(Preset("fhd60_8bit", 1920, 1080, 60, false))
        assertFalse(verdict.supported)
        assertEquals("no_stream_config", verdict.reason)
    }

    @Test
    fun `10-bit without the capability is unsupported`() {
        val evaluator = PresetEvaluator(listOf(uhdAt30Fps()), emptyList(), tenBitClaimed = false)
        val verdict = evaluator.evaluate(uhd24TenBit)
        assertFalse(verdict.supported)
        assertEquals("unsupported_format", verdict.reason)
    }

    @Test
    fun `10-bit with the capability is claimed but never verified`() {
        val evaluator = PresetEvaluator(listOf(uhdAt30Fps()), emptyList(), tenBitClaimed = true)
        val verdict = evaluator.evaluate(uhd24TenBit)
        assertTrue(verdict.supported)
        assertEquals("capability_only", verdict.basis)
        assertEquals("not_run", verdict.verified)
        assertEquals(listOf("hlg10"), verdict.profiles)
    }

    @Test
    fun `high speed stream satisfies 120fps`() {
        val evaluator = PresetEvaluator(
            streams = emptyList(),
            highSpeed = listOf(HighSpeedEntry(1920, 1080, 120)),
            tenBitClaimed = false,
        )
        val verdict = evaluator.evaluate(fhd120EightBit)
        assertTrue(verdict.supported)
    }

    @Test
    fun `10-bit high speed is rejected`() {
        val evaluator = PresetEvaluator(
            streams = emptyList(),
            highSpeed = listOf(HighSpeedEntry(1920, 1080, 120)),
            tenBitClaimed = true,
        )
        val verdict = evaluator.evaluate(Preset("fhd120_10bit", 1920, 1080, 120, true))
        assertFalse(verdict.supported)
        assertEquals("unsupported_format", verdict.reason)
    }

    @Test
    fun `8-bit preset still works when ten-bit is claimed`() {
        val evaluator = PresetEvaluator(listOf(uhdAt30Fps()), emptyList(), tenBitClaimed = true)
        assertTrue(evaluator.evaluate(uhd30EightBit).supported)
    }
}
