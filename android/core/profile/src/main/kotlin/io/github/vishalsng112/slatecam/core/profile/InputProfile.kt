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
) {
    init {
        require(matrix.size == 9) { "colour matrix must be 3x3" }
    }

    fun isIdentity(): Boolean =
        matrix == IDENTITY_MATRIX && exposureTrim == 1f && saturationTrim == 1f

    /** CPU reference of the shader stage, used by tests and future tooling. */
    fun transform(r: Float, g: Float, b: Float): FloatArray {
        val mr = matrix[0] * r + matrix[1] * g + matrix[2] * b
        val mg = matrix[3] * r + matrix[4] * g + matrix[5] * b
        val mb = matrix[6] * r + matrix[7] * g + matrix[8] * b

        var outR = mr * exposureTrim
        var outG = mg * exposureTrim
        var outB = mb * exposureTrim

        if (saturationTrim != 1f) {
            val luma = 0.2126f * outR + 0.7152f * outG + 0.0722f * outB
            outR = luma + (outR - luma) * saturationTrim
            outG = luma + (outG - luma) * saturationTrim
            outB = luma + (outB - luma) * saturationTrim
        }

        return floatArrayOf(outR, outG, outB)
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
