package io.github.vishalsng112.slatecam.core.camera

import kotlin.math.abs
import kotlin.math.exp

/**
 * Frame-driven zoom ramp shared by the slider and pinch gesture.
 *
 * The value chases the target exponentially in log space, so the perceived
 * speed is uniform across the zoom range instead of exploding at the long end.
 * A log-space velocity cap keeps a large jump (for example 1x -> 10x) from
 * arriving as an instant crop while still allowing a short, snappy move.
 *
 * The controller advances this once per camera frame and posts the value to the
 * repeating capture request; the camera stream, encoder and preview therefore
 * all see the same intermediate zoom steps.
 */
class SmoothZoom(
    private val smoothingSeconds: Float = DEFAULT_SMOOTHING_SECONDS,
    private val maxLogRatePerSecond: Float = DEFAULT_MAX_LOG_RATE,
) {

    /** Ratio currently applied to the camera. */
    var value: Float = 1f
        private set

    /** Ratio the user last asked for. */
    var target: Float = 1f
        private set

    private var lowerBound = 1f
    private var upperBound = 1f

    val isAnimating: Boolean
        get() = abs(target - value) > SETTLE_EPSILON

    fun setBounds(lower: Float, upper: Float) {
        lowerBound = lower.coerceAtLeast(MIN_RATIO)
        upperBound = upper.coerceAtLeast(lowerBound)
        value = value.coerceIn(lowerBound, upperBound)
        target = target.coerceIn(lowerBound, upperBound)
    }

    /** Resets to [ratio] without animating (camera open / switch). */
    fun snapTo(ratio: Float) {
        value = ratio.coerceIn(lowerBound, upperBound)
        target = value
    }

    /**
     * Sets a new target. Returns false when the target is already reached, so
     * the caller does not keep the ramp running for a no-op update.
     */
    fun retarget(ratio: Float): Boolean {
        target = ratio.coerceIn(lowerBound, upperBound)
        if (!isAnimating) {
            value = target
            return false
        }
        return true
    }

    /** Stops at the current value so a reopen does not resume an old target. */
    fun stop() {
        target = value
    }

    /**
     * Advances the value by [dtSeconds]. Returns true while another frame is
     * needed, false once [value] has settled exactly on [target].
     */
    fun advance(dtSeconds: Float): Boolean {
        if (!isAnimating) {
            value = target
            return false
        }
        if (dtSeconds <= 0f) return true
        val dt = dtSeconds.coerceAtMost(MAX_STEP_SECONDS)
        val diff = target - value
        val desired = if (smoothingSeconds <= 0f) {
            diff
        } else {
            diff * (1f - exp(-dt / smoothingSeconds))
        }
        val maxStep = value * (exp(maxLogRatePerSecond * dt) - 1f)
        value += desired.coerceIn(-maxStep, maxStep)
        if (!isAnimating) {
            value = target
            return false
        }
        return true
    }

    private companion object {
        /** ~90 ms response: 90% of a small adjustment in ~200 ms. */
        const val DEFAULT_SMOOTHING_SECONDS = 0.09f

        /** 6 natural-log units per second; 1x -> 10x takes ~0.38 s. */
        const val DEFAULT_MAX_LOG_RATE = 6f

        const val SETTLE_EPSILON = 0.001f
        const val MAX_STEP_SECONDS = 0.064f
        const val MIN_RATIO = 0.01f
    }
}
