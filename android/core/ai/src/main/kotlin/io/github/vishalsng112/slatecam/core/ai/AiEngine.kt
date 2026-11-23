package io.github.vishalsng112.slatecam.core.ai

/**
 * Phase 6 AI boundary.
 *
 * This module is contracts only. The LiteRT implementation, model assets,
 * thermal policy and UI arrive later. Every consumer must assume the engine is
 * unavailable until [AiCapabilities.available] is true.
 *
 * Rules that the implementation must keep:
 * - AI is opt-in. Nothing runs while [AiConfig.enabled] is false.
 * - Inference is frame-capped. [AiConfig.maxFramesPerSecond] is a hard ceiling.
 * - Frames stay native. An [AiFrame] carries a texture handle, never pixels
 *   over the Flutter bridge.
 * - AI results are advisory. The camera remains the authority; a focus
 *   suggestion is only applied if the focus controller accepts it.
 */
enum class AiFeature {
    SUBJECT_TRACKING,
    FOCUS_ASSIST,
}

/** Runtime capabilities, filled by the future implementation after engine load. */
data class AiCapabilities(
    val available: Boolean = false,
    val features: Set<AiFeature> = emptySet(),
    val backend: String = "none",
)

/**
 * Opt-in configuration for one AI session.
 *
 * The default is disabled. A caller must explicitly opt in and name the
 * features it wants; the engine must not infer permissions from the build.
 */
data class AiConfig(
    val enabled: Boolean = false,
    val features: Set<AiFeature> = setOf(AiFeature.SUBJECT_TRACKING, AiFeature.FOCUS_ASSIST),
    val maxFramesPerSecond: Int = DEFAULT_MAX_FPS,
) {
    init {
        require(maxFramesPerSecond > 0) { "maxFramesPerSecond must be positive" }
    }

    companion object {
        /** Conservative default until thermal profiling exists. */
        const val DEFAULT_MAX_FPS: Int = 10
    }
}

/** Texture binding used by a native frame handed to the engine. */
enum class AiTextureTarget {
    EXTERNAL_OES,
    TEXTURE_2D,
}

/**
 * Native frame descriptor.
 *
 * The texture remains owned by the GPU module; the AI engine must borrow it for
 * the duration of one inference and must never block the GL thread.
 */
data class AiFrame(
    val textureId: Int,
    val textureTarget: AiTextureTarget = AiTextureTarget.EXTERNAL_OES,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val rotationDegrees: Int = 0,
) {
    init {
        require(width > 0 && height > 0) { "frame dimensions must be positive" }
        require(rotationDegrees in setOf(0, 90, 180, 270)) {
            "rotationDegrees must be 0, 90, 180 or 270"
        }
    }
}

/** Normalised image coordinates in the range 0..1. */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && right in 0f..1f && top in 0f..1f && bottom in 0f..1f) {
            "normalised rect coordinates must be in 0..1"
        }
        require(left < right && top < bottom) { "rect must have positive area" }
    }
}

data class SubjectTrack(
    val id: Int,
    val label: String,
    val confidence: Float,
    val bounds: NormalizedRect,
) {
    init {
        require(confidence in 0f..1f) { "confidence must be in 0..1" }
    }
}

/**
 * A focus recommendation in dioptres. The camera may ignore it, clamp it or
 * feed it through the normal focus ramp; AI never writes the lens directly.
 */
data class FocusSuggestion(
    val diopters: Float,
    val confidence: Float,
    val subjectId: Int? = null,
) {
    init {
        require(diopters >= 0f) { "dioptres must not be negative" }
        require(confidence in 0f..1f) { "confidence must be in 0..1" }
    }
}

/** One AI output for one submitted frame. */
data class AiFrameResult(
    val timestampNs: Long,
    val subjects: List<SubjectTrack> = emptyList(),
    val focusSuggestion: FocusSuggestion? = null,
    val processingLatencyMs: Long? = null,
)

fun interface AiListener {
    fun onResult(result: AiFrameResult)
}

/**
 * Engine contract implemented in Phase 6.
 *
 * [configure] must stop inference when disabled and release thermal headroom.
 * [submit] may drop frames; callers should use [AiFrameGate] as well so the
 * engine is not handed work it is guaranteed to discard.
 */
interface AiEngine : AutoCloseable {
    val capabilities: AiCapabilities

    fun configure(config: AiConfig)

    fun submit(frame: AiFrame)

    fun setListener(listener: AiListener?)

    /** Clears tracking state without destroying engine resources. */
    fun reset()
}
