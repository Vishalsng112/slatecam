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
const val SCHEMA_VERSION = "1.2.0"

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
)

@Serializable
data class DynamicRangeInfo(
    @SerialName("ten_bit_streams") val tenBitStreams: Boolean,
    val profiles: List<String> = emptyList(),
)

@Serializable
data class PresetInfo(
    val supported: Boolean,
    val reason: String? = null,
    val detail: String? = null,
    val format: String? = null,
    @SerialName("min_frame_duration_ns") val minFrameDurationNs: Long? = null,
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
