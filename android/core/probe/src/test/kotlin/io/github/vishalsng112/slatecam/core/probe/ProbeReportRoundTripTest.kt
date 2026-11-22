package io.github.vishalsng112.slatecam.core.probe

import io.github.vishalsng112.slatecam.core.probe.model.ProbeReport
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden test: a real report captured from a POCO X6 Pro 5G on 2026-11-23 must
 * decode, re-encode, and decode again without loss. This is what keeps the
 * schema honest against vendor HAL output rather than against our assumptions.
 */
class ProbeReportRoundTripTest {

    private fun sample(): String {
        val stream = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("samples/poco-x6-pro-2311DRK48I.json"),
        ) { "sample report missing from test resources" }
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun `real device report round-trips`() {
        val original = ProbeRunner.json.decodeFromString<ProbeReport>(sample())

        assertEquals("slatecam.probe", original.schema)
        assertEquals("1.1.0", original.schemaVersion)
        assertEquals("xiaomi/duchamp", original.device.deviceKey)
        assertEquals(36, original.device.sdk)
        assertEquals(2, original.cameras.size)

        val encoded = ProbeRunner.json.encodeToString(original)
        val decoded = ProbeRunner.json.decodeFromString<ProbeReport>(encoded)
        assertEquals(original.tier, decoded.tier)
        assertEquals(original.cameras.map { it.id }, decoded.cameras.map { it.id })
    }

    @Test
    fun `poco exposes ten-bit capture but no Main10 encoder claim`() {
        val report = ProbeRunner.json.decodeFromString<ProbeReport>(sample())
        val back = report.cameras.first { it.facing == "back" }

        assertTrue(back.dynamicRange.tenBitStreams)
        assertTrue(back.presets.getValue("uhd30_10bit").supported)
        assertEquals("capability_only", back.presets.getValue("uhd30_10bit").basis)

        val hevc = report.encoders.filter { it.mime == "video/hevc" && it.hardware }
        assertTrue(hevc.isNotEmpty())
        assertFalse(hevc.any { it.main10Claimed })
        assertEquals("standard", report.tier.name)
        assertTrue(report.tier.missing.contains("hevc_main10_encoder"))
    }

    @Test
    fun `4K60 is correctly rejected on the poco`() {
        val report = ProbeRunner.json.decodeFromString<ProbeReport>(sample())
        val back = report.cameras.first { it.facing == "back" }
        val uhd60 = back.presets.getValue("uhd60_8bit")

        assertFalse(uhd60.supported)
        assertEquals("fps_out_of_range", uhd60.reason)
    }
}
