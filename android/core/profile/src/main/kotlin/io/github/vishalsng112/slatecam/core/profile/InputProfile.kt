package io.github.vishalsng112.slatecam.core.profile

/**
 * Per-device, per-camera normalisation applied before any shared look.
 *
 * Defaults to identity. Calibration data (a ColorChecker-derived 3×3 matrix plus
 * trims) fills it in Phase 7, so the same creative LUT behaves consistently
 * across different sensors without device-specific code.
 *
 * The matrix is row-major RGB.
 */
data class InputProfile(
    val version: Int = 1,
    val deviceKey: String? = null,
    val cameraId: String? = null,
    val matrix: List<Float> = IDENTITY_MATRIX,
    val exposureTrim: Float = 1f,
    val saturationTrim: Float = 1f,
    /** Per-lens white-balance multipliers applied before the CCM. */
    val wbROffset: Float = 1f,
    val wbBOffset: Float = 1f,
    /** Small display-space shadow lift used to align uncalibrated lenses. */
    val shadowLift: Float = 0f,
) {
    init {
        require(matrix.size == 9) { "colour matrix must be 3x3" }
    }

    fun isIdentity(): Boolean =
        matrix == IDENTITY_MATRIX &&
            exposureTrim == 1f &&
            saturationTrim == 1f &&
            wbROffset == 1f &&
            wbBOffset == 1f &&
            shadowLift == 0f

    /** CPU reference of the shader stage, used by tests and future tooling. */
    fun transform(r: Float, g: Float, b: Float): FloatArray {
        val wr = r * wbROffset
        val wg = g
        val wb = b * wbBOffset
        val mr = matrix[0] * wr + matrix[1] * wg + matrix[2] * wb
        val mg = matrix[3] * wr + matrix[4] * wg + matrix[5] * wb
        val mb = matrix[6] * wr + matrix[7] * wg + matrix[8] * wb

        var outR = mr * exposureTrim
        var outG = mg * exposureTrim
        var outB = mb * exposureTrim

        if (saturationTrim != 1f) {
            val luma = 0.2126f * outR + 0.7152f * outG + 0.0722f * outB
            outR = luma + (outR - luma) * saturationTrim
            outG = luma + (outG - luma) * saturationTrim
            outB = luma + (outB - luma) * saturationTrim
        }

        return floatArrayOf(
            outR + shadowLift,
            outG + shadowLift,
            outB + shadowLift,
        )
    }

    fun matrixArray(): FloatArray = matrix.toFloatArray()

    companion object {
        val IDENTITY_MATRIX = listOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
        )
        val IDENTITY = InputProfile()
    }
}
