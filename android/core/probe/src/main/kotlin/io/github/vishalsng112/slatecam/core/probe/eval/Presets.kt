package io.github.vishalsng112.slatecam.core.probe.eval

import io.github.vishalsng112.slatecam.core.probe.model.PresetInfo
import io.github.vishalsng112.slatecam.core.probe.model.TierVerdict

/** Stream formats the probe pipeline can consume. Deliberately not Android enums. */
enum class StreamFormat { PRIVATE, YUV420 }

data class StreamEntry(
    val width: Int,
    val height: Int,
    val format: StreamFormat,
    val minFrameDurationNs: Long,
    val stallDurationNs: Long = 0L,
)

data class HighSpeedEntry(
    val width: Int,
    val height: Int,
    val maxFps: Int,
)

data class Preset(
    val id: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val tenBit: Boolean,
)

/**
 * The recording-mode catalogue. The prober and the UI both read this list, so a
 * new mode cannot be shipped without a matching capability verdict.
 */
object PresetCatalog {
    private val cinemaRates = listOf(24, 25, 30)

    val v1: List<Preset> = buildList {
        cinemaRates.forEach { fps -> add(Preset("uhd${fps}_10bit", 3840, 2160, fps, true)) }
        cinemaRates.forEach { fps -> add(Preset("uhd${fps}_8bit", 3840, 2160, fps, false)) }
        add(Preset("uhd60_8bit", 3840, 2160, 60, false))
        add(Preset("uhd60_10bit", 3840, 2160, 60, true))
        add(Preset("dci4k24_10bit", 4096, 2160, 24, true))
        cinemaRates.forEach { fps -> add(Preset("fhd${fps}_8bit", 1920, 1080, fps, false)) }
        // 10-bit at FHD matters: some devices only expose P010 below UHD.
        cinemaRates.forEach { fps -> add(Preset("fhd${fps}_10bit", 1920, 1080, fps, true)) }
        add(Preset("fhd60_8bit", 1920, 1080, 60, false))
        add(Preset("fhd120_8bit", 1920, 1080, 120, false))
        add(Preset("hd240_8bit", 1280, 720, 240, false))
    }
}

/**
 * Pure evaluation of the preset catalogue against a camera's stream map.
 *
 * Important limitation: Camera2 does not report bit depth per stream size. The
 * 10-bit capability is device-wide, so a 10-bit preset can only be *claimed*
 * until a real session is configured with a dynamic range profile.
 */
class PresetEvaluator(
    private val streams: List<StreamEntry>,
    private val highSpeed: List<HighSpeedEntry>,
    private val tenBitClaimed: Boolean,
) {

    fun evaluate(preset: Preset): PresetInfo {
        if (preset.fps > 60) return evaluateHighSpeed(preset)
        return evaluateNormal(preset)
    }

    private fun evaluateNormal(preset: Preset): PresetInfo {
        val matching = streams.filter { it.width == preset.width && it.height == preset.height }
        if (matching.isEmpty()) {
            return unsupported(
                preset,
                reason = "no_stream_config",
                detail = "${preset.width}x${preset.height} is not offered by this camera",
            )
        }

        if (preset.tenBit && !tenBitClaimed) {
            return unsupported(
                preset,
                reason = "unsupported_format",
                detail = "device does not advertise DYNAMIC_RANGE_TEN_BIT",
            )
        }

        val best = matching
            .filter { it.minFrameDurationNs > 0 }
            .minByOrNull { it.minFrameDurationNs }
        if (best == null) {
            return unsupported(
                preset,
                reason = "unsupported_format",
                detail = "no frame duration reported for ${preset.width}x${preset.height}",
            )
        }

        val maxFps = 1_000_000_000.0 / best.minFrameDurationNs.toDouble()
        if (maxFps + 0.05 < preset.fps) {
            return unsupported(
                preset,
                reason = "fps_out_of_range",
                detail = "max %.1f fps at %dx%d".format(maxFps, preset.width, preset.height),
            )
        }

        return PresetInfo(
            supported = true,
            format = if (preset.tenBit) "p010" else "yuv420_flexible",
            minFrameDurationNs = best.minFrameDurationNs,
            stallDurationNs = best.stallDurationNs.takeIf { it > 0L },
            profiles = if (preset.tenBit) listOf("hlg10") else emptyList(),
            basis = if (preset.tenBit) "capability_only" else "stream_map",
            verified = "not_run",
        )
    }

    private fun evaluateHighSpeed(preset: Preset): PresetInfo {
        if (preset.tenBit) {
            return unsupported(
                preset,
                reason = "unsupported_format",
                detail = "high-speed Camera2 streams are 8-bit only",
            )
        }
        val sameSize = highSpeed.filter { it.width == preset.width && it.height == preset.height }
        if (sameSize.isEmpty()) {
            return unsupported(
                preset,
                reason = "no_stream_config",
                detail = "no high-speed stream at ${preset.width}x${preset.height}",
            )
        }
        val best = sameSize.maxOf { it.maxFps }
        if (best < preset.fps) {
            return unsupported(
                preset,
                reason = "fps_out_of_range",
                detail = "high-speed max $best fps at ${preset.width}x${preset.height}",
            )
        }
        return PresetInfo(
            supported = true,
            format = "yuv420_flexible",
            basis = "stream_map",
            verified = "not_run",
        )
    }

    private fun unsupported(preset: Preset, reason: String, detail: String): PresetInfo =
        PresetInfo(
            supported = false,
            reason = reason,
            detail = detail,
            basis = "stream_map",
            verified = "not_run",
        )
}

/** Input to the tier rules, reduced to plain values so it is testable. */
data class TierInput(
    val hardwareLevel: String,
    val manualSensor: Boolean,
    val manualPostProcessing: Boolean,
    val tenBit: Boolean,
    val hardwareHevc: Boolean,
    val hardwareHevcMain10: Boolean,
    /** pass | fail | not_run */
    val main10Verified: String,
)

object TierEvaluator {

    fun evaluate(input: TierInput): TierVerdict {
        val missing = mutableListOf<String>()
        val unverified = mutableListOf<String>()
        val reasons = mutableListOf<String>()

        val fullLevel = input.hardwareLevel == "full" || input.hardwareLevel == "level_3"
        if (!fullLevel) missing += "hardware_level_full_or_level_3"
        if (!input.manualSensor) missing += "manual_sensor"
        if (!input.manualPostProcessing) missing += "manual_post_processing"
        if (!input.tenBit) missing += "dynamic_range_ten_bit"
        // A device may encode Main10 without advertising it (MediaTek HALs do
        // this). A passing verification counts as the capability, otherwise the
        // advertised profile is required.
        val main10Available = input.hardwareHevcMain10 || input.main10Verified == "pass"
        if (!main10Available) missing += "hevc_main10_encoder"

        if (missing.isEmpty()) {
            return when (input.main10Verified) {
                "pass" -> TierVerdict(name = "cinema")
                "fail" -> TierVerdict(
                    name = "cinema_claimed",
                    reasons = listOf("ten_bit_verification_failed"),
                )
                else -> TierVerdict(
                    name = "cinema_claimed",
                    unverified = listOf("hevc_main10_encode"),
                    reasons = listOf("ten_bit_claimed_not_verified"),
                )
            }
        }

        val levelOk = fullLevel || input.hardwareLevel == "limited"
        if (levelOk && input.manualSensor && input.hardwareHevc) {
            reasons += "missing_cinema_capabilities"
            return TierVerdict(name = "standard", missing = missing, reasons = reasons)
        }

        reasons += "no_usable_manual_pipeline"
        return TierVerdict(name = "basic", missing = missing, reasons = reasons)
    }
}
