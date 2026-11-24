package io.github.vishalsng112.slatecam.core.probe.source

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import io.github.vishalsng112.slatecam.core.probe.eval.CameraRoles
import io.github.vishalsng112.slatecam.core.probe.eval.HighSpeedEntry
import io.github.vishalsng112.slatecam.core.probe.eval.PresetCatalog
import io.github.vishalsng112.slatecam.core.probe.eval.PresetEvaluator
import io.github.vishalsng112.slatecam.core.probe.eval.StreamEntry
import io.github.vishalsng112.slatecam.core.probe.eval.StreamFormat
import io.github.vishalsng112.slatecam.core.probe.model.CameraAuxInfo
import io.github.vishalsng112.slatecam.core.probe.model.CameraInfo
import io.github.vishalsng112.slatecam.core.probe.model.ControlsInfo
import io.github.vishalsng112.slatecam.core.probe.model.DynamicRangeInfo
import io.github.vishalsng112.slatecam.core.probe.model.ExposureCompensation
import io.github.vishalsng112.slatecam.core.probe.model.FloatRange
import io.github.vishalsng112.slatecam.core.probe.model.FpsRange
import io.github.vishalsng112.slatecam.core.probe.model.LensInfo
import io.github.vishalsng112.slatecam.core.probe.model.MaxRegions
import io.github.vishalsng112.slatecam.core.probe.model.PhysicalCameraInfo
import io.github.vishalsng112.slatecam.core.probe.model.SensorInfo
import io.github.vishalsng112.slatecam.core.probe.model.ZoomInfo
import kotlin.math.hypot

class CameraSource(private val context: Context) {

    fun read(warnings: MutableList<String>): List<CameraInfo> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val ids = try {
            manager.cameraIdList
        } catch (t: Throwable) {
            warnings += "camera enumeration failed: ${t.message}"
            return emptyList()
        }

        val concurrentSets = try {
            manager.concurrentCameraIds
        } catch (t: Throwable) {
            emptySet<Set<String>>()
        }

        val exposed = ids.mapNotNull { id ->
            try {
                readCamera(manager, id, concurrentSets, exposed = true)
            } catch (t: Throwable) {
                warnings += "camera $id probe failed: ${t.javaClass.simpleName}: ${t.message}"
                null
            }
        }
        val unlisted = readUnlistedCameras(manager, ids.toSet(), concurrentSets, warnings)
        return assignRoles(exposed + unlisted)
    }

    /**
     * Reads the OEM auxiliary-camera policy. Android has no standard way to see
     * sensors hidden from `cameraIdList`; most vendors gate them with a system
     * property instead. Reporting the policy explains why a three-lens phone can
     * expose only one back camera to this package.
     */
    fun auxInfo(): CameraAuxInfo? {
        val allowKey = "vendor.camera.aux.packagelist"
        val allowlist = systemProperty(allowKey)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()

        val expose = listOf("camera.expose_aux", "persist.vendor.camera.expose_aux")
            .firstNotNullOfOrNull { key -> systemProperty(key)?.let { key to it } }

        if (allowlist.isNullOrEmpty() && expose == null) return null
        return CameraAuxInfo(
            allowlistProperty = allowKey.takeIf { !allowlist.isNullOrEmpty() },
            allowlistedPackages = allowlist ?: emptyList(),
            thisAppAllowlisted = allowlist?.let { context.packageName in it },
            exposeAuxProperty = expose?.first,
            exposeAuxValue = expose?.second,
        )
    }

    private fun systemProperty(key: String): String? {
        val reflected = runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, key) as? String
        }.getOrNull()
        if (!reflected.isNullOrBlank()) return reflected

        return runCatching {
            val process = ProcessBuilder("getprop", key).start()
            process.inputStream.bufferedReader().use { it.readText() }
                .trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun readCamera(
        manager: CameraManager,
        id: String,
        concurrentSets: Set<Set<String>>,
        exposed: Boolean,
    ): CameraInfo {
        val c = manager.getCameraCharacteristics(id)

        val capabilityNames = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toList()
            ?.mapNotNull { CAPABILITY_NAMES[it] }
            ?: emptyList()

        val tenBit = capabilityNames.contains("dynamic_range_ten_bit")
        val physicalIds = runCatching { c.physicalCameraIds.toList() }.getOrDefault(emptyList())
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val evaluator = PresetEvaluator(
            streams = streamEntries(map),
            highSpeed = highSpeedEntries(map),
            tenBitClaimed = tenBit,
        )
        val presets = PresetCatalog.v1.associate { preset ->
            preset.id to evaluator.evaluate(preset)
        }

        val focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.toList()
            ?: emptyList()
        val apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?: FloatArray(0)
        val hyperfocal = c.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        return CameraInfo(
            id = id,
            facing = facingName(c.get(CameraCharacteristics.LENS_FACING)),
            hardwareLevel = hardwareLevelName(
                c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            ),
            logicalMultiCamera = capabilityNames.contains("logical_multi_camera"),
            physicalIds = physicalIds,
            physicalCameras = readPhysicalCameras(manager, physicalIds),
            concurrentWith = concurrentSets
                .filter { id in it }
                .flatten()
                .filter { it != id }
                .distinct()
                .sorted(),
            capabilities = capabilityNames,
            sensor = readSensor(c),
            lenses = focalLengths.mapIndexed { index, focal ->
                LensInfo(
                    focalLengthMm = focal,
                    aperture = apertures.getOrElse(index) { 0f },
                    minFocusDistanceDiopters = minFocus,
                    hyperfocal = hyperfocal,
                )
            },
            zoom = ZoomInfo(
                maxDigital = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
                availableFocalLengthsMm = focalLengths,
                ratioRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    ?.let { FloatRange(it.lower, it.upper) },
            ),
            controls = readControls(c),
            dynamicRange = DynamicRangeInfo(
                tenBitStreams = tenBit,
                // HLG10 is the mandatory baseline profile for the ten-bit capability.
                profiles = if (tenBit) listOf("hlg10") else emptyList(),
                recommendedProfile = if (tenBit) recommendedTenBitProfile(c) else null,
            ),
            rawSizes = rawSizes(map),
            lensPoseTranslation = c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)?.toList(),
            lensPoseRotation = c.get(CameraCharacteristics.LENS_POSE_ROTATION)?.toList(),
            lensIntrinsics = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.toList(),
            presets = presets,
            exposed = exposed,
        )
    }

    private fun rawSizes(
        map: android.hardware.camera2.params.StreamConfigurationMap?,
    ): List<String> = runCatching {
        map?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.map { "${it.width}x${it.height}" }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * Reads whatever characteristics the platform exposes for physical sub-cameras
     * and classifies their lens roles by field of view. Physical IDs are not
     * always openable, so every read is best-effort.
     */
    private fun readPhysicalCameras(
        manager: CameraManager,
        ids: List<String>,
    ): List<PhysicalCameraInfo> {
        val partial = ids.mapNotNull { id ->
            runCatching { manager.getCameraCharacteristics(id) }.getOrNull()?.let { pc ->
                val focal = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.toList() ?: emptyList()
                val size = pc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val array = pc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val width = size?.width?.toDouble()
                val height = size?.height?.toDouble()
                val fov = CameraRoles.horizontalFovDegrees(
                    focal.firstOrNull()?.toDouble(),
                    width,
                )
                val focal35mm = CameraRoles.focalLength35mmEquivalent(
                    focal.firstOrNull()?.toDouble(),
                    width,
                    height,
                )
                PhysicalCameraInfo(
                    id = id,
                    facing = facingName(pc.get(CameraCharacteristics.LENS_FACING)),
                    hardwareLevel = hardwareLevelName(
                        pc.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
                    ),
                    focalLengthsMm = focal,
                    sensorSizeMm = size?.let { listOf(it.width, it.height) } ?: emptyList(),
                    pixelArray = array?.let { listOf(it.width, it.height) } ?: emptyList(),
                    horizontalFovDeg = fov?.toFloat(),
                    focalLength35mmEquiv = focal35mm,
                )
            }
        }
        val roles = CameraRoles.classify(
            partial.map { info ->
                CameraRoles.Candidate(
                    id = info.id,
                    facing = info.facing ?: "unknown",
                    hfovDeg = info.horizontalFovDeg?.toDouble(),
                    score = physicalScore(info),
                )
            },
        )
        return partial.map { info ->
            val role = roles[info.id]
            info.copy(
                role = role?.role ?: "unknown",
                roleBasis = role?.basis ?: "no_optical_data",
                roleConfidence = role?.confidence ?: "low",
                intrinsicZoomRatio = role?.intrinsicZoomRatio,
                lensRole = when (role?.role) {
                    "default" -> "wide"
                    "ultrawide" -> "ultrawide"
                    "tele" -> "tele"
                    "macro" -> "macro"
                    else -> null
                },
            )
        }
    }

    /**
     * Some ROMs let an app read characteristics for camera IDs that are not in
     * `cameraIdList`. This is not a public guarantee, so the scan is capped and
     * every result is marked `exposed = false`.
     */
    private fun readUnlistedCameras(
        manager: CameraManager,
        listed: Set<String>,
        concurrentSets: Set<Set<String>>,
        warnings: MutableList<String>,
    ): List<CameraInfo> {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        val found = mutableListOf<CameraInfo>()
        for (index in 0..MAX_SCAN_CAMERA_ID) {
            val id = index.toString()
            if (id in listed) continue
            runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val camera = runCatching {
                readCamera(manager, id, concurrentSets, exposed = false)
            }.getOrNull() ?: continue
            val openable = canOpen(manager, id)
            // A hidden camera is a discovery result, not a recording proposal:
            // keep identity, optics, roles and openability, but leave the full
            // preset/control tables out of the summary. They can be probed on
            // demand if the user selects the lens.
            found += camera.copy(
                openable = openable,
                // Keep the 10-bit verdict surface so movie-mode alignment can be
                // evaluated, but drop the rest of the summary payload.
                presets = camera.presets.filterKeys { it.endsWith("_10bit") },
                controls = null,
            )
            warnings += if (openable) {
                "camera $id is readable but not listed by cameraIdList (vendor-hidden, openable)"
            } else {
                "camera $id is readable but not openable by this package (vendor-hidden)"
            }
        }
        return found
    }

    /**
     * Best-effort open/close test for an unlisted ID. This is the only way to
     * know whether a vendor-hidden lens is usable, as opposed to merely
     * readable.
     */
    private fun canOpen(manager: CameraManager, id: String): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val thread = HandlerThread("probe-open-$id").apply { start() }
        val handler = Handler(thread.looper)
        val executor = java.util.concurrent.Executor { handler.post(it) }
        val latch = java.util.concurrent.CountDownLatch(1)
        var opened = false
        var device: CameraDevice? = null

        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                opened = true
                device = camera
                latch.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                device = camera
                latch.countDown()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                device = camera
                latch.countDown()
            }
        }

        runCatching { manager.openCamera(id, executor, callback) }
            .onFailure { latch.countDown() }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        runCatching { device?.close() }
        // device.close() dispatches onClosed on this executor.
        handler.postDelayed({ thread.quitSafely() }, 500)
        return opened
    }

    private fun assignRoles(cameras: List<CameraInfo>): List<CameraInfo> {
        val candidates = cameras.map { camera ->
            val focal = camera.lenses.firstOrNull()?.focalLengthMm?.toDouble()
            val width = camera.sensor?.physicalSizeMm?.getOrNull(0)?.toDouble()
            CameraRoles.Candidate(
                id = camera.id,
                facing = camera.facing,
                hfovDeg = CameraRoles.horizontalFovDegrees(focal, width),
                score = cameraScore(camera),
                exposed = camera.exposed,
                fixedFocus = (camera.lenses.firstOrNull()?.minFocusDistanceDiopters ?: 0f) <= 0f,
                pixelCount = cameraPixelCount(camera),
            )
        }
        val roles = CameraRoles.classify(candidates)

        return cameras.map { camera ->
            val role = roles[camera.id]
            val width = camera.sensor?.physicalSizeMm?.getOrNull(0)?.toDouble()
            val height = camera.sensor?.physicalSizeMm?.getOrNull(1)?.toDouble()
            val lenses = camera.lenses.map { lens ->
                lens.copy(
                    focalLength35mmEquiv = CameraRoles.focalLength35mmEquivalent(
                        lens.focalLengthMm.toDouble(),
                        width,
                        height,
                    ),
                )
            }
            camera.copy(
                role = role?.role ?: "unknown",
                roleBasis = role?.basis ?: "no_optical_data",
                roleConfidence = role?.confidence ?: "low",
                intrinsicZoomRatio = role?.intrinsicZoomRatio,
                focalLength35mmEquiv = lenses.firstOrNull()?.focalLength35mmEquiv,
                lenses = lenses,
            )
        }
    }

    private fun cameraScore(camera: CameraInfo): Int {
        val pixels = cameraPixelCount(camera)
        val level = when (camera.hardwareLevel) {
            "level_3", "full" -> 2
            "limited" -> 1
            else -> 0
        }
        val raw = if ("raw" in camera.capabilities) 1 else 0
        val tenBit = if (camera.dynamicRange.tenBitStreams) 1 else 0
        return level * 10_000_000 +
            (raw + tenBit) * 1_000_000 +
            (pixels / 100_000).toInt()
    }

    private fun cameraPixelCount(camera: CameraInfo): Long =
        camera.sensor?.pixelArray
            ?.takeIf { it.size == 2 }
            ?.let { it[0].toLong() * it[1].toLong() }
            ?: 0L

    private fun physicalScore(camera: PhysicalCameraInfo): Int {
        val pixels = camera.pixelArray
            .takeIf { it.size == 2 }
            ?.let { it[0].toLong() * it[1] }
            ?: 0L
        val level = when (camera.hardwareLevel) {
            "level_3", "full" -> 2
            "limited" -> 1
            else -> 0
        }
        return level * 10_000_000 + (pixels / 100_000).toInt()
    }

    private fun readSensor(c: CameraCharacteristics): SensorInfo? {
        val pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null
        val activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val blackLevel = runCatching {
            c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.getOffsetForIndex(0, 0)
        }.getOrNull() ?: 0
        val whiteLevel = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 0

        return SensorInfo(
            pixelArray = listOf(pixelArray.width, pixelArray.height),
            activeArray = activeArray?.let { listOf(it.width(), it.height()) }
                ?: listOf(pixelArray.width, pixelArray.height),
            physicalSizeMm = physical?.let { listOf(it.width, it.height) } ?: emptyList(),
            orientationDeg = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            colorFilter = colorFilterName(
                c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
            ),
            timestampSource = timestampSourceName(
                c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE),
            ),
            isoRange = iso?.let { listOf(it.lower, it.upper) } ?: emptyList(),
            exposureTimeRangeNs = exposure?.let { listOf(it.lower, it.upper) } ?: emptyList(),
            maxFrameDurationNs = c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION),
            blackLevel = blackLevel,
            whiteLevel = whiteLevel,
        )
    }

    private fun readControls(c: CameraCharacteristics): ControlsInfo {
        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.map(::oisModeName)
            ?: emptyList()
        val videoStab = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.map(::videoStabilizationName)
            ?: emptyList()
        val regionsAe = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)
        val regionsAf = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)
        val regionsAwb = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB)
        val maxRegions = if (regionsAe != null || regionsAf != null || regionsAwb != null) {
            MaxRegions(ae = regionsAe ?: 0, af = regionsAf ?: 0, awb = regionsAwb ?: 0)
        } else {
            null
        }
        val exposureCompensation = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?.let { range ->
                ExposureCompensation(
                    min = range.lower,
                    max = range.upper,
                    step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                        ?.toFloat() ?: 1f,
                )
            }

        return ControlsInfo(
            aeModes = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.map(::aeModeName)
                ?: emptyList(),
            afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.map(::afModeName)
                ?: emptyList(),
            awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.map(::awbModeName)
                ?: emptyList(),
            oisModes = ois,
            oisAvailable = ois,
            videoStabilizationModes = videoStab,
            videoStabilizationAvailable = videoStab,
            aeTargetFpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { FpsRange(it.lower, it.upper) }
                ?: emptyList(),
            exposureCompensation = exposureCompensation,
            aeLockAvailable = c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE),
            awbLockAvailable = c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE),
            maxRegions = maxRegions,
            focusDistanceCalibration = focusCalibrationName(
                c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION),
            ),
            scalerRotateAndCrop = c.get(
                CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES,
            )?.map(::rotateAndCropName) ?: emptyList(),
            edgeModes = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                ?.map(::edgeModeName) ?: emptyList(),
            noiseReductionModes = c.get(
                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
            )?.map(::noiseReductionName) ?: emptyList(),
            antibandingModes = c.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES,
            )?.map(::antibandingName) ?: emptyList(),
            hotPixelModes = c.get(
                CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES,
            )?.map(::hotPixelName) ?: emptyList(),
            shadingModes = c.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)
                ?.map(::shadingName) ?: emptyList(),
            aberrationModes = c.get(
                CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
            )?.map(::aberrationName) ?: emptyList(),
            distortionModes = c.get(
                CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES,
            )?.map(::distortionName) ?: emptyList(),
            toneMapModes = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
                ?.map(::toneMapName) ?: emptyList(),
            maxToneMapCurvePoints = c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS),
            colorCorrectionModes = colorCorrectionModesFor(c),
        )
    }

    private fun streamEntries(
        map: android.hardware.camera2.params.StreamConfigurationMap?,
    ): List<StreamEntry> {
        if (map == null) return emptyList()
        val entries = mutableListOf<StreamEntry>()
        val formats = listOf(
            ImageFormat.PRIVATE to StreamFormat.PRIVATE,
            ImageFormat.YUV_420_888 to StreamFormat.YUV420,
        )
        for ((imageFormat, streamFormat) in formats) {
            val sizes = runCatching { map.getOutputSizes(imageFormat) }.getOrNull() ?: continue
            for (size in sizes) {
                val duration = runCatching {
                    map.getOutputMinFrameDuration(imageFormat, size)
                }.getOrDefault(0L)
                val stall = runCatching {
                    map.getOutputStallDuration(imageFormat, size)
                }.getOrDefault(0L)
                entries += StreamEntry(size.width, size.height, streamFormat, duration, stall)
            }
        }
        return entries
    }

    private fun highSpeedEntries(
        map: android.hardware.camera2.params.StreamConfigurationMap?,
    ): List<HighSpeedEntry> {
        if (map == null) return emptyList()
        val entries = mutableListOf<HighSpeedEntry>()
        val sizes = runCatching { map.highSpeedVideoSizes }.getOrNull() ?: return emptyList()
        for (size in sizes) {
            val ranges: Array<Range<Int>> = runCatching {
                map.getHighSpeedVideoFpsRangesFor(size)
            }.getOrNull() ?: continue
            for (range in ranges) {
                // Values are frames per second on current platform versions; the
                // /1000 guard covers older HALs that reported milli-fps.
                val maxFps = if (range.upper > 1000) range.upper / 1000 else range.upper
                entries += HighSpeedEntry(size.width, size.height, maxFps)
            }
        }
        return entries
    }

    private fun facingName(value: Int?): String = when (value) {
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
        else -> "unknown"
    }

    private fun hardwareLevelName(value: Int?): String = when (value) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "external"
        else -> "unknown"
    }

    private fun colorFilterName(value: Int?): String = when (value) {
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "rgb"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> "monochrome"
        null -> "unknown"
        else -> "bayer"
    }

    private fun timestampSourceName(value: Int?): String = when (value) {
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "realtime"
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "unknown"
        else -> "unknown"
    }

    private fun aeModeName(mode: Int): String = when (mode) {
        CameraCharacteristics.CONTROL_AE_MODE_OFF -> "off"
        CameraCharacteristics.CONTROL_AE_MODE_ON -> "on"
        CameraCharacteristics.CONTROL_AE_MODE_ON_AUTO_FLASH -> "on_auto_flash"
        CameraCharacteristics.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "on_always_flash"
        CameraCharacteristics.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "on_auto_flash_redeye"
        CameraCharacteristics.CONTROL_AE_MODE_ON_EXTERNAL_FLASH -> "on_external_flash"
        else -> "mode_$mode"
    }

    private fun afModeName(mode: Int): String = when (mode) {
        CameraCharacteristics.CONTROL_AF_MODE_OFF -> "off"
        CameraCharacteristics.CONTROL_AF_MODE_AUTO -> "auto"
        CameraCharacteristics.CONTROL_AF_MODE_MACRO -> "macro"
        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "continuous_video"
        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "continuous_picture"
        CameraCharacteristics.CONTROL_AF_MODE_EDOF -> "edof"
        else -> "mode_$mode"
    }

    private fun awbModeName(mode: Int): String = when (mode) {
        CameraCharacteristics.CONTROL_AWB_MODE_OFF -> "off"
        CameraCharacteristics.CONTROL_AWB_MODE_AUTO -> "auto"
        CameraCharacteristics.CONTROL_AWB_MODE_INCANDESCENT -> "incandescent"
        CameraCharacteristics.CONTROL_AWB_MODE_FLUORESCENT -> "fluorescent"
        CameraCharacteristics.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "warm_fluorescent"
        CameraCharacteristics.CONTROL_AWB_MODE_DAYLIGHT -> "daylight"
        CameraCharacteristics.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "cloudy"
        CameraCharacteristics.CONTROL_AWB_MODE_TWILIGHT -> "twilight"
        CameraCharacteristics.CONTROL_AWB_MODE_SHADE -> "shade"
        else -> "mode_$mode"
    }

    private fun oisModeName(mode: Int): String = when (mode) {
        CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "off"
        CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON -> "on"
        else -> "mode_$mode"
    }

    private fun videoStabilizationName(mode: Int): String = when (mode) {
        CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "off"
        CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "on"
        CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION -> "preview"
        else -> "mode_$mode"
    }

    /**
     * The public field is not present in every supported Android release even
     * though it exists in the compile SDK. Read it reflectively and report an
     * empty list rather than failing the whole camera probe on older builds.
     */
    private fun colorCorrectionModesFor(c: CameraCharacteristics): List<String> =
        runCatching {
            val field = CameraCharacteristics::class.java
                .getField("COLOR_CORRECTION_AVAILABLE_MODES")
            @Suppress("UNCHECKED_CAST")
            val key = field.get(null) as CameraCharacteristics.Key<IntArray>
            c.get(key)?.map(::colorCorrectionName) ?: emptyList()
        }.getOrDefault(emptyList())

    private fun toneMapName(value: Int): String = when (value) {
        CameraCharacteristics.TONEMAP_MODE_CONTRAST_CURVE -> "contrast_curve"
        CameraCharacteristics.TONEMAP_MODE_FAST -> "fast"
        CameraCharacteristics.TONEMAP_MODE_HIGH_QUALITY -> "high_quality"
        CameraCharacteristics.TONEMAP_MODE_GAMMA_VALUE -> "gamma_value"
        CameraCharacteristics.TONEMAP_MODE_PRESET_CURVE -> "preset_curve"
        else -> "mode_$value"
    }

    private fun colorCorrectionName(value: Int): String = when (value) {
        CameraCharacteristics.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX -> "transform_matrix"
        CameraCharacteristics.COLOR_CORRECTION_MODE_FAST -> "fast"
        CameraCharacteristics.COLOR_CORRECTION_MODE_HIGH_QUALITY -> "high_quality"
        else -> "mode_$value"
    }

    private fun focusCalibrationName(value: Int?): String? = when (value) {
        CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED -> "uncalibrated"
        CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "approximate"
        CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "calibrated"
        else -> null
    }

    private fun rotateAndCropName(value: Int): String = when (value) {
        // The mode values are documented but not exposed as public constants.
        0 -> "none"
        1 -> "90"
        2 -> "180"
        3 -> "270"
        4 -> "auto"
        else -> "mode_$value"
    }

    private fun edgeModeName(value: Int): String = when (value) {
        CameraCharacteristics.EDGE_MODE_OFF -> "off"
        CameraCharacteristics.EDGE_MODE_FAST -> "fast"
        CameraCharacteristics.EDGE_MODE_HIGH_QUALITY -> "high_quality"
        CameraCharacteristics.EDGE_MODE_ZERO_SHUTTER_LAG -> "zero_shutter_lag"
        else -> "mode_$value"
    }

    private fun noiseReductionName(value: Int): String = when (value) {
        CameraCharacteristics.NOISE_REDUCTION_MODE_OFF -> "off"
        CameraCharacteristics.NOISE_REDUCTION_MODE_FAST -> "fast"
        CameraCharacteristics.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "high_quality"
        CameraCharacteristics.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> "zero_shutter_lag"
        else -> "mode_$value"
    }

    private fun antibandingName(value: Int): String = when (value) {
        CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_OFF -> "off"
        CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_50HZ -> "50hz"
        CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_60HZ -> "60hz"
        CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_AUTO -> "auto"
        else -> "mode_$value"
    }

    private fun hotPixelName(value: Int): String = when (value) {
        CameraCharacteristics.HOT_PIXEL_MODE_OFF -> "off"
        CameraCharacteristics.HOT_PIXEL_MODE_FAST -> "fast"
        CameraCharacteristics.HOT_PIXEL_MODE_HIGH_QUALITY -> "high_quality"
        else -> "mode_$value"
    }

    private fun shadingName(value: Int): String = when (value) {
        CameraCharacteristics.SHADING_MODE_OFF -> "off"
        CameraCharacteristics.SHADING_MODE_FAST -> "fast"
        CameraCharacteristics.SHADING_MODE_HIGH_QUALITY -> "high_quality"
        else -> "mode_$value"
    }

    private fun aberrationName(value: Int): String = when (value) {
        CameraCharacteristics.COLOR_CORRECTION_ABERRATION_MODE_OFF -> "off"
        CameraCharacteristics.COLOR_CORRECTION_ABERRATION_MODE_FAST -> "fast"
        CameraCharacteristics.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY -> "high_quality"
        else -> "mode_$value"
    }

    private fun distortionName(value: Int): String = when (value) {
        CameraCharacteristics.DISTORTION_CORRECTION_MODE_OFF -> "off"
        CameraCharacteristics.DISTORTION_CORRECTION_MODE_FAST -> "fast"
        CameraCharacteristics.DISTORTION_CORRECTION_MODE_HIGH_QUALITY -> "high_quality"
        else -> "mode_$value"
    }

    private fun recommendedTenBitProfile(c: CameraCharacteristics): String? = runCatching {
        when (c.get(CameraCharacteristics.REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE)) {
            DynamicRangeProfiles.HLG10 -> "hlg10"
            DynamicRangeProfiles.HDR10 -> "hdr10"
            else -> null
        }
    }.getOrNull()

    private companion object {
        /** Numeric IDs above the last listed camera are worth a read attempt. */
        const val MAX_SCAN_CAMERA_ID = 7

        val CAPABILITY_NAMES: Map<Int, String> = mapOf(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
                to "backward_compatible",
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
                to "manual_sensor",
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
                to "manual_post_processing",
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW to "raw",
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING
                to "private_reprocessing",
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS
                to "read_sensor_settings",
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE
                to "burst_capture",
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING
                to "yuv_reprocessing",
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT
                to "depth_output",
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO
                to "constrained_high_speed_video",
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING
                to "motion_tracking",
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                to "logical_multi_camera",
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME to "monochrome",
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA
                to "secure_image_data",
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA
                to "system_camera",
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT
                to "dynamic_range_ten_bit",
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
                to "ultra_high_resolution_sensor",
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING
                to "offline_processing",
        )
    }
}
