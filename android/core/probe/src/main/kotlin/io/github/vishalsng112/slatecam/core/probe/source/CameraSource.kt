package io.github.vishalsng112.slatecam.core.probe.source

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.util.Range
import io.github.vishalsng112.slatecam.core.probe.eval.HighSpeedEntry
import io.github.vishalsng112.slatecam.core.probe.eval.PresetCatalog
import io.github.vishalsng112.slatecam.core.probe.eval.PresetEvaluator
import io.github.vishalsng112.slatecam.core.probe.eval.StreamEntry
import io.github.vishalsng112.slatecam.core.probe.eval.StreamFormat
import io.github.vishalsng112.slatecam.core.probe.model.CameraInfo
import io.github.vishalsng112.slatecam.core.probe.model.ControlsInfo
import io.github.vishalsng112.slatecam.core.probe.model.DynamicRangeInfo
import io.github.vishalsng112.slatecam.core.probe.model.ExposureCompensation
import io.github.vishalsng112.slatecam.core.probe.model.FloatRange
import io.github.vishalsng112.slatecam.core.probe.model.FpsRange
import io.github.vishalsng112.slatecam.core.probe.model.LensInfo
import io.github.vishalsng112.slatecam.core.probe.model.MaxRegions
import io.github.vishalsng112.slatecam.core.probe.model.SensorInfo
import io.github.vishalsng112.slatecam.core.probe.model.ZoomInfo

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

        return ids.mapNotNull { id ->
            try {
                readCamera(manager, id, concurrentSets)
            } catch (t: Throwable) {
                warnings += "camera $id probe failed: ${t.javaClass.simpleName}: ${t.message}"
                null
            }
        }
    }

    private fun readCamera(
        manager: CameraManager,
        id: String,
        concurrentSets: Set<Set<String>>,
    ): CameraInfo {
        val c = manager.getCameraCharacteristics(id)

        val capabilityNames = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toList()
            ?.mapNotNull { CAPABILITY_NAMES[it] }
            ?: emptyList()

        val tenBit = capabilityNames.contains("dynamic_range_ten_bit")
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
            physicalIds = runCatching { c.physicalCameraIds.toList() }.getOrDefault(emptyList()),
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
            presets = presets,
        )
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

    private fun recommendedTenBitProfile(c: CameraCharacteristics): String? = runCatching {
        when (c.get(CameraCharacteristics.REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE)) {
            DynamicRangeProfiles.HLG10 -> "hlg10"
            DynamicRangeProfiles.HDR10 -> "hdr10"
            else -> null
        }
    }.getOrNull()

    private companion object {
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
