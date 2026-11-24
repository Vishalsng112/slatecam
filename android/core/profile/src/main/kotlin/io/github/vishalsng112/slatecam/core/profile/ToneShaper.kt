package io.github.vishalsng112.slatecam.core.profile

import kotlin.math.exp
import kotlin.math.pow

/**
 * Working-space tone shaping shared by profiles and the future linear shader.
 *
 * Inputs and outputs are normalised scene-linear values. The order is:
 * 1. contrast around the 18% pivot;
 * 2. a subtle shadow-toe lift below the pivot;
 * 3. a soft highlight roll-off above the knee.
 *
 * These functions are pure references: the shader and the CPU tests must agree
 * on the curve before a profile is allowed to rely on it.
 */
object ToneShaper {

    fun shape(
        linear: Float,
        contrast: Float,
        pivot: Float,
        highlightRolloffKnee: Float,
        shadowToe: Float,
    ): Float {
        val p = pivot.coerceIn(MIN_PIVOT, 1f)
        val c = contrast.coerceIn(MIN_CONTRAST, MAX_CONTRAST)
        var x = linear.coerceIn(0f, 4f)

        // Contrast around middle grey. At contrast 1 this is the identity.
        x = if (x <= 0f) 0f else (p * (x / p).pow(c)).coerceAtMost(MAX_LINEAR)

        if (shadowToe > 0f && x < p) {
            val t = (x / p).coerceIn(0f, 1f)
            x += shadowToe.coerceIn(0f, 1f) * SHADOW_LIFT * (1f - t) * (1f - t)
        }

        val knee = highlightRolloffKnee.coerceIn(0.1f, 1f)
        if (x > knee && knee < 1f) {
            val range = (1f - knee).coerceAtLeast(0.001f)
            x = knee + range * (1f - exp(-(x - knee) / range))
        }

        return x.coerceIn(0f, 1f)
    }

    private const val MIN_PIVOT = 0.001f
    private const val MIN_CONTRAST = 0.01f
    private const val MAX_CONTRAST = 8f
    private const val MAX_LINEAR = 8f
    private const val SHADOW_LIFT = 0.05f
}
