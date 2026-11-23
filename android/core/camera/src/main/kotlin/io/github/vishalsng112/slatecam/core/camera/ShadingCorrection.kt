package io.github.vishalsng112.slatecam.core.camera

/**
 * Lens-shading (vignette) correction policy.
 *
 * Camera2 cameras always advertise `OFF`; only some also advertise `FAST` and
 * `HIGH_QUALITY`. Leaving the request unset lets the HAL choose, but the
 * controller previously forced `OFF`, which made raw sensor vignetting visible
 * in the preview and in clips. Correction must be an explicit, per-sensor
 * choice: on by default, off only when the operator asks for it.
 */
object ShadingCorrection {

    /** Mode names, as emitted by [CameraController]'s capability reader. */
    const val OFF = "off"
    const val FAST = "fast"
    const val HIGH_QUALITY = "high_quality"

    /** True when this sensor can actually correct shading. */
    fun isSupported(available: List<String>): Boolean =
        FAST in available || HIGH_QUALITY in available

    /**
     * The mode to put in the capture request.
     *
     * Correction is preferred in quality order. When it is disabled, or the
     * sensor does not offer a correcting mode, the result is `off` if the
     * sensor accepts it (null means "leave the HAL default").
     */
    fun requestedMode(available: List<String>, enabled: Boolean): String? {
        if (enabled && isSupported(available)) {
            return when {
                HIGH_QUALITY in available -> HIGH_QUALITY
                FAST in available -> FAST
                else -> OFF
            }
        }
        return if (OFF in available) OFF else null
    }
}
