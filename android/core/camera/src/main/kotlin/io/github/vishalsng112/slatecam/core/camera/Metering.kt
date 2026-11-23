package io.github.vishalsng112.slatecam.core.camera

import kotlin.math.exp
import kotlin.math.log2

/**
 * Exposure metering patterns modelled on a mirrorless camera.
 *
 * These are software patterns over the GPU luma grid, not Camera2 AE rectangles:
 * Camera2 exposes one weighted region, which cannot express highlight priority
 * or five distinct patterns. The meter produces a target error; the camera
 * controller decides how to apply it (AE compensation in P, ISO in S).
 */
enum class MeteringMode(val wireValue: String, val label: String) {
    MULTI("multi", "Multi"),
    CENTER("center", "Center"),
    SPOT("spot", "Spot"),
    AVERAGE("average", "Average"),
    HIGHLIGHT("highlight", "Highlight"),
    ;

    companion object {
        val wireValues: List<String> = entries.map { it.wireValue }

        fun fromWire(value: String): MeteringMode =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: MULTI
    }
}

/** What the meter measured and what the active pattern wants it to be. */
data class MeterSample(
    val measuredLuma: Float,
    val targetLuma: Float,
) {
    /** Positive means the image is too dark and needs more exposure. */
    val errorEv: Float
        get() {
            val measured = measuredLuma.coerceAtLeast(MIN_LUMA)
            return log2(targetLuma / measured)
        }

    private companion object {
        const val MIN_LUMA = 1e-4f
    }
}

/**
 * Pure linear-luma metering over a row-major grid.
 *
 * The grid is the upright analysis frame from the GPU. Values are normalised
 * 0..1. Callers are expected to pass the dimensions that match the array.
 */
object ExposureMeter {
    /** Mid-grey target used by every pattern except highlight priority. */
    const val NEUTRAL_TARGET = 0.45f

    /** Highlight metering only pulls exposure down to keep this ceiling. */
    const val HIGHLIGHT_TARGET = 0.85f

    private const val HIGHLIGHT_PERCENTILE = 0.90f
    private const val MULTI_TRIM = 0.05f
    private const val MULTI_SIGMA = 1.10f
    private const val CENTER_SIGMA = 0.70f
    private const val SPOT_SIGMA = 0.18f

    fun measure(
        mode: MeteringMode,
        grid: FloatArray,
        width: Int,
        height: Int,
    ): MeterSample? {
        if (width <= 0 || height <= 0 || grid.size < width * height) return null
        val size = width * height
        val values = FloatArray(size) { grid[it].coerceIn(0f, 1f) }

        var target = NEUTRAL_TARGET
        val measured = when (mode) {
            MeteringMode.AVERAGE -> average(values)
            MeteringMode.CENTER -> gaussian(values, width, height, CENTER_SIGMA)
            MeteringMode.SPOT -> gaussian(values, width, height, SPOT_SIGMA)
            MeteringMode.MULTI -> multi(values, width, height)
            MeteringMode.HIGHLIGHT -> {
                // Protect-only: target the lesser of the measured highlight and
                // the ceiling. A flat scene yields error 0 instead of being
                // pushed up to 0.85.
                val highlight = percentile(values, HIGHLIGHT_PERCENTILE)
                target = minOf(highlight, HIGHLIGHT_TARGET)
                highlight
            }
        }
        return MeterSample(measured.coerceIn(1e-4f, 1f), target)
    }

    private fun average(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        var sum = 0f
        for (value in values) sum += value
        return sum / values.size
    }

    /**
     * Matrix-style metering: reject the extreme 5% at each end, then apply a
     * mild centre bias so a bright sky or a dark foreground cannot dominate.
     */
    private fun multi(values: FloatArray, width: Int, height: Int): Float {
        val sorted = values.sortedArray()
        val last = sorted.size - 1
        val low = sorted[(last * MULTI_TRIM).toInt().coerceIn(0, last)]
        val high = sorted[(last * (1f - MULTI_TRIM)).toInt().coerceIn(0, last)]
        val clipped = FloatArray(values.size) { values[it].coerceIn(low, high) }
        return gaussian(clipped, width, height, MULTI_SIGMA)
    }

    /** Centre-weighted average with a Gaussian falloff over the frame. */
    private fun gaussian(
        values: FloatArray,
        width: Int,
        height: Int,
        sigma: Float,
    ): Float {
        if (width <= 0 || height <= 0) return 0f
        val denominator = 2f * sigma * sigma
        var weighted = 0f
        var weights = 0f
        for (y in 0 until height) {
            val ny = ((y + 0.5f) / height) * 2f - 1f
            for (x in 0 until width) {
                val nx = ((x + 0.5f) / width) * 2f - 1f
                val weight = exp((-(nx * nx + ny * ny) / denominator).toDouble()).toFloat()
                weighted += values[y * width + x] * weight
                weights += weight
            }
        }
        return if (weights > 0f) weighted / weights else 0f
    }

    private fun percentile(values: FloatArray, fraction: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sortedArray()
        val index = ((sorted.size - 1) * fraction.coerceIn(0f, 1f)).toInt()
        return sorted[index.coerceIn(0, sorted.size - 1)]
    }
}
