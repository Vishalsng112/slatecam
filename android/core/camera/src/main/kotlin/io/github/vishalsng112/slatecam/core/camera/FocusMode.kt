package io.github.vishalsng112.slatecam.core.camera

/**
 * The focus-mode catalog shared by the controller, the bridge and its tests.
 *
 * Wire values are the Camera2 `CONTROL_AF_MODE` names, plus `software` for the
 * app's contrast-autofocus engine. [defaultFor] always prefers the camera's
 * continuous-video AF (CAF); the software engine is never selected silently.
 */
object FocusMode {
    const val OFF = "off"
    const val AUTO = "auto"
    const val MACRO = "macro"
    const val CONTINUOUS_VIDEO = "continuous_video"
    const val CONTINUOUS_PICTURE = "continuous_picture"
    const val EDOF = "edof"

    /** Software contrast autofocus implemented in the app, not a Camera2 mode. */
    const val SOFTWARE = "software"

    /** Operator-facing order: continuous modes first, manual last, SAF after. */
    private val order = listOf(
        CONTINUOUS_VIDEO,
        CONTINUOUS_PICTURE,
        AUTO,
        MACRO,
        EDOF,
        OFF,
    )

    /** All halo modes the active camera reports, plus SAF when it has a range. */
    fun options(halModes: List<String>, hasSoftware: Boolean): List<String> {
        val reported = halModes.toSet()
        return buildList {
            order.filterTo(this) { it in reported }
            if (hasSoftware) add(SOFTWARE)
        }
    }

    /**
     * CAF is the default. The fallback chain only exists for sensors that do
     * not advertise continuous video; SAF is never an implicit default.
     */
    fun defaultFor(available: List<String>): String {
        val cameraModes = available.filter { it in order }
        return when {
            CONTINUOUS_VIDEO in cameraModes -> CONTINUOUS_VIDEO
            CONTINUOUS_PICTURE in cameraModes -> CONTINUOUS_PICTURE
            AUTO in cameraModes -> AUTO
            MACRO in cameraModes -> MACRO
            EDOF in cameraModes -> EDOF
            else -> OFF
        }
    }

    /** Camera2 value for a HAL focus mode, or `null` for manual/SAF. */
    fun toHal(mode: String): Int? = when (mode) {
        CONTINUOUS_VIDEO -> android.hardware.camera2.CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        CONTINUOUS_PICTURE -> android.hardware.camera2.CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        AUTO -> android.hardware.camera2.CameraCharacteristics.CONTROL_AF_MODE_AUTO
        MACRO -> android.hardware.camera2.CameraCharacteristics.CONTROL_AF_MODE_MACRO
        EDOF -> android.hardware.camera2.CameraCharacteristics.CONTROL_AF_MODE_EDOF
        OFF -> android.hardware.camera2.CameraCharacteristics.CONTROL_AF_MODE_OFF
        else -> null
    }

    /** Wire name for a Camera2 mode, or `null` when it is not a known mode. */
    fun fromHal(value: Int): String? = when (value) {
        android.hardware.camera2.CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> CONTINUOUS_VIDEO
        android.hardware.camera2.CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE ->
            CONTINUOUS_PICTURE
        android.hardware.camera2.CameraCharacteristics.CONTROL_AF_MODE_AUTO -> AUTO
        android.hardware.camera2.CameraCharacteristics.CONTROL_AF_MODE_MACRO -> MACRO
        android.hardware.camera2.CameraCharacteristics.CONTROL_AF_MODE_EDOF -> EDOF
        android.hardware.camera2.CameraCharacteristics.CONTROL_AF_MODE_OFF -> OFF
        else -> null
    }

    /** True only for a HAL autofocus mode; manual and SAF drive the lens. */
    fun isAutomatic(mode: String): Boolean = mode != OFF && toHal(mode) != null

    fun isSoftware(mode: String): Boolean = mode == SOFTWARE

    fun isKnown(mode: String): Boolean = mode == SOFTWARE || toHal(mode) != null

    fun isContinuous(mode: String): Boolean =
        mode == CONTINUOUS_VIDEO || mode == CONTINUOUS_PICTURE
}
