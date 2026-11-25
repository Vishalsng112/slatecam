package io.github.vishalsng112.slatecam.core.profile

/**
 * Fits a per-camera input profile from colour-target samples.
 *
 * Samples are in **linear Rec.2020**, the same working space the renderer
 * applies `uInputMatrix` in (ADR 0018). Callers convert the captured,
 * display-encoded frame with [ColorChecker.observedFromEncoded] before solving;
 * a matrix fitted in an encoded domain would be applied to the wrong values.
 *
 * The solver first neutralises the illuminant using the neutral patches
 * (`wb_r_offset` / `wb_b_offset`), then fits a 3×3 channel mix and an exposure
 * trim. Saturation is left at 1 so a calibration never changes the creative
 * look beyond channel alignment. There is deliberately no offset term: the
 * shader stage is a 3×3 matrix, so the fit does not pretend it can correct
 * black level.
 */
object ColorCalibration {
    /** RGB triple in linear Rec.2020, each channel >= 0. */
    data class Sample(
        val observed: FloatArray,
        val reference: FloatArray,
    ) {
        init {
            require(observed.size == 3) { "observed must be RGB" }
            require(reference.size == 3) { "reference must be RGB" }
        }
    }

    /** Fitted profile plus the measured error before and after correction. */
    data class Result(
        val profile: InputProfile,
        val accepted: Boolean,
        val deltaEBefore: Float,
        val deltaEAfter: Float,
        val deltaEMaxAfter: Float,
        val patchDeltaEAfter: List<Float>,
    )

    private const val RIDGE = 1e-4
    private const val MIN_SAMPLES = 8
    private const val MIN_TRIM = 0.25f
    private const val MAX_TRIM = 4f
    private const val MIN_WB = 0.25f
    private const val MAX_WB = 4f
    private const val EPSILON = 1e-6f

    /**
     * [samples] are all patches; [neutrals] is the neutral subset used to set
     * the white balance and exposure trim. When [neutrals] is empty the white
     * balance stays identity and the trim is derived from every sample.
     */
    fun solve(
        samples: List<Sample>,
        neutrals: List<Sample> = emptyList(),
    ): Result {
        val valid = samples.filter(::isUsable)
        require(valid.size >= MIN_SAMPLES) {
            "need at least $MIN_SAMPLES usable samples, got ${valid.size}"
        }

        val neutralValid = neutrals.filter(::isUsable)
        val gains = neutralGains(neutralValid)
        val balanced = valid.map { Sample(applyGains(it.observed, gains), it.reference) }

        val matrix = fit(balanced)
        if (matrix == null) {
            // A degenerate fit (no chart, black frame, extreme glare) stays
            // identity. Report the real per-patch error so the UI can tell the
            // user the measurement was rejected instead of showing 0.
            val references = valid.map { it.reference }
            val patchDelta = balanced.indices.map { index ->
                deltaE(balanced[index].observed, references[index])
            }
            return Result(
                profile = InputProfile.IDENTITY,
                accepted = false,
                deltaEBefore = patchDelta.average().toFloat(),
                deltaEAfter = patchDelta.average().toFloat(),
                deltaEMaxAfter = patchDelta.maxOrNull() ?: 0f,
                patchDeltaEAfter = patchDelta,
            )
        }

        val trimSource = neutralValid.ifEmpty { valid }
        val balancedTrim = trimSource.map { Sample(applyGains(it.observed, gains), it.reference) }
        val referenceLuma = balancedTrim.map { ColorChecker.luma2020(it.reference) }.average()
        val mappedLuma = balancedTrim.map {
            ColorChecker.luma2020(apply(matrix, it.observed))
        }.average()
        val trim = if (mappedLuma > EPSILON && referenceLuma > EPSILON) {
            (referenceLuma / mappedLuma).toFloat().coerceIn(MIN_TRIM, MAX_TRIM)
        } else {
            1f
        }

        val profile = InputProfile(
            matrix = matrix.toList(),
            exposureTrim = trim,
            saturationTrim = 1f,
            wbROffset = gains[0],
            wbBOffset = gains[2],
        )

        val beforeScale = if (referenceLuma > EPSILON && balancedTrim
                .map { ColorChecker.luma2020(it.observed) }
                .average() > EPSILON
        ) {
            (referenceLuma / balancedTrim.map { ColorChecker.luma2020(it.observed) }.average())
                .toFloat()
        } else {
            1f
        }
        val before = balanced.map { applyScale(it.observed, beforeScale) }
        val after = balanced.map { applyScale(apply(matrix, it.observed), trim) }
        val references = valid.map { it.reference }
        val patchDelta = after.indices.map { index ->
            deltaE(after[index], references[index])
        }

        return Result(
            profile = profile,
            accepted = true,
            deltaEBefore = meanDeltaE(before, references),
            deltaEAfter = patchDelta.average().toFloat(),
            deltaEMaxAfter = patchDelta.maxOrNull() ?: 0f,
            patchDeltaEAfter = patchDelta,
        )
    }

    private fun fit(samples: List<Sample>): FloatArray? {
        if (samples.size < MIN_SAMPLES) return null
        val matrix = FloatArray(9)
        for (channel in 0..2) {
            val ata = Array(3) { DoubleArray(3) }
            val atb = DoubleArray(3)
            samples.forEach { sample ->
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
            val coefficients = solve3x3(ata, atb) ?: return null
            matrix[channel * 3] = coefficients[0].toFloat()
            matrix[channel * 3 + 1] = coefficients[1].toFloat()
            matrix[channel * 3 + 2] = coefficients[2].toFloat()
        }
        return if (isPlausible(matrix)) matrix else null
    }

    /**
     * Per-channel multipliers from the neutral patches. Green is the reference
     * and stays at 1; only the red and blue offsets exist in the input profile.
     */
    private fun neutralGains(neutrals: List<Sample>): FloatArray {
        if (neutrals.isEmpty()) return floatArrayOf(1f, 1f, 1f)
        val observed = meanRgb(neutrals.map { it.observed })
        val reference = meanRgb(neutrals.map { it.reference })
        if (observed[0] <= EPSILON || observed[1] <= EPSILON || observed[2] <= EPSILON) {
            return floatArrayOf(1f, 1f, 1f)
        }
        val greenRatio = reference[1] / observed[1]
        val red = ((reference[0] / observed[0]) / greenRatio).coerceIn(MIN_WB, MAX_WB)
        val blue = ((reference[2] / observed[2]) / greenRatio).coerceIn(MIN_WB, MAX_WB)
        return floatArrayOf(red, 1f, blue)
    }

    private fun meanRgb(values: List<FloatArray>): FloatArray {
        val sum = FloatArray(3)
        values.forEach { rgb ->
            for (i in 0..2) sum[i] += rgb[i]
        }
        val count = values.size.coerceAtLeast(1)
        for (i in 0..2) sum[i] /= count
        return sum
    }

    private fun applyGains(rgb: FloatArray, gains: FloatArray): FloatArray {
        if (gains[0] == 1f && gains[2] == 1f) return rgb.copyOf()
        return floatArrayOf(
            rgb[0] * gains[0],
            rgb[1] * gains[1],
            rgb[2] * gains[2],
        )
    }

    private fun applyScale(rgb: FloatArray, scale: Float): FloatArray = floatArrayOf(
        rgb[0] * scale,
        rgb[1] * scale,
        rgb[2] * scale,
    )

    private fun deltaE(a: FloatArray, b: FloatArray): Float = ColorChecker.deltaE2000(
        ColorChecker.linear2020ToLab(a),
        ColorChecker.linear2020ToLab(b),
    )

    private fun meanDeltaE(a: List<FloatArray>, b: List<FloatArray>): Float {
        if (a.isEmpty()) return 0f
        var sum = 0f
        for (i in a.indices) sum += deltaE(a[i], b[i])
        return sum / a.size
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
        sample.observed.all { it.isFinite() && it >= 0f } &&
            sample.reference.all { it.isFinite() && it >= 0f }

    private fun apply(matrix: FloatArray, rgb: FloatArray): FloatArray = floatArrayOf(
        matrix[0] * rgb[0] + matrix[1] * rgb[1] + matrix[2] * rgb[2],
        matrix[3] * rgb[0] + matrix[4] * rgb[1] + matrix[5] * rgb[2],
        matrix[6] * rgb[0] + matrix[7] * rgb[1] + matrix[8] * rgb[2],
    )

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
