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
 * Conversion from a metered [MeterSample] to an exposure correction.
 *
 * The same loop drives P-mode AE compensation and S-mode ISO. The user-facing
 * speed (0 = slow, 1 = fast) changes both the proportional gain and the
 * per-second EV limit, so a slow setting glides and a fast setting snaps.
 *
 * Pure Kotlin so the response curve is testable without a camera.
 */
class ExposureController(
    private val deadBandEv: Float = 0.06f,
) {
    /**
     * Returns the EV correction for one metering update, or 0 inside the dead
     * band. Callers apply it to ISO (S) or AE compensation (P).
     *
     * @param elapsedSeconds time since the previous metering update.
     * @param speed 0 = slowest, 1 = fastest.
     */
    fun correctionEv(
        sample: MeterSample,
        elapsedSeconds: Float,
        speed: Float,
    ): Float {
        val measured = sample.measuredLuma.coerceAtLeast(MIN_LUMA)
        val target = sample.targetLuma.coerceIn(MIN_LUMA, 1f)
        val errorEv = log2(target / measured)
        if (abs(errorEv) < deadBandEv) return 0f

        val normalized = speed.coerceIn(0f, 1f)
        val gain = GAIN_SLOW + (GAIN_FAST - GAIN_SLOW) * normalized
        val maxEvPerSecond = EV_PER_SECOND_SLOW +
            (EV_PER_SECOND_FAST - EV_PER_SECOND_SLOW) * normalized
        val maxStep = maxEvPerSecond *
            elapsedSeconds.coerceIn(MIN_STEP_SECONDS, MAX_STEP_SECONDS)
        return (errorEv * gain).coerceIn(-maxStep, maxStep)
    }

    /** ISO for the next metering update in shutter-priority mode. */
    fun nextIso(
        sample: MeterSample,
        currentIso: Int,
        isoMin: Int,
        isoMax: Int,
        elapsedSeconds: Float,
        speed: Float,
    ): Int {
        if (isoMin <= 0 || isoMax <= isoMin) return currentIso
        val correctionEv = correctionEv(sample, elapsedSeconds, speed)
        if (correctionEv == 0f) return currentIso
        val next = currentIso * 2f.pow(correctionEv)
        return next.roundToInt().coerceIn(isoMin, isoMax)
    }

    private companion object {
        const val MIN_STEP_SECONDS = 0.05f
        const val MAX_STEP_SECONDS = 0.5f
        const val MIN_LUMA = 1e-4f
        const val GAIN_SLOW = 0.2f
        const val GAIN_FAST = 1.0f
        const val EV_PER_SECOND_SLOW = 0.4f
        const val EV_PER_SECOND_FAST = 4.0f
    }
}
