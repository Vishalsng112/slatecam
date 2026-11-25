package io.github.vishalsng112.slatecam.core.device

import io.github.vishalsng112.slatecam.core.probe.eval.PresetCatalog
import io.github.vishalsng112.slatecam.core.probe.model.ProbeReport

/**
 * A feature decision plus the plain-language reason to show the user.
 *
 * Features are gated by what the probe verified on *this* device. Nothing here
 * looks at a brand, model or SoC: the same report from the same build on the
 * same camera always produces the same answer.
 */
data class FeatureGate(
    val available: Boolean,
    val reason: String,
)

/**
 * Turns a probe report into runtime feature decisions.
 *
 * The rules mirror docs/compatibility.md: verified beats claimed, a quirk can
 * withhold a capability the probe proved, and a verdict covers only the preset
 * it was measured on. A report from another device is never used.
 */
object CapabilityGates {

    /**
     * Whether the controller may use CONTROL_ZOOM_RATIO handoff for [cameraId].
     * The probe must have recorded a passing `logicalZoomHandoff` check; the
     * discovery fields alone are not evidence.
     */
    fun logicalZoomHandoff(
        identity: DeviceIdentity,
        report: ProbeReport?,
        cameraId: String,
    ): FeatureGate {
        if (report == null) {
            return FeatureGate(false, "run the device report to verify logical zoom handoff")
        }
        if (!report.device.deviceKey.equals(identity.deviceKey, ignoreCase = true)) {
            return FeatureGate(
                false,
                "latest report is for ${report.device.deviceKey}; " +
                    "run the device report on this device",
            )
        }
        val camera = report.cameras.firstOrNull { it.id == cameraId }
            ?: return FeatureGate(false, "camera $cameraId is missing from the probe report")
        if (camera.logicalZoom?.handoffEligible != true) {
            return FeatureGate(
                false,
                camera.logicalZoom?.reason?.takeIf { it.isNotBlank() }
                    ?: "camera $cameraId has no eligible logical zoom range",
            )
        }
        val check = report.verification.checks.firstOrNull {
            it.id == "logicalZoomHandoff_c$cameraId"
        } ?: return FeatureGate(
            false,
            "logical zoom handoff was not exercised; re-run the device report",
        )
        if (check.status != "pass") {
            return FeatureGate(
                false,
                "logical zoom handoff ${check.status}: ${check.detail ?: "no detail"}",
            )
        }
        return FeatureGate(true, check.detail ?: "logical zoom handoff verified")
    }

    /**
     * Whether 10-bit HLG recording may be requested for [cameraId] at
     * [width] x [height] @ [fps].
     *
     * The caller still has to honour the live session: the camera may reject
     * HLG10 and fall back to SDR, and the recorder only writes the HLG metadata
     * when the encoded SPS really says Main10.
     */
    fun tenBitRecording(
        identity: DeviceIdentity,
        quirks: List<QuirkEntry>,
        report: ProbeReport?,
        cameraId: String?,
        width: Int,
        height: Int,
        fps: Int,
    ): FeatureGate {
        quirks.firstOrNull { it.action == QuirkActions.FORCE_8BIT }?.let { quirk ->
            val why = quirk.reason.takeIf { it.isNotBlank() } ?: quirk.id
            return FeatureGate(false, "10-bit withheld by device quirk ${quirk.id}: $why")
        }

        if (report == null) {
            return FeatureGate(false, "run the device report to verify 10-bit recording")
        }

        if (!report.device.deviceKey.equals(identity.deviceKey, ignoreCase = true)) {
            return FeatureGate(
                false,
                "latest report is for ${report.device.deviceKey}; " +
                    "run the device report on this device",
            )
        }

        if (report.tier.name != "cinema") {
            val why = (report.tier.missing + report.tier.unverified + report.tier.reasons)
                .distinct()
            val suffix = if (why.isEmpty()) "" else " (${why.joinToString(", ")})"
            return FeatureGate(false, "device tier is ${report.tier.name}$suffix")
        }

        val preset = PresetCatalog.v1.firstOrNull {
            it.tenBit && it.width == width && it.height == height && it.fps == fps
        } ?: return FeatureGate(false, "no 10-bit preset at ${width}x$height @ ${fps}p")

        val camera = report.cameras.firstOrNull { it.id == cameraId }
            ?: report.cameras.firstOrNull { it.facing == "back" }
            ?: return FeatureGate(false, "no camera data in the probe report")

        val verdict = camera.presets[preset.id]
            ?: return FeatureGate(
                false,
                "${preset.id} is missing from the probe report; re-run it",
            )

        if (!verdict.supported) {
            return FeatureGate(
                false,
                verdict.detail ?: verdict.reason ?: "${preset.id} is unsupported",
            )
        }

        if (verdict.verified != "pass") {
            return FeatureGate(
                false,
                "${preset.id} is not verified on camera ${camera.id} " +
                    "(probe: ${verdict.verified})",
            )
        }

        return FeatureGate(true, "${preset.id} verified on camera ${camera.id}")
    }
}
