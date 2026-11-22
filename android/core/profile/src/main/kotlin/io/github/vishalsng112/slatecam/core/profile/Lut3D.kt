package io.github.vishalsng112.slatecam.core.profile

/**
 * CPU-side 3D LUT with a `.cube` parser.
 *
 * The GPU path packs this into a 2D texture; keeping the parser and the
 * trilinear sampling here makes the colour maths testable on the JVM.
 */
class Lut3D(
    val size: Int,
    private val data: FloatArray,
) {

    init {
        require(size > 1) { "LUT size must be > 1" }
        require(data.size == size * size * size * 3) { "LUT data size mismatch" }
    }

    /** Trilinear sample, inputs and outputs in 0..1. */
    fun sample(r: Float, g: Float, b: Float): FloatArray {
        val maxIndex = size - 1
        val rf = r.coerceIn(0f, 1f) * maxIndex
        val gf = g.coerceIn(0f, 1f) * maxIndex
        val bf = b.coerceIn(0f, 1f) * maxIndex

        val r0 = rf.toInt().coerceAtMost(maxIndex - 1)
        val g0 = gf.toInt().coerceAtMost(maxIndex - 1)
        val b0 = bf.toInt().coerceAtMost(maxIndex - 1)
        val dr = rf - r0
        val dg = gf - g0
        val db = bf - b0

        var outR = 0f
        var outG = 0f
        var outB = 0f
        for (i in 0..1) {
            val wr = if (i == 0) 1f - dr else dr
            for (j in 0..1) {
                val wg = if (j == 0) 1f - dg else dg
                for (k in 0..1) {
                    val wb = if (k == 0) 1f - db else db
                    val weight = wr * wg * wb
                    if (weight == 0f) continue
                    val offset = index(r0 + i, g0 + j, b0 + k) * 3
                    outR += data[offset] * weight
                    outG += data[offset + 1] * weight
                    outB += data[offset + 2] * weight
                }
            }
        }
        return floatArrayOf(outR, outG, outB)
    }

    private fun index(r: Int, g: Int, b: Int): Int =
        (b * size * size) + (g * size) + r

    companion object {
        /** Parses the Adobe `.cube` format. Returns null when the text is invalid. */
        fun parse(text: String): Lut3D? {
            var size = -1
            val values = mutableListOf<Float>()

            for (rawLine in text.lineSequence()) {
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val upper = line.uppercase()
                when {
                    upper.startsWith("TITLE") -> Unit
                    upper.startsWith("LUT_3D_SIZE") -> {
                        size = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: return null
                    }
                    upper.startsWith("LUT_1D_SIZE") -> return null
                    upper.startsWith("DOMAIN_MIN") || upper.startsWith("DOMAIN_MAX") -> Unit
                    else -> {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size < 3) return null
                        for (index in 0..2) {
                            values += parts[index].toFloatOrNull() ?: return null
                        }
                    }
                }
            }

            if (size <= 1) return null
            if (values.size != size * size * size * 3) return null
            return Lut3D(size, values.toFloatArray())
        }
    }
}
