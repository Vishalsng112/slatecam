package io.github.vishalsng112.slatecam.core.camera

/**
 * Focus-mode policy shared by the controller and its tests.
 *
 * Names match the probe/Camera2 wire values. Video defaults to continuous
 * autofocus; stills-oriented modes are only selected when the sensor does not
 * offer continuous video.
 */
object FocusMode {
    const val OFF = "off"
    const val AUTO = "auto"
    const val MACRO = "macro"
    const val CONTINUOUS_VIDEO = "continuous_video"
    const val CONTINUOUS_PICTURE = "continuous_picture"
    const val EDOF = "edof"

    fun defaultFor(available: List<String>): String = when {
        CONTINUOUS_VIDEO in available -> CONTINUOUS_VIDEO
        CONTINUOUS_PICTURE in available -> CONTINUOUS_PICTURE
        AUTO in available -> AUTO
        else -> available.firstOrNull { it != OFF } ?: OFF
    }

    fun isAutomatic(mode: String): Boolean = mode != OFF
}
