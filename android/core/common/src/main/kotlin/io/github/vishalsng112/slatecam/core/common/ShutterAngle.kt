package io.github.vishalsng112.slatecam.core.common

/**
 * Shutter angle ↔ exposure time.
 *
 * `exposure = (angle / 360) / fps`. 180° at 30 fps is 1/60 s, the classic
 * "180 degree shutter".
 */
object ShutterAngle {

    fun exposureNs(angleDegrees: Float, fps: Int): Long {
        require(fps > 0) { "fps must be positive" }
        val clamped = angleDegrees.coerceIn(0f, 360f)
        return ((clamped / 360.0) / fps * 1_000_000_000.0).toLong()
    }

    fun degrees(exposureNs: Long, fps: Int): Float {
        require(fps > 0) { "fps must be positive" }
        return ((exposureNs / 1_000_000_000.0) * fps * 360.0).toFloat()
    }
}
