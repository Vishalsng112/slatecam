package io.github.vishalsng112.slatecam.core.camera

import android.graphics.Rect
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import kotlin.math.abs

/**
 * The single interface for focus state and mode switching.
 *
 * Every focus change goes through [selectMode] or one of the operator-oriented
 * entry points that delegates to it ([selectManual], [selectScenario]). The
 * controller owns the mode catalog, the Camera2 continuous-video default, the
 * manual diopter ramp and the software (SAF) engine, so the camera controller
 * only drives frames and writes the values into the capture request.
 */
class FocusController(private val onRampNeeded: () -> Unit = {}) {

    enum class PointFocus {
        /** The software engine restarted its search. */
        SOFTWARE,

        /** A HAL AF region should be applied by the caller. */
        HARDWARE,

        /** Manual focus has no depth information for a tapped point. */
        IGNORE,
    }

    var availableModes: List<String> = emptyList()
        private set

    /** Active mode for the current lens. */
    var mode: String = FocusMode.CONTINUOUS_VIDEO
        private set

    /** Last mode the operator chose; restored on the next capable lens. */
    var preferredMode: String = FocusMode.CONTINUOUS_VIDEO
        private set

    var autoFocusSupported: Boolean = true
        private set

    val autoFocus: Boolean get() = FocusMode.isAutomatic(mode)

    val isContinuous: Boolean get() = FocusMode.isContinuous(mode)

    var scenario: FocusScenario = FocusScenario.WEDDING
        private set

    var shiftSensitivity: Float = 0.5f
        private set

    var transitionSpeed: Float = 0.5f
        private set

    var maxDiopters: Float = 0f
        private set

    var diopters: Float = 0f
        private set

    var targetDiopters: Float = 0f
        private set

    /** Current diopters-per-second ramp speed. */
    var shiftSpeed: Float = 2f
        private set

    var isRamping: Boolean = false
        private set

    var reportedDiopters: Float = 0f
        private set

    var isSearching: Boolean = false
        private set

    var settleUntilMs: Long = 0L
        private set

    private val softwareFocus = SoftwareFocusEngine()
    private var softwareInitialized = false

    /**
     * Refreshes the lens capabilities and resolves the active mode. The
     * operator's last choice wins when this lens supports it; otherwise the
     * Camera2 default chain selects the mode.
     */
    fun bind(halModes: List<String>, minimumFocusDistance: Float) {
        maxDiopters = minimumFocusDistance.coerceAtLeast(0f)
        availableModes = FocusMode.options(halModes, hasSoftware = maxDiopters > 0f)
        autoFocusSupported = halModes.any { it != FocusMode.OFF }
        mode = if (preferredMode in availableModes) {
            preferredMode
        } else {
            FocusMode.defaultFor(availableModes)
        }
        if (mode == FocusMode.SOFTWARE) {
            shiftSpeed = transitionToSpeed(transitionSpeed)
            softwareInitialized = false
        }
    }

    /** Restores a persisted operator choice before the first [bind]. */
    fun restorePreferredMode(value: String) {
        if (FocusMode.isKnown(value)) preferredMode = value
    }

    fun resetLensPosition() {
        diopters = 0f
        targetDiopters = 0f
        reportedDiopters = 0f
        isRamping = false
        isSearching = false
        settleUntilMs = 0L
        softwareInitialized = false
    }

    /**
     * The single mode switch. Returns false when the lens does not offer the
     * mode, so callers never silently land in a mode the HAL cannot run.
     */
    fun selectMode(value: String, nowMs: Long): Boolean {
        if (value !in availableModes) return false
        preferredMode = value
        mode = value
        isSearching = false
        when (value) {
            FocusMode.OFF -> {
                targetDiopters = diopters
                isRamping = false
            }
            FocusMode.SOFTWARE -> {
                shiftSpeed = transitionToSpeed(transitionSpeed)
                softwareInitialized = false
                softwareFocus.reset(diopters)
                targetDiopters = diopters
                settleUntilMs = nowMs + SOFTWARE_SETTLE_MS
                isRamping = false
            }
            else -> Unit
        }
        return true
    }

    /**
     * Manual focus is the `off` mode plus a lens distance. Moving the slider
     * therefore selects the mode through the same switch.
     */
    fun selectManual(value: Float, nowMs: Long): Boolean {
        if (FocusMode.OFF !in availableModes) return false
        selectMode(FocusMode.OFF, nowMs)
        targetDiopters = value.coerceIn(0f, maxDiopters)
        isRamping = true
        onRampNeeded()
        return true
    }

    /** Applies a preset and runs it through the software engine. */
    fun selectScenario(value: String, nowMs: Long): Boolean {
        val selected = FocusScenario.fromWire(value)
        scenario = selected
        shiftSensitivity = selected.shiftSensitivity
        transitionSpeed = selected.transitionSpeed
        shiftSpeed = transitionToSpeed(transitionSpeed)
        if (FocusMode.SOFTWARE !in availableModes) return false
        return selectMode(FocusMode.SOFTWARE, nowMs)
    }

    fun setShiftSensitivity(value: Float) {
        shiftSensitivity = value.coerceIn(0f, 1f)
    }

    fun setTransitionSpeed(value: Float) {
        transitionSpeed = value.coerceIn(0f, 1f)
        shiftSpeed = transitionToSpeed(transitionSpeed)
    }

    fun restoreScenario(value: String, sensitivity: Float, speed: Float) {
        scenario = FocusScenario.fromWire(value)
        shiftSensitivity = sensitivity.coerceIn(0f, 1f)
        transitionSpeed = speed.coerceIn(0f, 1f)
        shiftSpeed = transitionToSpeed(transitionSpeed)
    }

    /** Consumes the HAL's lens report for the active frame. */
    fun onCaptureResult(distance: Float, nowMs: Long) {
        reportedDiopters = distance
        when {
            // Only the first result seeds the engine. After that the ramp owns
            // diopters; a lagging HAL report must not stall the request.
            mode == FocusMode.SOFTWARE && !softwareInitialized -> {
                softwareInitialized = true
                diopters = distance
                targetDiopters = distance
                softwareFocus.reset(distance)
                settleUntilMs = nowMs + SOFTWARE_SETTLE_MS
            }
            autoFocus -> {
                diopters = distance
                targetDiopters = distance
            }
        }
    }

    /** Feeds one sharpness sample into the software engine. */
    fun onSharpness(score: Float, nowMs: Long): Boolean {
        if (mode != FocusMode.SOFTWARE) return false
        if (nowMs < settleUntilMs) return false
        if (abs(targetDiopters - diopters) > SOFTWARE_SETTLED_DIOPTERS) return false
        // Wait for the lens report to catch up, but do not stall forever if
        // the HAL does not publish a useful distance.
        val reportedClose = abs(targetDiopters - reportedDiopters) <= SOFTWARE_REPORTED_DIOPTERS
        if (!reportedClose && nowMs < settleUntilMs + SOFTWARE_REPORT_TIMEOUT_MS) return false
        val update = softwareFocus.update(
            score = score,
            currentDiopters = diopters,
            settled = true,
            nowMs = nowMs,
            maxDiopters = maxDiopters,
            settings = SoftwareFocusSettings(shiftSensitivity, transitionSpeed),
        )
        isSearching = update.searching
        if (update.targetDiopters == targetDiopters) return false
        targetDiopters = update.targetDiopters
        shiftSpeed = transitionToSpeed(transitionSpeed)
        isRamping = true
        onRampNeeded()
        return true
    }

    /** Advances the manual/SAF ramp. Returns true while it is still moving. */
    fun advanceRamp(seconds: Float, nowMs: Long): Boolean {
        if (!isRamping) return false
        val delta = targetDiopters - diopters
        val maxStep = shiftSpeed * seconds
        if (abs(delta) <= maxStep) {
            diopters = targetDiopters
            isRamping = false
            // Give the lens a moment to settle before the next score is valid.
            settleUntilMs = nowMs + RAMP_SETTLE_MS
            return false
        }
        diopters += if (delta > 0f) maxStep else -maxStep
        return true
    }

    /** Handles a viewfinder tap. HAL region work stays with the caller. */
    fun onPointFocus(nowMs: Long): PointFocus = when (mode) {
        FocusMode.SOFTWARE -> {
            softwareInitialized = true
            softwareFocus.reset(diopters)
            targetDiopters = diopters
            isSearching = false
            settleUntilMs = nowMs + SOFTWARE_SETTLE_MS
            PointFocus.SOFTWARE
        }
        FocusMode.OFF -> PointFocus.IGNORE
        else -> PointFocus.HARDWARE
    }

    /** Writes the AF / lens fields owned by this controller. */
    fun applyTo(
        request: CaptureRequest.Builder,
        afRegion: Rect?,
        maxAfRegions: Int,
    ) {
        val hal = FocusMode.toHal(mode)
        if (hal != null && mode != FocusMode.OFF) {
            request.set(CaptureRequest.CONTROL_AF_MODE, hal)
            if (afRegion != null && maxAfRegions > 0) {
                request.set(
                    CaptureRequest.CONTROL_AF_REGIONS,
                    arrayOf(MeteringRectangle(afRegion, AF_REGION_WEIGHT)),
                )
            }
            request.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                android.hardware.camera2.CameraMetadata.CONTROL_AF_TRIGGER_IDLE,
            )
        } else {
            // Manual focus and the software engine both own the lens distance.
            request.set(
                CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CameraCharacteristics.CONTROL_AF_MODE_OFF,
            )
            request.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
        }
    }

    private fun transitionToSpeed(value: Float): Float =
        0.5f + 11.5f * value.coerceIn(0f, 1f)

    private companion object {
        const val AF_REGION_WEIGHT = 1000
        const val SOFTWARE_SETTLE_MS = 150L
        const val RAMP_SETTLE_MS = 120L
        const val SOFTWARE_REPORT_TIMEOUT_MS = 350L
        const val SOFTWARE_SETTLED_DIOPTERS = 0.02f
        const val SOFTWARE_REPORTED_DIOPTERS = 0.08f
    }
}
