package io.github.vishalsng112.slatecam.core.camera

/**
 * Shooting-oriented defaults for the software focus engine.
 *
 * These are normalized (0..1) so the UI can show the same sliders for every
 * scenario. Selecting a scenario applies both values; the operator can then
 * fine-tune either one.
 */
enum class FocusScenario(
    val wireValue: String,
    val label: String,
    val shiftSensitivity: Float,
    val transitionSpeed: Float,
) {
    /** Seated subject: sticky, slow, no hunting. */
    INTERVIEW("interview", "Interview", 0.25f, 0.35f),

    /** Moving presenter: quick to follow, quick to rack. */
    VLOGGING("vlogging", "Vlogging", 0.80f, 0.75f),

    /** Balanced default for unpredictable events. */
    WEDDING("wedding", "Wedding", 0.50f, 0.50f),

    /** Sticky subject and a deliberately slow, smooth rack. */
    CINEMATIC("cinematic", "Cinematic", 0.20f, 0.15f),
    ;

    companion object {
        val wireValues: List<String> = entries.map { it.wireValue }

        fun fromWire(value: String): FocusScenario =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: WEDDING
    }
}

/** Normalized live settings for the software focus engine. */
data class SoftwareFocusSettings(
    val shiftSensitivity: Float,
    val transitionSpeed: Float,
) {
    val sensitivity: Float get() = shiftSensitivity.coerceIn(0f, 1f)
    val speed: Float get() = transitionSpeed.coerceIn(0f, 1f)
}

/**
 * Contrast-detect autofocus over the lens focus-distance range.
 *
 * The camera is in AF OFF and the controller ramps [android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE]
 * in diopters. The engine consumes a sharpness score measured on the GPU luma
 * frame and performs a bounded hill-climb:
 *
 * - while locked it watches for a scene change; shift sensitivity sets both the
 *   score drop that counts and how long it must persist;
 * - while searching it steps toward the sharpness peak, reverses when the score
 *   falls, halves the step and finally locks at the best position;
 * - transition speed affects the step size, while the controller turns it into
 *   diopters per second for the actual lens ramp.
 *
 * Pure Kotlin and time-driven so the search can be tested with a synthetic
 * focus curve.
 */
class SoftwareFocusEngine {
    data class Update(
        val targetDiopters: Float,
        val searching: Boolean,
        val locked: Boolean,
    )

    private var locked = true
    private var searching = false
    private var targetDiopters = 0f
    private var lockedDiopters = 0f
    private var lockedScore = 0f
    private var bestScore = 0f
    private var bestDiopters = 0f
    private var lastScore = 0f
    private var direction = 1
    private var stepFraction = DEFAULT_STEP_FRACTION
    private var belowSinceMs = -1L
    private var smoothedScore = 0f
    private var hasSmoothedScore = false
    private var lockedAtMs = -1L
    private var lastUpdateMs = 0L

    /** Start a fresh acquisition at the current lens position. */
    fun reset(diopters: Float) {
        locked = true
        searching = false
        targetDiopters = diopters.coerceAtLeast(0f)
        lockedDiopters = targetDiopters
        lockedScore = 0f
        bestScore = 0f
        bestDiopters = targetDiopters
        lastScore = 0f
        direction = 1
        stepFraction = DEFAULT_STEP_FRACTION
        belowSinceMs = -1L
        smoothedScore = 0f
        hasSmoothedScore = false
        lockedAtMs = -1L
        lastUpdateMs = 0L
    }

    fun update(
        score: Float,
        currentDiopters: Float,
        settled: Boolean,
        nowMs: Long,
        maxDiopters: Float,
        settings: SoftwareFocusSettings,
    ): Update {
        if (maxDiopters <= 0f) {
            return Update(targetDiopters, searching = false, locked = true)
        }
        if (!settled) {
            return Update(targetDiopters, searching, locked)
        }
        lastUpdateMs = nowMs
        val rawScore = score.coerceAtLeast(0f)
        // EMA removes frame-to-frame sharpness noise; the raw score still gates
        // the contrast floor so a flat scene locks immediately.
        smoothedScore = if (hasSmoothedScore) {
            smoothedScore * 0.5f + rawScore * 0.5f
        } else {
            rawScore
        }
        hasSmoothedScore = true
        val measured = smoothedScore

        if (rawScore < CONTRAST_FLOOR) {
            // A flat/no-texture scene cannot be focused by contrast. Hold or
            // lock at the best position instead of hunting through noise.
            return if (locked) {
                Update(targetDiopters, searching = false, locked = true)
            } else {
                lockBest()
            }
        }

        if (locked) {
            if (lockedScore <= 0f) {
                beginSearch(measured, currentDiopters, maxDiopters, settings)
                return Update(targetDiopters, searching = true, locked = false)
            }

            // Do not immediately re-search after a lock; let the lens and the
            // score settle. This is what stops visible hunting at the peak.
            if (lockedAtMs >= 0L && nowMs - lockedAtMs < LOCK_COOLDOWN_MS) {
                return Update(targetDiopters, searching = false, locked = true)
            }

            val triggerDrop = TRIGGER_MAX - (TRIGGER_MAX - TRIGGER_MIN) *
                settings.sensitivity
            val below = measured < lockedScore * (1f - triggerDrop)
            if (!below) {
                belowSinceMs = -1L
                // Only accept a meaningfully higher score as the new lock;
                // noise must not raise the bar and cause a later false trigger.
                if (measured > lockedScore * 1.01f) {
                    lockedScore = measured
                    lockedDiopters = currentDiopters
                    targetDiopters = currentDiopters
                }
                return Update(targetDiopters, searching = false, locked = true)
            }

            if (belowSinceMs < 0L) belowSinceMs = nowMs
            val holdMs = HOLD_MAX_MS - (HOLD_MAX_MS - HOLD_MIN_MS) * settings.sensitivity
            if (nowMs - belowSinceMs < holdMs) {
                return Update(targetDiopters, searching = false, locked = true)
            }
            beginSearch(measured, currentDiopters, maxDiopters, settings)
            return Update(targetDiopters, searching = true, locked = false)
        }

        // Searching. The lens has settled at the previous target, so this score
        // describes that position.
        if (measured > bestScore) {
            bestScore = measured
            bestDiopters = currentDiopters
        }

        val improved = measured >= lastScore * SCORE_NOISE
        if (improved) {
            lastScore = measured
            val next = (currentDiopters + direction * stepFraction * maxDiopters)
                .coerceIn(0f, maxDiopters)
            if (next == currentDiopters || stepFraction < MIN_STEP_FRACTION) {
                return lockBest()
            }
            targetDiopters = next
            return Update(targetDiopters, searching = true, locked = false)
        }

        // Score fell: reverse around the best position and search finer.
        direction = -direction
        stepFraction /= 2f
        lastScore = bestScore
        if (stepFraction < MIN_STEP_FRACTION) return lockBest()
        val next = (bestDiopters + direction * stepFraction * maxDiopters)
            .coerceIn(0f, maxDiopters)
        if (next == bestDiopters) return lockBest()
        targetDiopters = next
        return Update(targetDiopters, searching = true, locked = false)
    }

    private fun beginSearch(
        score: Float,
        currentDiopters: Float,
        maxDiopters: Float,
        settings: SoftwareFocusSettings,
    ) {
        locked = false
        searching = true
        bestScore = score
        bestDiopters = currentDiopters
        lastScore = score
        belowSinceMs = -1L
        stepFraction = BASE_STEP_FRACTION + STEP_SPEED_RANGE * settings.speed
        // Start toward the middle of the range; the first comparison decides
        // whether the peak is nearer or farther.
        direction = if (currentDiopters < maxDiopters / 2f) 1 else -1
        targetDiopters = (currentDiopters + direction * stepFraction * maxDiopters)
            .coerceIn(0f, maxDiopters)
    }

    private fun lockBest(): Update {
        locked = true
        searching = false
        lockedAtMs = lastUpdateMs
        targetDiopters = bestDiopters.coerceAtLeast(0f)
        lockedDiopters = targetDiopters
        lockedScore = bestScore
        belowSinceMs = -1L
        return Update(targetDiopters, searching = false, locked = true)
    }

    private companion object {
        const val CONTRAST_FLOOR = 0.0025f
        const val TRIGGER_MIN = 0.08f
        const val TRIGGER_MAX = 0.40f
        const val HOLD_MIN_MS = 700L
        const val HOLD_MAX_MS = 3_000L
        const val LOCK_COOLDOWN_MS = 800L
        const val BASE_STEP_FRACTION = 0.04f
        const val STEP_SPEED_RANGE = 0.06f
        const val MIN_STEP_FRACTION = 0.008f
        const val DEFAULT_STEP_FRACTION = 0.06f
        const val SCORE_NOISE = 1.005f
    }
}
