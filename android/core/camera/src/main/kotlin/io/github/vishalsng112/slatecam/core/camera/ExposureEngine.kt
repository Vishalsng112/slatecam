package io.github.vishalsng112.slatecam.core.camera

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Exposure modes exposed by the capture engine.
 *
 * Av (aperture priority) is deliberately absent: phone apertures are fixed and
 * Camera2 has no key for selecting one. The three modes that map to real
 * controls are P, S (Tv), and M.
 */
enum class ExposureMode(val wireValue: String) {
    /** Stock HAL auto-exposure and auto-focus. */
    PROGRAM("P"),

    /** Shutter priority: the shutter is fixed and our loop drives ISO. */
    SHUTTER_PRIORITY("S"),

    /** Fully manual ISO and shutter. */
    MANUAL("M"),
    ;

    companion object {
        val wireValues: List<String> = entries.map { it.wireValue }

        fun fromWire(value: String): ExposureMode =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: PROGRAM
    }
}

/**
 * Brightness controller for shutter-priority mode.
 *
 * The GPU already produces a 64-bin luma histogram at roughly 5 Hz. This loop
 * turns the requested percentile of that histogram into an EV error and moves
 * ISO toward the target. It is intentionally conservative: a dead band stops
 * chattering around the target, and the per-second EV limit prevents the
 * visible jumps that make a manual shutter look broken.
 *
 * This is not the user-facing "Pro AE" loop from the backlog; it only has to
 * hold brightness while the shutter is fixed. It is pure Kotlin so the
 * behaviour can be tested without a camera.
 */
class AutoIsoController(
    private val targetLuma: Float = 0.45f,
    private val measurementPercentile: Float = 0.5f,
    private val deadBandEv: Float = 0.08f,
    private val maxEvPerSecond: Float = 2f,
    private val gain: Float = 0.6f,
) {
    /**
     * Returns the ISO for the next metering update.
     *
     * @param histogram GPU luma histogram, one bin per equal luma step.
     * @param currentIso ISO currently applied to the sensor.
     * @param elapsedSeconds time since the previous update.
     */
    fun update(
        histogram: IntArray,
        currentIso: Int,
        isoMin: Int,
        isoMax: Int,
        elapsedSeconds: Float,
    ): Int {
        if (histogram.isEmpty() || isoMin <= 0 || isoMax <= isoMin) return currentIso

        val total = histogram.sumOf { it.coerceAtLeast(0) }
        if (total <= 0) return currentIso

        val measured = percentileLuma(histogram, total)
        if (measured <= 0f) return currentIso

        val errorEv = log2(targetLuma / measured)
        if (abs(errorEv) < deadBandEv) return currentIso

        val maxStepEv = maxEvPerSecond * elapsedSeconds.coerceIn(MIN_STEP_SECONDS, MAX_STEP_SECONDS)
        val correctionEv = (errorEv * gain).coerceIn(-maxStepEv, maxStepEv)
        val next = currentIso * 2f.pow(correctionEv)
        return next.roundToInt().coerceIn(isoMin, isoMax)
    }

    private fun percentileLuma(histogram: IntArray, total: Int): Float {
        val requested = (total * measurementPercentile.coerceIn(0f, 1f))
            .toInt()
            .coerceIn(1, total)
        var cumulative = 0
        for (bin in histogram.indices) {
            cumulative += histogram[bin].coerceAtLeast(0)
            if (cumulative >= requested) {
                return (bin + 0.5f) / histogram.size
            }
        }
        return (histogram.size - 0.5f) / histogram.size
    }

    private companion object {
        const val MIN_STEP_SECONDS = 0.05f
        const val MAX_STEP_SECONDS = 0.5f
    }
}
