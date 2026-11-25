package io.github.vishalsng112.slatecam.core.device

import io.github.vishalsng112.slatecam.core.probe.model.CameraInfo
import io.github.vishalsng112.slatecam.core.probe.model.DeviceInfo
import io.github.vishalsng112.slatecam.core.probe.model.DynamicRangeInfo
import io.github.vishalsng112.slatecam.core.probe.model.FloatRange
import io.github.vishalsng112.slatecam.core.probe.model.LogicalZoomInfo
import io.github.vishalsng112.slatecam.core.probe.model.LogicalZoomStop
import io.github.vishalsng112.slatecam.core.probe.model.PresetInfo
import io.github.vishalsng112.slatecam.core.probe.model.ProbeReport
import io.github.vishalsng112.slatecam.core.probe.model.ReportMeta
import io.github.vishalsng112.slatecam.core.probe.model.SystemInfo
import io.github.vishalsng112.slatecam.core.probe.model.TierVerdict
import io.github.vishalsng112.slatecam.core.probe.model.Verification
import io.github.vishalsng112.slatecam.core.probe.model.VerificationCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityGatesTest {

    private val identity = DeviceIdentity(
        deviceKey = "oneplus/op6190l1",
        fingerprint = "OnePlus/OPD2415IN/OP6190L1:17/AP3A.240617.008/V.R4T3.30012fe",
        sdk = 37,
        manufacturer = "OnePlus",
        brand = "OnePlus",
        model = "OPD2415",
    )

    @Test
    fun `verified preset at the recorded size is available`() {
        val gate = CapabilityGates.tenBitRecording(
            identity = identity,
            quirks = emptyList(),
            report = report(
                deviceKey = identity.deviceKey,
                tier = "cinema",
                verified = mapOf("fhd30_10bit" to "pass"),
            ),
            cameraId = "0",
            width = 1920,
            height = 1080,
            fps = 30,
        )

        assertTrue(gate.available)
        assertTrue(gate.reason.contains("fhd30_10bit"))
    }

    @Test
    fun `a verdict covers only the size that was verified`() {
        val gate = CapabilityGates.tenBitRecording(
            identity = identity,
            quirks = emptyList(),
            report = report(
                deviceKey = identity.deviceKey,
                tier = "cinema",
                verified = mapOf(
                    "uhd30_10bit" to "pass",
                    "fhd30_10bit" to "not_run",
                ),
            ),
            cameraId = "0",
            width = 1920,
            height = 1080,
            fps = 30,
        )

        assertFalse(gate.available)
        assertTrue(gate.reason.contains("not verified"))
    }

    @Test
    fun `a quirk can withhold a verified capability`() {
        val quirk = QuirkEntry(
            id = "vendor-force-8bit",
            match = QuirkMatch(deviceKey = identity.deviceKey),
            issue = "ten_bit_encode_claim_unverified",
            action = QuirkActions.FORCE_8BIT,
            reason = "encoder writes 8-bit Main",
        )

        val gate = CapabilityGates.tenBitRecording(
            identity = identity,
            quirks = listOf(quirk),
            report = report(
                deviceKey = identity.deviceKey,
                tier = "cinema",
                verified = mapOf("fhd30_10bit" to "pass"),
            ),
            cameraId = "0",
            width = 1920,
            height = 1080,
            fps = 30,
        )

        assertFalse(gate.available)
        assertTrue(gate.reason.contains("vendor-force-8bit"))
    }

    @Test
    fun `a report from another device is never used`() {
        val gate = CapabilityGates.tenBitRecording(
            identity = identity,
            quirks = emptyList(),
            report = report(
                deviceKey = "xiaomi/duchamp",
                tier = "cinema",
                verified = mapOf("fhd30_10bit" to "pass"),
            ),
            cameraId = "0",
            width = 1920,
            height = 1080,
            fps = 30,
        )

        assertFalse(gate.available)
        assertTrue(gate.reason.contains("xiaomi/duchamp"))
    }

    @Test
    fun `a device that did not reach cinema tier is unavailable`() {
        val gate = CapabilityGates.tenBitRecording(
            identity = identity,
            quirks = emptyList(),
            report = report(
                deviceKey = identity.deviceKey,
                tier = "standard",
                verified = mapOf("fhd30_10bit" to "not_run"),
            ),
            cameraId = "0",
            width = 1920,
            height = 1080,
            fps = 30,
        )

        assertFalse(gate.available)
        assertTrue(gate.reason.contains("standard"))
    }

    @Test
    fun `without a report the feature stays closed`() {
        val gate = CapabilityGates.tenBitRecording(
            identity = identity,
            quirks = emptyList(),
            report = null,
            cameraId = "0",
            width = 1920,
            height = 1080,
            fps = 30,
        )

        assertFalse(gate.available)
        assertTrue(gate.reason.contains("device report"))
    }

    @Test
    fun `logical zoom handoff requires a passing recording verdict`() {
        val passed = logicalReport("pass")
        val failed = logicalReport("fail")
        val missing = logicalReport("pass").copy(
            verification = Verification(ran = true, checks = emptyList()),
        )

        assertTrue(
            CapabilityGates.logicalZoomHandoff(
                identity, passed, "4",
            ).available,
        )
        assertFalse(
            CapabilityGates.logicalZoomHandoff(
                identity, failed, "4",
            ).available,
        )
        assertFalse(
            CapabilityGates.logicalZoomHandoff(
                identity, missing, "4",
            ).available,
        )
    }

    private fun logicalReport(status: String): ProbeReport {
        val base = report(
            deviceKey = identity.deviceKey,
            tier = "cinema",
            verified = mapOf("fhd30_10bit" to "pass"),
        )
        return base.copy(
            cameras = listOf(
                base.cameras.first().copy(
                    id = "4",
                    logicalMultiCamera = true,
                    logicalZoom = LogicalZoomInfo(
                        handoffEligible = true,
                        ratioRange = FloatRange(0.6f, 10f),
                        stops = listOf(
                            LogicalZoomStop(
                                physicalId = "2",
                                role = "ultrawide",
                                ratio = 0.621,
                                basis = "physical_intrinsic",
                            ),
                            LogicalZoomStop(
                                physicalId = "0",
                                role = "default",
                                ratio = 1.0,
                                basis = "physical_intrinsic",
                            ),
                        ),
                    ),
                ),
            ),
            verification = Verification(
                ran = true,
                checks = listOf(
                    VerificationCheck(
                        id = "logicalZoomHandoff_c4",
                        status = status,
                        detail = "test",
                    ),
                ),
            ),
        )
    }

    private fun report(
        deviceKey: String,
        tier: String,
        verified: Map<String, String>,
    ) = ProbeReport(
        report = ReportMeta(
            id = "test",
            createdAt = "2026-11-23T00:00:00Z",
            proberVersion = "1.6.0",
            appVersion = "0.1.0",
            durationMs = 1,
        ),
        device = DeviceInfo(
            deviceKey = deviceKey,
            manufacturer = "test",
            brand = "test",
            model = "test",
            device = "test",
            product = "test",
            board = "test",
            hardware = "test",
            fingerprint = "test",
            buildId = "test",
            androidRelease = "17",
            sdk = 37,
            securityPatch = "2026-10-01",
            socManufacturer = "test",
            socModel = "test",
            abis = listOf("arm64-v8a"),
            isEmulator = false,
            isLowRam = false,
        ),
        system = SystemInfo(
            thermalStatus = "none",
            totalRamMb = 8192,
            freeStorageMb = 4096,
            nativePageSizeKb = 4,
        ),
        cameras = listOf(
            CameraInfo(
                id = "0",
                facing = "back",
                hardwareLevel = "level_3",
                logicalMultiCamera = false,
                dynamicRange = DynamicRangeInfo(tenBitStreams = true),
                presets = verified.mapValues { (_, status) ->
                    PresetInfo(supported = true, verified = status)
                },
            ),
        ),
        tier = TierVerdict(name = tier),
    )
}
