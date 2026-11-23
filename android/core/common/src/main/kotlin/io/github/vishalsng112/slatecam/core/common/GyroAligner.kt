package io.github.vishalsng112.slatecam.core.common

data class GyroSample(
    val timestampNs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
)

/**
 * Integrates angular velocity into a per-frame rotation.
 *
 * For each pair of consecutive encoded frame timestamps, samples inside the
 * interval are integrated (`rate × dt`) and the tail is closed at the frame
 * boundary. Output is radians per frame interval, which is what a stabiliser
 * or motion-blur tool needs.
 */
object GyroAligner {

    data class FrameRotation(
        val timestampNs: Long,
        val rx: Float,
        val ry: Float,
        val rz: Float,
    ) {
        fun magnitude(): Float = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz)
    }

    fun align(framesNs: LongArray, samples: List<GyroSample>): List<FrameRotation> {
        if (framesNs.size < 2 || samples.isEmpty()) return emptyList()

        val rotations = ArrayList<FrameRotation>(framesNs.size - 1)
        var cursor = 0

        for (frameIndex in 0 until framesNs.size - 1) {
            val start = framesNs[frameIndex]
            val end = framesNs[frameIndex + 1]
            if (end <= start) continue

            while (cursor < samples.size && samples[cursor].timestampNs < start) cursor++

            var rx = 0f
            var ry = 0f
            var rz = 0f
            var previous = start
            var index = cursor

            while (index < samples.size && samples[index].timestampNs < end) {
                val sample = samples[index]
                val dt = ((sample.timestampNs - previous).coerceAtLeast(0L)) / 1_000_000_000f
                rx += sample.x * dt
                ry += sample.y * dt
                rz += sample.z * dt
                previous = sample.timestampNs
                index++
            }

            if (index > cursor) {
                val tail = ((end - previous).coerceAtLeast(0L)) / 1_000_000_000f
                val last = samples[index - 1]
                rx += last.x * tail
                ry += last.y * tail
                rz += last.z * tail
            }

            rotations += FrameRotation(start, rx, ry, rz)
        }

        return rotations
    }
}
