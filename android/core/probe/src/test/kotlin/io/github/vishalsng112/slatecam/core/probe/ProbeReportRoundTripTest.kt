package io.github.vishalsng112.slatecam.core.probe

import io.github.vishalsng112.slatecam.core.probe.model.ProbeReport
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

        // This fixture is a 1.1.0 report. Fields added in 1.2.0 must decode as
        // absent, not as a fabricated zero.
        assertEquals(null, original.displays.first().maxRefreshHz)
        assertEquals(null, original.verification.checks.firstOrNull()?.width)
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
        assertEquals("1.2.0", report.schemaVersion)

        // 1.2.0 additions: active vs maximum refresh, and a structured size.
        val display = report.displays.first()
        assertEquals(60, display.refreshHz)
        assertEquals(120, display.maxRefreshHz)

        val check = report.verification.checks.first { it.id == "hevc_main10_encode" }
        assertEquals("pass", check.status)
        assertEquals(3840, check.width)
        assertEquals(2160, check.height)

        // Exactly one codec is credited with the pass; the others were not tried.
        val passing = report.encoders.filter {
            it.mime == "video/hevc" && it.main10Verified == "pass"
        }
        assertEquals(1, passing.size)
        assertFalse(passing.first().main10Claimed)
    }

    @Test
    fun `verification promotes only supported presets at the encoded size`() {
        val report = ProbeRunner.json.decodeFromString<ProbeReport>(verified())
        val back = report.cameras.first { it.facing == "back" }

        // The encode ran at 3840x2160, which covers the UHD ten-bit presets.
        val uhd = back.presets.getValue("uhd30_10bit")
        assertTrue(uhd.supported)
        assertEquals("capability_only", uhd.basis)
        assertEquals("pass", uhd.verified)

        // Unsupported modes must never inherit a pass.
        assertFalse(back.presets.getValue("uhd60_10bit").supported)
        assertEquals("not_run", back.presets.getValue("uhd60_10bit").verified)

        // Other sizes were not exercised, so they stay unverified.
        assertEquals("not_run", back.presets.getValue("fhd30_10bit").verified)
    }

    @Test
    fun `pad 3 claims and verifies Main10 on android 17`() {
        val report = ProbeRunner.json.decodeFromString<ProbeReport>(sample("oneplus-pad-3-opd2415.json"))

        assertEquals("oneplus/op6190l1", report.device.deviceKey)
        assertEquals(37, report.device.sdk)
        assertEquals("cinema", report.tier.name)
        assertTrue(report.tier.missing.isEmpty())

        val hevc = report.encoders.filter { it.mime == "video/hevc" && it.hardware }
        assertTrue(hevc.isNotEmpty())
        assertTrue(hevc.all { it.main10Claimed })

        val check = report.verification.checks.first { it.id == "hevc_main10_encode" }
        assertEquals("pass", check.status)
        assertTrue(check.detail!!.contains("c2.qti.hevc.encoder"))
    }

    @Test
    fun `both vendors reach cinema with opposite claim behaviour`() {
        val poco = ProbeRunner.json.decodeFromString<ProbeReport>(verified())
        val pad = ProbeRunner.json.decodeFromString<ProbeReport>(sample("oneplus-pad-3-opd2415.json"))

        assertEquals("cinema", poco.tier.name)
        assertEquals("cinema", pad.tier.name)

        // MediaTek: verified but not advertised.
        assertFalse(poco.encoders.any { it.mime == "video/hevc" && it.hardware && it.main10Claimed })
        // Qualcomm: advertised and verified.
        assertTrue(pad.encoders.any { it.mime == "video/hevc" && it.hardware && it.main10Claimed })

        // Either way, the verdict comes from the encode, not the claim.
        assertTrue(poco.verification.ran && pad.verification.ran)
    }

    @Test
    fun `tablet camera is limited to 4K30 and has no high-speed modes`() {
        val report = ProbeRunner.json.decodeFromString<ProbeReport>(sample("oneplus-pad-3-opd2415.json"))
        val back = report.cameras.first { it.facing == "back" }

        assertTrue(back.presets.getValue("uhd24_10bit").supported)
        assertEquals("fps_out_of_range", back.presets.getValue("uhd60_8bit").reason)
        assertEquals("no_stream_config", back.presets.getValue("fhd120_8bit").reason)
    }

    @Test
    fun `unknown fields from a newer prober are ignored, not fatal`() {
        val root = ProbeRunner.json
            .parseToJsonElement(sample("oneplus-pad-3-opd2415.json"))
            .jsonObject

        val extended = JsonObject(
            root + mapOf(
                "future_root_field" to JsonPrimitive(1),
                "future_object" to buildJsonObject {
                    put("nested", 2)
                    put("also_unknown", true)
                },
            ),
        )

        val decoded = ProbeRunner.json.decodeFromString<ProbeReport>(extended.toString())
        assertEquals("oneplus/op6190l1", decoded.device.deviceKey)
        assertEquals("cinema", decoded.tier.name)
    }

    @Test
    fun `a report missing optional sections still decodes`() {
        val root = ProbeRunner.json
            .parseToJsonElement(sample("oneplus-pad-3-opd2415.json"))
            .jsonObject

        val withoutAudio = JsonObject(root - "audio")

        val decoded = ProbeRunner.json.decodeFromString<ProbeReport>(withoutAudio.toString())
        assertEquals(null, decoded.audio)
        assertEquals(2, decoded.cameras.size)
    }
}
