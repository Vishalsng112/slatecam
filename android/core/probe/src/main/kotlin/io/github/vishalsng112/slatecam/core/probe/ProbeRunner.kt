package io.github.vishalsng112.slatecam.core.probe

import android.content.Context
import io.github.vishalsng112.slatecam.core.probe.eval.TierEvaluator
import io.github.vishalsng112.slatecam.core.probe.eval.TierInput
import io.github.vishalsng112.slatecam.core.probe.model.ProbeReport
import io.github.vishalsng112.slatecam.core.probe.model.ReportMeta
import io.github.vishalsng112.slatecam.core.probe.model.TierVerdict
import io.github.vishalsng112.slatecam.core.probe.model.Verification
import io.github.vishalsng112.slatecam.core.probe.source.AudioSource
import io.github.vishalsng112.slatecam.core.probe.source.CameraSource
import io.github.vishalsng112.slatecam.core.probe.source.DeviceSource
import io.github.vishalsng112.slatecam.core.probe.source.DisplaySource
import io.github.vishalsng112.slatecam.core.probe.source.EncoderSource
import io.github.vishalsng112.slatecam.core.probe.source.SystemSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * Orchestrates a probe run. Blocking: call from a background thread.
 *
 * Every platform read is best-effort; a vendor HAL that throws on one
 * characteristic must not abort the whole report.
 */
class ProbeRunner(private val context: Context) {

    fun run(kind: String = "summary"): ProbeReport {
        val startedAt = System.currentTimeMillis()
        val warnings = mutableListOf<String>()

        val device = DeviceSource.read(context)
        val system = SystemSource.read(context)
        val displays = DisplaySource.read(context)
        val cameras = CameraSource(context).read(warnings)
        val encoders = EncoderSource().read(warnings)
        val audio = AudioSource.read(context)

        val hardwareHevc = encoders.any { it.mime == "video/hevc" && it.hardware }
        val hardwareHevcMain10 = encoders.any {
            it.mime == "video/hevc" && it.hardware && it.main10Claimed
        }
        val main10Verified = encoders
            .filter { it.mime == "video/hevc" && it.main10Claimed }
            .map { it.main10Verified }
            .let { statuses ->
                when {
                    statuses.any { it == "pass" } -> "pass"
                    statuses.isNotEmpty() && statuses.all { it == "fail" } -> "fail"
                    else -> "not_run"
                }
            }

        val primaryCamera = cameras
            .filter { it.facing == "back" }
            .minByOrNull { it.id.toIntOrNull() ?: Int.MAX_VALUE }
            ?: cameras.firstOrNull()

        val tier = if (primaryCamera == null) {
            warnings += "no camera reported by the device"
            TierVerdict(
                name = "unsupported",
                missing = listOf("no_camera"),
                reasons = listOf("no_camera_found"),
            )
        } else {
            val capabilities = primaryCamera.capabilities.toSet()
            TierEvaluator.evaluate(
                TierInput(
                    hardwareLevel = primaryCamera.hardwareLevel,
                    manualSensor = "manual_sensor" in capabilities,
                    manualPostProcessing = "manual_post_processing" in capabilities,
                    tenBit = "dynamic_range_ten_bit" in capabilities,
                    hardwareHevc = hardwareHevc,
                    hardwareHevcMain10 = hardwareHevcMain10,
                    main10Verified = main10Verified,
                ),
            )
        }

        if (cameras.count { it.facing == "back" } > 1) {
            warnings += "multiple back cameras; tier computed from camera ${primaryCamera?.id}"
        }

        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"

        return ProbeReport(
            kind = kind,
            report = ReportMeta(
                id = UUID.randomUUID().toString(),
                createdAt = Instant.ofEpochMilli(startedAt).toString(),
                proberVersion = PROBER_VERSION,
                appVersion = versionName,
                durationMs = System.currentTimeMillis() - startedAt,
            ),
            device = device,
            system = system,
            displays = displays,
            cameras = cameras,
            encoders = encoders,
            audio = audio,
            tier = tier,
            verification = Verification(ran = false),
            warnings = warnings,
        )
    }

    fun runJson(kind: String = "summary"): String = json.encodeToString(run(kind))

    companion object {
        const val PROBER_VERSION = "1.0.0"

        val json: Json = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
