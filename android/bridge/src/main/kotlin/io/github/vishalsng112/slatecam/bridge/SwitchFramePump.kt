package io.github.vishalsng112.slatecam.bridge

/**
 * Decides whether a lens switch needs a repeated frame.
 *
 * The camera session is recreated between lenses; until the new session
 * produces frames, the encoder would otherwise see a timestamp gap. The pump
 * fills that gap with the last frame at the active frame rate and stops as soon
 * as the new session has been ready for [readyGraceMs].
 */
internal class SwitchFramePump(
    val frameIntervalMs: Long,
    private val readyGraceMs: Long = 2 * frameIntervalMs,
    private val timeoutMs: Long = 2500L,
) {
    enum class Decision {
        /** No new camera frame this tick: the caller should repeat the last one. */
        REPEAT,

        /** A real frame arrived or the switch is still settling: keep ticking. */
        HOLD,

        /** The switch is finished, failed or timed out: stop the pump. */
        DONE,
    }

    private var active = false
    private var startedAtMs = 0L
    private var readyAtMs: Long? = null
    private var lastFrameCount = 0L

    val isActive: Boolean get() = active

    fun start(nowMs: Long, frameCount: Long) {
        active = true
        startedAtMs = nowMs
        readyAtMs = null
        lastFrameCount = frameCount
    }

    fun cancel() {
        active = false
        readyAtMs = null
    }

    fun tick(nowMs: Long, targetReady: Boolean, frameCount: Long): Decision {
        if (!active) return Decision.DONE
        if (nowMs - startedAtMs >= timeoutMs) {
            active = false
            return Decision.DONE
        }
        if (targetReady) {
            if (readyAtMs == null) readyAtMs = nowMs
            if (nowMs - readyAtMs!! >= readyGraceMs) {
                active = false
                return Decision.DONE
            }
        }
        if (frameCount != lastFrameCount) {
            lastFrameCount = frameCount
            return Decision.HOLD
        }
        return Decision.REPEAT
    }
}
