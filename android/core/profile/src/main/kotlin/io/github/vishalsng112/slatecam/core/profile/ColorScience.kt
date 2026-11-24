package io.github.vishalsng112.slatecam.core.profile

import kotlin.math.pow

/**
 * Manual colour-science trim applied on top of the active picture profile.
 *
 * The profile remains the base look (standard/log/mono/LUT). These values are
 * the operator's live adjustments: exposure in stops, contrast and saturation
 * multipliers, and a simple warm/cool + green/magenta white-balance trim.
 *
 * Pure data so the UI, bridge and tests share one definition of the ranges.
 */
data class ColorScienceTuning(
    val exposureEv: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
) {
    fun normalized(): ColorScienceTuning = copy(
        exposureEv = exposureEv.coerceIn(MIN_EV, MAX_EV),
        contrast = contrast.coerceIn(MIN_CONTRAST, MAX_CONTRAST),
        saturation = saturation.coerceIn(MIN_SATURATION, MAX_SATURATION),
        temperature = temperature.coerceIn(-1f, 1f),
        tint = tint.coerceIn(-1f, 1f),
    )

    fun exposureMultiplier(): Float = 2f.pow(exposureEv)

    /**
     * Red/green/blue channel trim, normalised so overall luma stays close to
     * the input. Warm raises red and lowers blue; positive tint adds magenta
     * and removes green.
     */
    fun whiteBalanceGains(): FloatArray {
        val t = temperature.coerceIn(-1f, 1f)
        val g = tint.coerceIn(-1f, 1f)
        val red = (1f + 0.18f * t + 0.06f * g).coerceAtLeast(0.05f)
        val green = (1f - 0.10f * g).coerceAtLeast(0.05f)
        val blue = (1f - 0.18f * t + 0.06f * g).coerceAtLeast(0.05f)
        val mean = (red + green + blue) / 3f
        return floatArrayOf(red / mean, green / mean, blue / mean)
    }

    companion object {
        const val MIN_EV = -2f
        const val MAX_EV = 2f
        const val MIN_CONTRAST = 0.25f
        const val MAX_CONTRAST = 2f
        const val MIN_SATURATION = 0f
        const val MAX_SATURATION = 2f

        val IDENTITY = ColorScienceTuning()
    }
}
