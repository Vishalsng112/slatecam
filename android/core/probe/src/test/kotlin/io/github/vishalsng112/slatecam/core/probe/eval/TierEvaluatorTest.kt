package io.github.vishalsng112.slatecam.core.probe.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TierEvaluatorTest {

    private fun cinemaDevice(verified: String) = TierInput(
        hardwareLevel = "full",
        manualSensor = true,
        manualPostProcessing = true,
        tenBit = true,
        hardwareHevc = true,
        hardwareHevcMain10 = true,
        main10Verified = verified,
    )

    @Test
    fun `verified capability is the cinema tier`() {
        val verdict = TierEvaluator.evaluate(cinemaDevice("pass"))
        assertEquals("cinema", verdict.name)
        assertTrue(verdict.missing.isEmpty())
        assertTrue(verdict.unverified.isEmpty())
    }

    @Test
    fun `unverified capability is cinema_claimed, not cinema`() {
        val verdict = TierEvaluator.evaluate(cinemaDevice("not_run"))
        assertEquals("cinema_claimed", verdict.name)
        assertEquals(listOf("hevc_main10_encode"), verdict.unverified)
        assertTrue(verdict.reasons.contains("ten_bit_claimed_not_verified"))
    }

    @Test
    fun `failed verification keeps the device out of the cinema tier`() {
        val verdict = TierEvaluator.evaluate(cinemaDevice("fail"))
        assertEquals("cinema_claimed", verdict.name)
        assertTrue(verdict.reasons.contains("ten_bit_verification_failed"))
    }

    @Test
    fun `missing ten-bit drops to standard`() {
        val verdict = TierEvaluator.evaluate(
            cinemaDevice("not_run").copy(tenBit = false, hardwareHevcMain10 = false),
        )
        assertEquals("standard", verdict.name)
        assertTrue(verdict.missing.contains("dynamic_range_ten_bit"))
        assertTrue(verdict.missing.contains("hevc_main10_encoder"))
    }

    @Test
    fun `limited device without manual sensor is basic`() {
        val verdict = TierEvaluator.evaluate(
            TierInput(
                hardwareLevel = "limited",
                manualSensor = false,
                manualPostProcessing = false,
                tenBit = false,
                hardwareHevc = true,
                hardwareHevcMain10 = false,
                main10Verified = "not_run",
            ),
        )
        assertEquals("basic", verdict.name)
        assertTrue(verdict.missing.contains("manual_sensor"))
    }

    @Test
    fun `level 3 counts as a full-level camera`() {
        val verdict = TierEvaluator.evaluate(cinemaDevice("pass").copy(hardwareLevel = "level_3"))
        assertEquals("cinema", verdict.name)
    }

    @Test
    fun `verified Main10 without an advertised profile still reaches cinema`() {
        // MediaTek HALs commonly omit Main10 from CodecCapabilities while the
        // encoder can produce it. Verification must be able to override the claim.
        val verdict = TierEvaluator.evaluate(
            cinemaDevice("pass").copy(hardwareHevcMain10 = false),
        )
        assertEquals("cinema", verdict.name)
        assertTrue(verdict.missing.isEmpty())
    }

    @Test
    fun `unverified Main10 without an advertised profile stays out of cinema`() {
        val verdict = TierEvaluator.evaluate(
            cinemaDevice("not_run").copy(hardwareHevcMain10 = false, hardwareHevc = true),
        )
        assertEquals("standard", verdict.name)
        assertTrue(verdict.missing.contains("hevc_main10_encoder"))
    }
}
