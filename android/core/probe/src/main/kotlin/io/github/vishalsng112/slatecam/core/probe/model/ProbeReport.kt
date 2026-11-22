package io.github.vishalsng112.slatecam.core.probe.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val SCHEMA_ID = "slatecam.probe"

/**
 * Bumped from 1.0.0: presets gained [PresetInfo.basis] and [PresetInfo.verified].
 * Camera2 does not expose bit depth in the stream configuration map, so 10-bit
 * support at a given size can only ever be claimed until an actual session is
 * created. See docs/probe-schema.md.
 */
const val SCHEMA_VERSION = "1.6.0"

@Serializable
data class ProbeReport(
    val schema: String = SCHEMA_ID,
    @SerialName("schema_version") val schemaVersion: String = SCHEMA_VERSION,
    val kind: String = "summary",
    val report: ReportMeta,
    val device: DeviceInfo,
    val system: SystemInfo,
    val displays: List<DisplayInfo> = emptyList(),
    val cameras: List<CameraInfo> = emptyList(),
    val encoders: List<EncoderInfo> = emptyList(),
    val audio: AudioInfo? = null,
    val tier: TierVerdict,
    val verification: Verification = Verification(ran = false),
    @SerialName("quirks_applied") val quirksApplied: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class ReportMeta(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("prober_version") val proberVersion: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("duration_ms") val durationMs: Long,
)

@Serializable
data class DeviceInfo(
    @SerialName("device_key") val deviceKey: String,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val board: String,
    val hardware: String,
    val fingerprint: String,
    @SerialName("build_id") val buildId: String,
    @SerialName("android_release") val androidRelease: String,
    val sdk: Int,
    @SerialName("security_patch") val securityPatch: String,
    @SerialName("soc_manufacturer") val socManufacturer: String,
    @SerialName("soc_model") val socModel: String,
    val abis: List<String>,
    @SerialName("is_emulator") val isEmulator: Boolean,
    @SerialName("is_low_ram") val isLowRam: Boolean,
    @SerialName("reported_by") val reportedBy: String = "owner",
)

@Serializable
data class SystemInfo(
    @SerialName("thermal_status") val thermalStatus: String,
    @SerialName("sustained_performance_supported") val sustainedPerformanceSupported: Boolean? = null,
    @SerialName("total_ram_mb") val totalRamMb: Long,
    @SerialName("free_storage_mb") val freeStorageMb: Long,
    @SerialName("native_page_size_kb") val nativePageSizeKb: Long,
)

@Serializable
data class DisplayInfo(
    val id: Int,
    /** Refresh rate of the mode the display is currently in. */
    @SerialName("refresh_hz") val refreshHz: Int,
    /** Highest refresh rate the panel supports; null when not reported. */
    @SerialName("max_refresh_hz") val maxRefreshHz: Int? = null,
    @SerialName("wide_color_gamut") val wideColorGamut: Boolean,
    @SerialName("hdr_types") val hdrTypes: List<String> = emptyList(),
    @SerialName("max_luminance_nits") val maxLuminanceNits: Float = 0f,
)

@Serializable
data class CameraInfo(
    val id: String,
    val facing: String,
    @SerialName("hardware_level") val hardwareLevel: String,
    @SerialName("logical_multi_camera") val logicalMultiCamera: Boolean,
    @SerialName("physical_ids") val physicalIds: List<String> = emptyList(),
    /** Characteristics of the physical sub-cameras, when the platform exposes them. */
    @SerialName("physical_cameras") val physicalCameras: List<PhysicalCameraInfo> = emptyList(),
    @SerialName("concurrent_with") val concurrentWith: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val sensor: SensorInfo? = null,
    val lenses: List<LensInfo> = emptyList(),
    val zoom: ZoomInfo? = null,
    val controls: ControlsInfo? = null,
    @SerialName("dynamic_range") val dynamicRange: DynamicRangeInfo,
    val presets: Map<String, PresetInfo> = emptyMap(),
)

@Serializable
data class PhysicalCameraInfo(
    val id: String,
    val facing: String? = null,
    @SerialName("hardware_level") val hardwareLevel: String? = null,
    @SerialName("focal_lengths_mm") val focalLengthsMm: List<Float> = emptyList(),
    @SerialName("sensor_size_mm") val sensorSizeMm: List<Float> = emptyList(),
    @SerialName("pixel_array") val pixelArray: List<Int> = emptyList(),
    @SerialName("horizontal_fov_deg") val horizontalFovDeg: Float? = null,
    /** ultrawide | wide | tele, relative to the other physical cameras */
    @SerialName("lens_role") val lensRole: String? = null,
)

@Serializable
data class SensorInfo(
    @SerialName("pixel_array") val pixelArray: List<Int>,
    @SerialName("active_array") val activeArray: List<Int>,
    @SerialName("physical_size_mm") val physicalSizeMm: List<Float>,
    @SerialName("orientation_deg") val orientationDeg: Int,
    @SerialName("color_filter") val colorFilter: String,
    @SerialName("timestamp_source") val timestampSource: String,
    @SerialName("iso_range") val isoRange: List<Int>,
    @SerialName("exposure_time_range_ns") val exposureTimeRangeNs: List<Long>,
    @SerialName("black_level") val blackLevel: Int,
    @SerialName("white_level") val whiteLevel: Int,
)

@Serializable
data class LensInfo(
    @SerialName("focal_length_mm") val focalLengthMm: Float,
    val aperture: Float,
    @SerialName("min_focus_distance_diopters") val minFocusDistanceDiopters: Float,
    val hyperfocal: Float = 0f,
)

@Serializable
data class ZoomInfo(
    @SerialName("max_digital") val maxDigital: Float,
    @SerialName("available_focal_lengths_mm") val availableFocalLengthsMm: List<Float> = emptyList(),
    @SerialName("ratio_range") val ratioRange: FloatRange? = null,
)

@Serializable
data class FloatRange(
    val min: Float,
    val max: Float,
)

@Serializable
data class IntRange(
    val min: Int,
    val max: Int,
)

@Serializable
data class FpsRange(
    val min: Int,
    val max: Int,
)

@Serializable
data class ExposureCompensation(
    val min: Int,
    val max: Int,
    val step: Float,
)

@Serializable
data class MaxRegions(
    val ae: Int,
    val af: Int,
    val awb: Int,
)

@Serializable
data class ControlsInfo(
    @SerialName("ae_modes") val aeModes: List<String> = emptyList(),
    @SerialName("af_modes") val afModes: List<String> = emptyList(),
    @SerialName("awb_modes") val awbModes: List<String> = emptyList(),
    @SerialName("ois_modes") val oisModes: List<String> = emptyList(),
    @SerialName("ois_available") val oisAvailable: List<String> = emptyList(),
    @SerialName("video_stabilization_modes") val videoStabilizationModes: List<String> = emptyList(),
    @SerialName("video_stabilization_available") val videoStabilizationAvailable: List<String> = emptyList(),
    /** Frame-rate ranges selectable with auto-exposure; min frame duration is not enough. */
    @SerialName("ae_target_fps_ranges") val aeTargetFpsRanges: List<FpsRange> = emptyList(),
    @SerialName("exposure_compensation") val exposureCompensation: ExposureCompensation? = null,
    @SerialName("ae_lock_available") val aeLockAvailable: Boolean? = null,
    @SerialName("awb_lock_available") val awbLockAvailable: Boolean? = null,
    @SerialName("max_regions") val maxRegions: MaxRegions? = null,
    @SerialName("focus_distance_calibration") val focusDistanceCalibration: String? = null,
    @SerialName("scaler_rotate_and_crop") val scalerRotateAndCrop: List<String> = emptyList(),
    @SerialName("edge_modes") val edgeModes: List<String> = emptyList(),
    @SerialName("noise_reduction_modes") val noiseReductionModes: List<String> = emptyList(),
    @SerialName("antibanding_modes") val antibandingModes: List<String> = emptyList(),
    @SerialName("hot_pixel_modes") val hotPixelModes: List<String> = emptyList(),
    @SerialName("shading_modes") val shadingModes: List<String> = emptyList(),
    @SerialName("aberration_modes") val aberrationModes: List<String> = emptyList(),
    @SerialName("distortion_modes") val distortionModes: List<String> = emptyList(),
)

@Serializable
data class DynamicRangeInfo(
    @SerialName("ten_bit_streams") val tenBitStreams: Boolean,
    val profiles: List<String> = emptyList(),
    /** Profile the device recommends for ten-bit capture, when it says. */
    @SerialName("recommended_profile") val recommendedProfile: String? = null,
)

@Serializable
data class PresetInfo(
    val supported: Boolean,
    val reason: String? = null,
    val detail: String? = null,
    val format: String? = null,
    @SerialName("min_frame_duration_ns") val minFrameDurationNs: Long? = null,
    /** Time the pipeline may stall per frame for this stream; used to size queues. */
    @SerialName("stall_duration_ns") val stallDurationNs: Long? = null,
    val profiles: List<String> = emptyList(),
    /**
     * `stream_map` = derived from ScalerStreamConfigurationMap.
     * `capability_only` = device advertises the capability, but per-size support
     * can only be proven by configuring a real session.
     */
    val basis: String = "stream_map",
    /** pass | fail | not_run */
    val verified: String = "not_run",
)

@Serializable
data class EncoderInfo(
    val mime: String,
    val name: String,
    val hardware: Boolean,
    @SerialName("software_only") val softwareOnly: Boolean,
    val vendor: Boolean,
    @SerialName("surface_input") val surfaceInput: Boolean,
    val profiles: List<EncoderProfile> = emptyList(),
    @SerialName("main10_claimed") val main10Claimed: Boolean = false,
    /** pass | fail | not_run */
    @SerialName("main10_verified") val main10Verified: String = "not_run",
    @SerialName("max_instances") val maxInstances: Int = 0,
)

@Serializable
data class EncoderProfile(
    val profile: String,
    val level: String,
)

@Serializable
data class AudioInfo(
    @SerialName("sample_rates") val sampleRates: List<Int>,
    @SerialName("max_input_channels") val maxInputChannels: Int,
    @SerialName("pcm_24bit") val pcm24Bit: Boolean,
    @SerialName("low_latency") val lowLatency: Boolean,
    @SerialName("unprocessed_supported") val unprocessedSupported: Boolean = false,
    @SerialName("input_devices") val inputDevices: List<String> = emptyList(),
    @SerialName("effects_available") val effectsAvailable: List<String> = emptyList(),
)

@Serializable
data class TierVerdict(
    val name: String,
    val missing: List<String> = emptyList(),
    val unverified: List<String> = emptyList(),
    val reasons: List<String> = emptyList(),
)

@Serializable
data class Verification(
    val ran: Boolean,
    val checks: List<VerificationCheck> = emptyList(),
)

@Serializable
data class VerificationCheck(
    val id: String,
    val status: String,
    val detail: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** Encoded width, when the check exercised a specific size. */
    val width: Int? = null,
    val height: Int? = null,
)
