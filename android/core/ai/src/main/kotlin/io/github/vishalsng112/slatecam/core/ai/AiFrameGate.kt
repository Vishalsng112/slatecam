package io.github.vishalsng112.slatecam.core.ai

/**
 * Rate limiter for AI submissions.
 *
 * The camera/GPU pipeline can deliver 30-120 fps. The AI engine is deliberately
 * capped so tracking does not become the dominant thermal load. A frame is
 * accepted at most once per `1 / maxFramesPerSecond` interval; duplicate,
 * backwards and too-early timestamps are dropped.
 */
class AiFrameGate(maxFramesPerSecond: Int) {

    private val intervalNs: Long
    private var lastAcceptedNs: Long = NO_FRAME

    init {
        require(maxFramesPerSecond > 0) { "maxFramesPerSecond must be positive" }
        intervalNs = 1_000_000_000L / maxFramesPerSecond
    }

    /** Returns true when this timestamp should be submitted to the engine. */
    fun accept(timestampNs: Long): Boolean {
        if (lastAcceptedNs != NO_FRAME) {
            if (timestampNs <= lastAcceptedNs) return false
            if (timestampNs - lastAcceptedNs < intervalNs) return false
        }
        lastAcceptedNs = timestampNs
        return true
    }

    fun reset() {
        lastAcceptedNs = NO_FRAME
    }

    private companion object {
        const val NO_FRAME = Long.MIN_VALUE
    }
}
