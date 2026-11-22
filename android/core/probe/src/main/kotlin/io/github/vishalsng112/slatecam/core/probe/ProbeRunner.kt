package io.github.vishalsng112.slatecam.core.probe

import android.content.Context
import io.github.vishalsng112.slatecam.core.probe.eval.PresetCatalog
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
import io.github.vishalsng112.slatecam.core.probe.verify.EncoderVerifier
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

    fun run(kind: String = "summary", verify: Boolean = true): ProbeReport {
        val startedAt = System.currentTimeMillis()
        val warnings = mutableListOf<String>()

        val device = DeviceSource.read(context)
        val system = SystemSource.read(context)
        val displays = DisplaySource.read(context)
        val cameras = CameraSource(context).read(warnings)
        val encoders = EncoderSource().read(warnings)
        val audio = AudioSource.read(context)

        val encodeOutcome = if (verify) EncoderVerifier().verifyHevcMain10() else null
        val encodeCheck = encodeOutcome?.check
        val main10Verified = encodeCheck?.status ?: "not_run"

        val encodersWithVerification = encoders.map { encoder ->
            if (encoder.mime == "video/hevc") {
                val verified = if (encoder.name == encodeOutcome?.passingCodec) {
                    main10Verified
                } else {
                    // Not exercised: claiming a verdict for this codec would lie.
                    "not_run"
                }
                encoder.copy(main10Verified = verified)
            } else {
                encoder
            }
        }

        val verifiedWidth = encodeOutcome?.width ?: 0
        val verifiedHeight = encodeOutcome?.height ?: 0
        val camerasWithVerification = cameras.map { camera ->
            camera.copy(
                presets = camera.presets.mapValues { (id, preset) ->
                    val spec = PresetCatalog.v1.firstOrNull { it.id == id }
                    val sameSize = spec != null &&
                        spec.width == verifiedWidth &&
                        spec.height == verifiedHeight
                    // A verdict only covers a preset this camera actually offers
                    // at the size that encoded successfully.
                    val promotable = preset.supported &&
                        spec != null &&
                        spec.width == verifiedWidth &&
                        spec.height == verifiedHeight
                    if (id.endsWith("_10bit") && promotable && main10Verified == "pass") {
                        preset.copy(verified = "pass")
                    } else {
                        preset
                    }
                },
            )
        }

        val hardwareHevc = encoders.any { it.mime == "video/hevc" && it.hardware }
        val hardwareHevcMain10 = encoders.any {
            it.mime == "video/hevc" && it.hardware && it.main10Claimed
        }

        val primaryCamera = camerasWithVerification
            .filter { it.facing == "back" }
            .minByOrNull { it.id.toIntOrNull() ?: Int.MAX_VALUE }
            ?: camerasWithVerification.firstOrNull()

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

        if (camerasWithVerification.count { it.facing == "back" } > 1) {
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
            cameras = camerasWithVerification,
            encoders = encodersWithVerification,
            audio = audio,
            tier = tier,
            verification = if (encodeCheck != null) {
                Verification(ran = true, checks = listOf(encodeCheck))
            } else {
                Verification(ran = false)
            },
            warnings = warnings,
        )
    }

    fun runJson(kind: String = "summary", verify: Boolean = true): String =
        json.encodeToString(run(kind, verify))

    companion object {
        const val PROBER_VERSION = "1.0.0"

        val json: Json = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
