package io.github.vishalsng112.slatecam.core.probe

import android.content.Context
import io.github.vishalsng112.slatecam.core.probe.eval.PresetCatalog
import io.github.vishalsng112.slatecam.core.probe.eval.TierEvaluator
import io.github.vishalsng112.slatecam.core.probe.eval.TierInput
import io.github.vishalsng112.slatecam.core.probe.model.CameraInfo
import io.github.vishalsng112.slatecam.core.probe.model.ProbeReport
import io.github.vishalsng112.slatecam.core.probe.model.ReportMeta
import io.github.vishalsng112.slatecam.core.probe.model.TierVerdict
import io.github.vishalsng112.slatecam.core.probe.model.Verification
import io.github.vishalsng112.slatecam.core.probe.model.VerificationCheck
import io.github.vishalsng112.slatecam.core.probe.source.AudioSource
import io.github.vishalsng112.slatecam.core.probe.source.CameraSource
import io.github.vishalsng112.slatecam.core.probe.source.DeviceSource
import io.github.vishalsng112.slatecam.core.probe.source.DisplaySource
import io.github.vishalsng112.slatecam.core.probe.source.EncoderSource
import io.github.vishalsng112.slatecam.core.probe.source.SystemSource
import io.github.vishalsng112.slatecam.core.probe.verify.CameraSessionVerifier
import io.github.vishalsng112.slatecam.core.probe.verify.ConcurrentSessionVerifier
import io.github.vishalsng112.slatecam.core.probe.verify.EncoderVerifier
import io.github.vishalsng112.slatecam.core.probe.verify.LogicalZoomHandoffVerifier
import io.github.vishalsng112.slatecam.core.probe.verify.DualSessionSwitchVerifier
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
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
        val cameraSource = CameraSource(context)
        val cameras = cameraSource.read(warnings)
        val cameraAux = cameraSource.auxInfo()
        if (cameraAux != null &&
            cameraAux.allowlistedPackages.isNotEmpty() &&
            cameraAux.thisAppAllowlisted != true
        ) {
            val hiddenOpenable = cameras.filter { !it.exposed && it.openable == true }
            warnings += if (hiddenOpenable.isEmpty()) {
                "auxiliary cameras are gated by ${cameraAux.allowlistProperty}; " +
                    "this package is not allowlisted and no hidden camera was openable"
            } else {
                "OEM auxiliary allowlist (${cameraAux.allowlistProperty}) excludes this package, " +
                    "but unlisted cameras ${hiddenOpenable.joinToString(",") { it.id }} were openable"
            }
        }
        val encoders = EncoderSource().read(warnings)
        val audio = AudioSource.read(context)

        // Verify every size the app can record in 10-bit, and every lens that
        // participates in a shoot set. A verdict covers only the size and camera
        // that were actually exercised.
        val primaryCameraInfo = cameras
            .filter { it.facing == "back" }
            .minWithOrNull(
                compareBy<CameraInfo> { if (it.role == "default") 0 else 1 }
                    .thenBy { it.id.toIntOrNull() ?: Int.MAX_VALUE },
            )
            ?: cameras.firstOrNull()

        val claimedTenBitSizes = cameras
            .flatMap { camera -> camera.presets.filterValues { it.supported }.keys }
            .mapNotNull { id -> PresetCatalog.v1.firstOrNull { it.id == id } }
            .filter { it.tenBit }
            .map { it.width to it.height }
            .toSet()
        val sizesToVerify = VERIFY_SIZES
            .filter { it in claimedTenBitSizes }
            .ifEmpty { listOf(FALLBACK_VERIFY_SIZE) }

        val encodeOutcomes = if (verify) {
            sizesToVerify.mapIndexed { index, (width, height) ->
                EncoderVerifier().verifyHevcMain10(
                    width = width,
                    height = height,
                    checkId = checkId("hevc_main10_encode", width, height, index),
                )
            }
        } else {
            emptyList()
        }

        val sessionResults = mutableListOf<SessionResult>()
        if (verify) {
            val sessionVerifier = CameraSessionVerifier(context)
            if (primaryCameraInfo != null) {
                sizesToVerify.forEachIndexed { index, (width, height) ->
                    val result = sessionVerifier.verifyHlg10Session(
                        width = width,
                        height = height,
                        checkId = checkId("camera_hlg10_session", width, height, index),
                        cameraId = primaryCameraInfo.id,
                    )
                    sessionResults += SessionResult(
                        primaryCameraInfo.id,
                        width,
                        height,
                        result.check,
                    )
                }
            }

            for (camera in uniqueVerificationCameras(cameras, primaryCameraInfo?.id)) {
                for ((width, height) in sizesToVerify) {
                    if (!supportsTenBitAt(camera, width, height)) continue
                    val result = sessionVerifier.verifyHlg10Session(
                        width = width,
                        height = height,
                        checkId = "camera_hlg10_session_c${camera.id}_" + sizeTag(width, height),
                        cameraId = camera.id,
                    )
                    sessionResults += SessionResult(camera.id, width, height, result.check)
                }
            }
        }
        val concurrentCheck = if (verify) ConcurrentSessionVerifier(context).verify() else null
        val dualSessionChecks = if (verify) {
            val exposed = cameras.filter { it.exposed }
            val pairs = exposed
                .flatMap { a ->
                    a.concurrentWith.mapNotNull { b ->
                        exposed.firstOrNull { it.id == b }?.let { other ->
                            listOf(a.id, other.id).sorted()
                        }
                    }
                }
                .distinct()
            pairs.map { pair ->
                DualSessionSwitchVerifier(context).verify(pair[0], pair[1])
            }
        } else {
            emptyList()
        }
        // Run last: opening a vendor-hidden logical camera can disturb the
        // camera list for the checks above.
        val logicalZoomChecks = if (verify) {
            cameras
                .filter { it.logicalZoom?.handoffEligible == true }
                .map { camera ->
                    LogicalZoomHandoffVerifier(context).verify(
                        camera = camera,
                        checkId = "logicalZoomHandoff_c${camera.id}",
                    )
                }
        } else {
            emptyList()
        }

        // Tier uses the primary camera's largest claimed pair, so a mix of sizes
        // or cameras cannot be promoted to cinema.
        val primarySize = sizesToVerify.firstOrNull()
        val primaryEncode = encodeOutcomes.firstOrNull()?.check
        val primarySession = primarySize?.let { size ->
            sessionResults.firstOrNull {
                it.cameraId == primaryCameraInfo?.id &&
                    it.width == size.first && it.height == size.second
            }?.check
        }
        val primaryPairPass = primaryEncode?.status == "pass" && primarySession?.status == "pass"
        val main10Verified = when {
            !verify -> "not_run"
            primaryPairPass -> "pass"
            else -> primaryEncode?.status ?: "not_run"
        }
        val sessionVerified = when {
            !verify -> "not_run"
            primaryPairPass -> "pass"
            else -> primarySession?.status ?: "not_run"
        }
        val passingCodecs = encodeOutcomes
            .filter { it.check.status == "pass" }
            .mapNotNull { it.passingCodec }
            .toSet()

        val encodersWithVerification = encoders.map { encoder ->
            if (encoder.mime == "video/hevc") {
                // Credit only a codec that produced output. Everyone else stays
                // not_run rather than inheriting a pass.
                val verified = if (encoder.name in passingCodecs) "pass" else "not_run"
                encoder.copy(main10Verified = verified)
            } else {
                encoder
            }
        }

        // A preset is verified only when the encoder passed at that size and the
        // session for that exact camera and size also passed.
        val verifiedSizesByCamera = buildMap<String, MutableSet<Pair<Int, Int>>> {
            sessionResults.forEach { result ->
                val sizeIndex = sizesToVerify.indexOf(result.width to result.height)
                val encodeOk = sizeIndex >= 0 &&
                    encodeOutcomes.getOrNull(sizeIndex)?.check?.status == "pass"
                if (encodeOk && result.check.status == "pass") {
                    getOrPut(result.cameraId) { mutableSetOf() }
                        .add(result.width to result.height)
                }
            }
        }

        val camerasWithVerification = cameras.map { camera ->
            camera.copy(
                presets = camera.presets.mapValues { (id, preset) ->
                    val spec = PresetCatalog.v1.firstOrNull { it.id == id }
                    val verifiedSizes = verifiedSizesByCamera[camera.id].orEmpty()
                    val sameSize = spec != null &&
                        (spec.width to spec.height) in verifiedSizes
                    if (id.endsWith("_10bit") && preset.supported && sameSize) {
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

        val primaryCamera = primaryCameraInfo?.let { primary ->
            camerasWithVerification.firstOrNull { it.id == primary.id }
        } ?: camerasWithVerification.firstOrNull()

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
                    tenBitSessionVerified = sessionVerified,
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
            verification = Verification(
                ran = encodeOutcomes.isNotEmpty() ||
                    sessionResults.isNotEmpty() ||
                    concurrentCheck != null ||
                    dualSessionChecks.isNotEmpty() ||
                    logicalZoomChecks.isNotEmpty(),
                checks = encodeOutcomes.map { it.check } +
                    sessionResults.map { it.check } +
                    listOfNotNull(concurrentCheck) +
                    dualSessionChecks +
                    logicalZoomChecks,
            ),
            cameraAux = cameraAux,
            warnings = warnings,
        )
    }

    fun runJson(kind: String = "summary", verify: Boolean = true): String =
        json.encodeToString(run(kind, verify))

    private data class SessionResult(
        val cameraId: String,
        val width: Int,
        val height: Int,
        val check: VerificationCheck,
    )

    /**
     * Cameras whose 10-bit path is worth exercising outside the primary lens:
     * exposed or probe-openable, de-duplicated by facing/role/focal length so
     * logical aliases do not repeat the same session.
     */
    private fun uniqueVerificationCameras(
        cameras: List<CameraInfo>,
        primaryId: String?,
    ): List<CameraInfo> {
        val seen = mutableSetOf<String>()
        return cameras
            .asSequence()
            .filter { it.id != primaryId && (it.exposed || it.openable == true) }
            .sortedWith(
                compareByDescending<CameraInfo> { it.exposed }
                    .thenBy { it.id.toIntOrNull() ?: Int.MAX_VALUE },
            )
            .filter { camera ->
                val key = "${camera.facing}:${camera.role}:" +
                    ((camera.focalLength35mmEquiv ?: 0.0).roundToInt())
                seen.add(key)
            }
            .toList()
    }

    private fun supportsTenBitAt(camera: CameraInfo, width: Int, height: Int): Boolean =
        camera.presets.any { (id, info) ->
            info.supported && PresetCatalog.v1.firstOrNull { it.id == id }?.let {
                it.tenBit && it.width == width && it.height == height
            } == true
        }

    companion object {
        const val PROBER_VERSION = "1.12.0"

        /**
         * Sizes the prober exercises for the 10-bit path. UHD first: it is the
         * primary tier verdict. FHD is included because it is the size the
         * recorder currently captures, and a preset verdict covers only the size
         * that actually encoded.
         */
        private val VERIFY_SIZES = listOf(
            3840 to 2160,
            1920 to 1080,
            1440 to 1080,
        )
        private val FALLBACK_VERIFY_SIZE = 1920 to 1080

        private fun checkId(base: String, width: Int, height: Int, index: Int): String {
            if (index == 0) return base
            return "${base}_${sizeTag(width, height)}"
        }

        private fun sizeTag(width: Int, height: Int): String = when (width to height) {
            3840 to 2160 -> "uhd"
            1920 to 1080 -> "fhd"
            1440 to 1080 -> "fhd43"
            else -> "${width}x$height"
        }

        val json: Json = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
            // Forward compatibility: a report from a newer prober may carry fields
            // this build does not know. Never fail on them.
            ignoreUnknownKeys = true
        }
    }
}
