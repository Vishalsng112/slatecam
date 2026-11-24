package io.github.vishalsng112.slatecam.core.probe.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val SCHEMA_ID = "slatecam.probe"

/**
 * Bumped from 1.0.0: presets gained [PresetInfo.basis] and [PresetInfo.verified].
 * Camera2 does not expose bit depth in the stream configuration map, so 10-bit
 * support at a given size can only ever be claimed until an actual session is
 * created. See docs/probe-schema.md.
 *
 * 1.7.0: the 10-bit path is verified per size (UHD and FHD), so
 * `hevc_main10_encode_fhd` / `camera_hlg10_session_fhd` sit beside the UHD
 * checks and an UHD pass no longer promotes an FHD preset.
 *
 * 1.8.0: encoder limits and HDR feature, per-device audio inputs, gyro,
 * RAW sizes, max frame duration and lens pose/intrinsics. The verification
 * checks now read bit depth from the SPS, not just `general_profile_idc`.
 *
 * 1.9.0: lens roles (`default` / `ultrawide` / `tele`) with basis and
 * confidence, 35 mm-equivalent focal lengths, unlisted-camera scan, and the OEM
 * auxiliary-camera exposure policy.
 *
 * 1.11.0: tone-map and colour-correction mode lists for the future manual
 * colour pipeline.
 * 1.10.0: 4:3 presets (`fhd43_*`) and per-lens HLG10 session checks, so every
 * camera in a shoot set carries its own verified 10-bit verdict.
 */
const val SCHEMA_VERSION = "1.11.0"

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
    /** OEM policy that hides auxiliary lenses from third-party packages. */
    @SerialName("camera_aux") val cameraAux: CameraAuxInfo? = null,
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
    /** Motion-sensor facts used by the gyro sidecar; absent when not collected. */
    val gyro: GyroInfo? = null,
)

@Serializable
data class GyroInfo(
    val available: Boolean,
    /** Fastest sampling rate the sensor advertises (1_000_000 / min_delay_us). */
    @SerialName("max_rate_hz") val maxRateHz: Double? = null,
    @SerialName("min_delay_us") val minDelayUs: Int? = null,
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
    /**
     * Lens role within this facing: `default`, `ultrawide`, `tele` or `unknown`.
     * Relative on purpose — a phone's "wide" is another phone's "ultrawide".
     */
    val role: String = "unknown",
    /** intrinsic_zoom_ratio | fov_ratio | absolute_fov | only_camera | no_optical_data */
    @SerialName("role_basis") val roleBasis: String = "unknown",
    /** high | medium | low */
    @SerialName("role_confidence") val roleConfidence: String = "low",
    /** Zoom relative to the assessed default lens, using CameraX's convention. */
    @SerialName("intrinsic_zoom_ratio") val intrinsicZoomRatio: Double? = null,
    /** 35 mm-equivalent focal length from the first lens, when measurable. */
    @SerialName("focal_length_35mm_equiv") val focalLength35mmEquiv: Double? = null,
    /** True when the ID appears in CameraManager.cameraIdList. */
    val exposed: Boolean = true,
    /** Set for unlisted IDs: whether this package can actually open them. */
    val openable: Boolean? = null,
    /** RAW sensor output sizes, as `WxH`; empty when RAW is not offered. */
    @SerialName("raw_sizes") val rawSizes: List<String> = emptyList(),
    /** LENS_POSE_TRANSLATION (x, y, z) in millimetres, when exposed. */
    @SerialName("lens_pose_translation") val lensPoseTranslation: List<Float>? = null,
    /** LENS_POSE_ROTATION quaternion (x, y, z, w), when exposed. */
    @SerialName("lens_pose_rotation") val lensPoseRotation: List<Float>? = null,
    /** LENS_INTRINSIC_CALIBRATION (fx, fy, cx, cy, skew), when exposed. */
    @SerialName("lens_intrinsics") val lensIntrinsics: List<Float>? = null,
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
    /** 35 mm-equivalent focal length from the first lens, when measurable. */
    @SerialName("focal_length_35mm_equiv") val focalLength35mmEquiv: Double? = null,
    val role: String = "unknown",
    @SerialName("role_basis") val roleBasis: String = "unknown",
    @SerialName("role_confidence") val roleConfidence: String = "low",
    @SerialName("intrinsic_zoom_ratio") val intrinsicZoomRatio: Double? = null,
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
    /** SENSOR_INFO_MAX_FRAME_DURATION in nanoseconds, when reported. */
    @SerialName("max_frame_duration_ns") val maxFrameDurationNs: Long? = null,
    @SerialName("black_level") val blackLevel: Int,
    @SerialName("white_level") val whiteLevel: Int,
)

@Serializable
data class LensInfo(
    @SerialName("focal_length_mm") val focalLengthMm: Float,
    val aperture: Float,
    @SerialName("min_focus_distance_diopters") val minFocusDistanceDiopters: Float,
    val hyperfocal: Float = 0f,
    /** 35 mm-equivalent focal length when sensor geometry is known. */
    @SerialName("focal_length_35mm_equiv") val focalLength35mmEquiv: Double? = null,
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
    @SerialName("tone_map_modes") val toneMapModes: List<String> = emptyList(),
    @SerialName("max_tone_map_curve_points") val maxToneMapCurvePoints: Int? = null,
    @SerialName("color_correction_modes") val colorCorrectionModes: List<String> = emptyList(),
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
    /** FEATURE_HdrEditing: whether the codec accepts HDR metadata edits. */
    @SerialName("hdr_editing") val hdrEditing: Boolean? = null,
    /** Surface input accepts COLOR_FormatYUVP010, i.e. 10-bit buffers. */
    @SerialName("p010_input") val p010Input: Boolean = false,
    /** getBitrateRange() for this encoder, in bits per second. */
    @SerialName("bitrate_range") val bitrateRange: IntRange? = null,
    /** VideoCapabilities width range, when reported. */
    @SerialName("width_range") val widthRange: IntRange? = null,
    /** VideoCapabilities height range, when reported. */
    @SerialName("height_range") val heightRange: IntRange? = null,
    @SerialName("width_alignment") val widthAlignment: Int = 0,
    @SerialName("height_alignment") val heightAlignment: Int = 0,
    /** Frame-rate ceiling per preset size; absent sizes are not offered. */
    @SerialName("size_limits") val sizeLimits: List<EncoderSizeLimit> = emptyList(),
)

@Serializable
data class EncoderSizeLimit(
    val width: Int,
    val height: Int,
    @SerialName("max_fps") val maxFps: Double? = null,
    /**
     * True when the size satisfies the encoder's reported range and alignment.
     * False means the platform's constraint maths says no, but surface input
     * still accepted the size (observed on MediaTek), so treat it as claimed and
     * unverified rather than unsupported.
     */
    val aligned: Boolean = true,
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
    /** Per-device rates and channel counts, not just the device class. */
    @SerialName("input_device_details") val inputDeviceDetails: List<AudioInputDevice> = emptyList(),
    @SerialName("effects_available") val effectsAvailable: List<String> = emptyList(),
)

@Serializable
data class AudioInputDevice(
    /** builtin_mic / usb_device / usb_headset / wired_headset / ... */
    val type: String,
    @SerialName("sample_rates") val sampleRates: List<Int> = emptyList(),
    @SerialName("channel_counts") val channelCounts: List<Int> = emptyList(),
)

/**
 * The OEM policy that controls auxiliary-camera exposure. Android has no
 * standard "show me every sensor" API; vendors commonly gate extra lenses with
 * a system property (for example `vendor.camera.aux.packagelist`). These values
 * explain why a device with three rear lenses may expose only one camera ID.
 */
@Serializable
data class CameraAuxInfo(
    @SerialName("allowlist_property") val allowlistProperty: String? = null,
    @SerialName("allowlisted_packages") val allowlistedPackages: List<String> = emptyList(),
    @SerialName("this_app_allowlisted") val thisAppAllowlisted: Boolean? = null,
    @SerialName("expose_aux_property") val exposeAuxProperty: String? = null,
    @SerialName("expose_aux_value") val exposeAuxValue: String? = null,
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
