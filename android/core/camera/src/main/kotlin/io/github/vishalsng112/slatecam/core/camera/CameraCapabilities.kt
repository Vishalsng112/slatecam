package io.github.vishalsng112.slatecam.core.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Capabilities read from one camera, in a form the mode engine can compare across
 * devices and lenses.
 *
 * Android exposes different sizes, frame rates, autofocus support and dynamic
 * ranges per camera. Nothing here assumes that the main, ultra-wide, tele or
 * front camera share a configuration.
 */
data class CameraSize(val width: Int, val height: Int)

data class CameraCapabilities(
    val cameraId: String,
    val facing: String,
    val hardwareLevel: String,
    val sizes: List<CameraSize>,
    /** Exact AE frame-rate ranges, i.e. fps the camera can frame-lock. */
    val exactFrameRates: Set<Int>,
    val tenBitClaimed: Boolean,
    val afModes: List<String>,
    val zoomRange: ClosedFloatingPointRange<Float>?,
) {
    val autoFocusSupported: Boolean
        get() = afModes.any { it == "auto" || it == "continuous_video" }

    fun supports(width: Int, height: Int): Boolean =
        sizes.any { it.width == width && it.height == height }

    fun supportsExactFrameRate(fps: Int): Boolean = fps in exactFrameRates
}

object CameraCapabilitiesReader {

    fun read(context: Context, cameraId: String): CameraCapabilities? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val c = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
            ?: return null

        val sizes = runCatching {
            c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                ?.distinct()
                ?.sortedByDescending { it.width.toLong() * it.height }
                ?.map { CameraSize(it.width, it.height) }
                ?: emptyList()
        }.getOrDefault(emptyList())

        val exactRates = runCatching {
            c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.lower == it.upper }
                ?.map { it.lower }
                ?.toSet()
                ?: emptySet()
        }.getOrDefault(emptySet())

        val capabilities = runCatching {
            c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList()
                ?: emptyList()
        }.getOrDefault(emptyList())

        val afModes = runCatching {
            c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.map(::afModeName)
                ?: emptyList()
        }.getOrDefault(emptyList())

        return CameraCapabilities(
            cameraId = cameraId,
            facing = facingName(c.get(CameraCharacteristics.LENS_FACING)),
            hardwareLevel = hardwareLevelName(
                c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            ),
            sizes = sizes,
            exactFrameRates = exactRates,
            tenBitClaimed = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT
                in capabilities,
            afModes = afModes,
            zoomRange = runCatching {
                c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                    it.lower..it.upper
                }
            }.getOrNull(),
        )
    }

    private fun facingName(facing: Int?): String = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
        else -> "unknown"
    }

    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level_3"
        else -> "unknown"
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
}
