package io.github.vishalsng112.slatecam.core.profile

import kotlin.math.log2
import kotlin.math.pow

/**
 * SlateLog: the project's log transfer function.
 *
 * Anchors:
 * - 0 linear maps to 0.09 (lifted black)
 * - 18% linear grey maps to 0.40
 * - 100% linear white maps to 0.754
 *
 * Encoding is `0.738 + 0.169 * log2(linear + 0.07)`, clamped to [0, 1]. The
 * 0.07 toe is what lifts shadows; without it, a log curve crushes dark scenes
 * instead of flattening them. Decoding is the exact inverse.
 */
object SlateLog {

    const val SLOPE = 0.169f
    const val OFFSET = 0.738f
    const val TOE = 0.07f

    fun encode(linear: Float): Float {
        val x = linear.coerceAtLeast(0f)
        return (OFFSET + SLOPE * log2((x + TOE).toDouble()).toFloat()).coerceIn(0f, 1f)
    }

    fun decode(codeValue: Float): Float {
        val code = codeValue.coerceIn(0f, 1f)
        return (2f.pow((code - OFFSET) / SLOPE) - TOE).coerceAtLeast(0f)
    }
}
