package io.github.vishalsng112.slatecam.core.profile

/**
 * Fits a per-camera input profile from colour-target samples.
 *
 * The solver is pure math: callers supply observed RGB (what the camera
 * produced) and reference RGB (what the target is), both in the same encoded
 * domain the shader sees. The result is a 3×3 channel mix plus an exposure
 * trim; saturation is left at 1 so a calibration never changes the creative
 * look beyond channel alignment.
 *
 * There is deliberately no offset term: [InputProfile] is a 3×3 matrix in the
 * renderer, so the fit avoids pretending it can correct black level.
 */
object ColorCalibration {
    /** RGB triple in the encoded (display-referred) domain, each channel 0..1. */
    data class Sample(
        val observed: FloatArray,
        val reference: FloatArray,
    ) {
        init {
            require(observed.size == 3) { "observed must be RGB" }
            require(reference.size == 3) { "reference must be RGB" }
        }
    }

    private const val RIDGE = 1e-4
    private const val MIN_SAMPLES = 8
    private const val MIN_TRIM = 0.25f
    private const val MAX_TRIM = 4f

    /**
     * [samples] are all patches; [neutrals] is the subset used to set the
     * exposure trim (gray/white patches). When [neutrals] is empty the trim is
     * derived from every sample.
     */
    fun solve(
        samples: List<Sample>,
        neutrals: List<Sample> = emptyList(),
    ): InputProfile {
        val valid = samples.filter(::isUsable)
        require(valid.size >= MIN_SAMPLES) {
            "need at least $MIN_SAMPLES usable samples, got ${valid.size}"
        }

        val matrix = FloatArray(9)
        for (channel in 0..2) {
            val ata = Array(3) { DoubleArray(3) }
            val atb = DoubleArray(3)
            valid.forEach { sample ->
                val x = sample.observed
                val y = sample.reference[channel].toDouble()
                for (i in 0..2) {
                    for (j in 0..2) {
                        ata[i][j] += x[i].toDouble() * x[j].toDouble()
                    }
                    atb[i] += x[i].toDouble() * y
                }
            }
            for (i in 0..2) ata[i][i] += RIDGE
            val coefficients = solve3x3(ata, atb) ?: return InputProfile.IDENTITY
            matrix[channel * 3] = coefficients[0].toFloat()
            matrix[channel * 3 + 1] = coefficients[1].toFloat()
            matrix[channel * 3 + 2] = coefficients[2].toFloat()
        }

        if (!isPlausible(matrix)) return InputProfile.IDENTITY

        val trimSource = neutrals.filter(::isUsable).ifEmpty { valid }
        val referenceLuma = trimSource.map { luma(it.reference) }.average()
        val mappedLuma = trimSource.map { luma(apply(matrix, it.observed)) }.average()
        val trim = if (mappedLuma > 1e-6 && referenceLuma > 1e-6) {
            (referenceLuma / mappedLuma).toFloat().coerceIn(MIN_TRIM, MAX_TRIM)
        } else {
            1f
        }

        return InputProfile(
            matrix = matrix.toList(),
            exposureTrim = trim,
            saturationTrim = 1f,
        )
    }

    /**
     * Rejects degenerate fits (black frames, inverted channels, absurd gains).
     * A rejected calibration stays identity; the UI can still report that no
     * usable measurement was stored.
     */
    private fun isPlausible(matrix: FloatArray): Boolean {
        if (matrix.any { !it.isFinite() || kotlin.math.abs(it) > 2f }) return false
        for (row in 0..2) {
            val sum = matrix[row * 3] + matrix[row * 3 + 1] + matrix[row * 3 + 2]
            if (sum !in 0.5f..1.5f) return false
        }
        return true
    }

    private fun isUsable(sample: Sample): Boolean =
        sample.observed.all { it.isFinite() && it >= 0f && it <= 1f } &&
            sample.reference.all { it.isFinite() && it >= 0f && it <= 1f }

    private fun apply(matrix: FloatArray, rgb: FloatArray): FloatArray = floatArrayOf(
        matrix[0] * rgb[0] + matrix[1] * rgb[1] + matrix[2] * rgb[2],
        matrix[3] * rgb[0] + matrix[4] * rgb[1] + matrix[5] * rgb[2],
        matrix[6] * rgb[0] + matrix[7] * rgb[1] + matrix[8] * rgb[2],
    )

    private fun luma(rgb: FloatArray): Double =
        0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]

    /** Gaussian elimination with partial pivoting on a 3×3 system. */
    private fun solve3x3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val m = Array(3) { a[it].copyOf() }
        val v = b.copyOf()

        for (column in 0..2) {
            var pivot = column
            for (row in column + 1..2) {
                if (kotlin.math.abs(m[row][column]) > kotlin.math.abs(m[pivot][column])) {
                    pivot = row
                }
            }
            if (kotlin.math.abs(m[pivot][column]) < 1e-9) return null
            if (pivot != column) {
                val row = m[pivot]
                m[pivot] = m[column]
                m[column] = row
                val tmp = v[pivot]
                v[pivot] = v[column]
                v[column] = tmp
            }
            for (row in column + 1..2) {
                val factor = m[row][column] / m[column][column]
                for (col in column..2) m[row][col] -= factor * m[column][col]
                v[row] -= factor * v[column]
            }
        }

        val result = DoubleArray(3)
        for (row in 2 downTo 0) {
            var sum = v[row]
            for (col in row + 1..2) sum -= m[row][col] * result[col]
            result[row] = sum / m[row][row]
        }
        return result
    }
}
