package io.github.vishalsng112.slatecam.core.probe

import io.github.vishalsng112.slatecam.core.probe.model.ProbeReport
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests against reports captured from a real POCO X6 Pro 5G
 * (MT6897, Android 16) on 2026-11-23. Two fixtures:
 *
 * - `...json` — probe before verification: tier `standard`, no Main10 claim.
 * - `...-verified.json` — after the verification encode: tier `cinema`.
 *
 * The second fixture is the load-bearing one: it proves a device can be
 * cinema-capable while advertising no Main10 profile at all.
 */
class ProbeReportRoundTripTest {

    private fun sample(name: String): String {
        val stream = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("samples/$name"),
        ) { "sample report missing from test resources: $name" }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun unverified() = sample("poco-x6-pro-2311DRK48I.json")
    private fun verified() = sample("poco-x6-pro-2311DRK48I-verified.json")

    @Test
    fun `real device report round-trips`() {
        val original = ProbeRunner.json.decodeFromString<ProbeReport>(unverified())

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
        val report = ProbeRunner.json.decodeFromString<ProbeReport>(unverified())
        val back = report.cameras.first { it.facing == "back" }

        assertTrue(back.dynamicRange.tenBitStreams)
        assertTrue(back.presets.getValue("uhd30_10bit").supported)
        assertEquals("capability_only", back.presets.getValue("uhd30_10bit").basis)

        val hevc = report.encoders.filter { it.mime == "video/hevc" && it.hardware }
        assertTrue(hevc.isNotEmpty())
        assertFalse(hevc.any { it.main10Claimed })
        assertEquals("standard", report.tier.name)
        assertTrue(report.tier.missing.contains("hevc_main10_encoder"))
        assertFalse(report.verification.ran)
    }

    @Test
    fun `4K60 is correctly rejected on the poco`() {
        val report = ProbeRunner.json.decodeFromString<ProbeReport>(unverified())
        val back = report.cameras.first { it.facing == "back" }
        val uhd60 = back.presets.getValue("uhd60_8bit")

        assertFalse(uhd60.supported)
        assertEquals("fps_out_of_range", uhd60.reason)
    }

    @Test
    fun `verified encode reaches cinema without an advertised Main10 profile`() {
        val report = ProbeRunner.json.decodeFromString<ProbeReport>(verified())

        assertEquals("cinema", report.tier.name)
        assertTrue(report.tier.missing.isEmpty())
        assertTrue(report.tier.unverified.isEmpty())
        assertTrue(report.verification.ran)

        val check = report.verification.checks.first { it.id == "hevc_main10_encode" }
        assertEquals("pass", check.status)

        // Exactly one codec is credited with the pass; the others were not tried.
        val passing = report.encoders.filter {
            it.mime == "video/hevc" && it.main10Verified == "pass"
        }
        assertEquals(1, passing.size)
        assertFalse(passing.first().main10Claimed)
    }

    @Test
    fun `verification promotes ten-bit presets from capability only to pass`() {
        val report = ProbeRunner.json.decodeFromString<ProbeReport>(verified())
        val back = report.cameras.first { it.facing == "back" }
        val preset = back.presets.getValue("uhd30_10bit")

        assertTrue(preset.supported)
        assertEquals("capability_only", preset.basis)
        assertEquals("pass", preset.verified)
    }
}
