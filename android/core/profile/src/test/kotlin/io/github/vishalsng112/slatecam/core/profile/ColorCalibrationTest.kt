package io.github.vishalsng112.slatecam.core.profile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorCalibrationTest {
    // ColorChecker Classic 24, linear Rec.2020, in patch order.
    private val reference = ColorChecker.referenceLinear2020()

    private val neutralRange = ColorChecker.NEUTRAL_INDICES

    private fun ref(i: Int) = reference[i].copyOf()

    private fun sample(observed: FloatArray, referenceIndex: Int) =
        ColorCalibration.Sample(observed, reference[referenceIndex].copyOf())

    private fun neutral(i: Int) = reference[i].copyOf()

    private fun solveIdentity(): ColorCalibration.Result {
        val samples = reference.indices.map { sample(ref(it), it) }
        return ColorCalibration.solve(samples, neutralRange.map { sample(neutral(it), it) })
    }

    private fun assertMatrix(expected: FloatArray, actual: List<Float>, tolerance: Float = 0.02f) {
        assertEquals(9, actual.size)
        expected.indices.forEach { index ->
            assertTrue(
                "matrix[$index] expected ${expected[index]} but was ${actual[index]}",
                abs(expected[index] - actual[index]) <= tolerance,
            )
        }
    }

    @Test
    fun `an unmodified target solves to the identity`() {
        val result = solveIdentity()

        assertMatrix(InputProfile.IDENTITY_MATRIX.toFloatArray(), result.profile.matrix, 0.01f)
        assertEquals(1f, result.profile.exposureTrim, 0.02f)
        assertEquals(1f, result.profile.saturationTrim, 0f)
        assertEquals(1f, result.profile.wbROffset, 0.01f)
        assertEquals(1f, result.profile.wbBOffset, 0.01f)
        assertTrue(result.accepted)
        assertTrue("deltaE after should be near zero", result.deltaEAfter < 0.1f)
    }

    @Test
    fun `a known channel mix is recovered`() {
        val known = floatArrayOf(
            1.02f, -0.01f, -0.01f,
            -0.01f, 1.02f, -0.01f,
            -0.01f, -0.01f, 1.02f,
        )
        val samples = reference.indices.map { index ->
            ColorCalibration.Sample(ref(index), apply(known, ref(index)))
        }
        val result = ColorCalibration.solve(
            samples,
            neutralRange.map { ColorCalibration.Sample(neutral(it), apply(known, neutral(it))) },
        )

        assertMatrix(known, result.profile.matrix, 0.01f)
        assertEquals(1f, result.profile.exposureTrim, 0.02f)
        assertTrue(result.deltaEAfter < 0.2f)
    }

    @Test
    fun `a darker exposure is corrected by the matrix`() {
        val samples = reference.indices.map { index ->
            sample(ref(index).map { it * 0.8f }.toFloatArray(), index)
        }
        val result = ColorCalibration.solve(
            samples,
            neutralRange.map { sample(neutral(it).map { ch -> ch * 0.8f }.toFloatArray(), it) },
        )

        assertMatrix(
            InputProfile.IDENTITY_MATRIX.map { it * 1.25f }.toFloatArray(),
            result.profile.matrix,
            0.02f,
        )
        assertEquals(1f, result.profile.exposureTrim, 0.03f)
    }

    @Test
    fun `a blue cast is neutralised by the white-balance offsets`() {
        val samples = reference.indices.map { index ->
            sample(ref(index).also { it[2] *= 0.9f }, index)
        }
        val result = ColorCalibration.solve(
            samples,
            neutralRange.map { sample(neutral(it).also { ch -> ch[2] *= 0.9f }, it) },
        )

        assertMatrix(InputProfile.IDENTITY_MATRIX.toFloatArray(), result.profile.matrix, 0.02f)
        assertEquals(1f / 0.9f, result.profile.wbBOffset, 0.01f)
        assertEquals(1f, result.profile.wbROffset, 0.01f)
    }

    @Test
    fun `the neutral patches drive the exposure trim`() {
        val samples = reference.indices.map { index -> sample(ref(index), index) }
        val doubledNeutrals = neutralRange.map { index ->
            ColorCalibration.Sample(
                floatArrayOf(
                    reference[index][0] * 2f,
                    reference[index][1] * 2f,
                    reference[index][2] * 2f,
                ),
                reference[index].copyOf(),
            )
        }
        val result = ColorCalibration.solve(samples, doubledNeutrals)

        // The matrix itself is identity; the trim has to remove a 2x lift that
        // only the neutral patches saw.
        assertEquals(0.5f, result.profile.exposureTrim, 0.05f)
    }

    @Test
    fun `a black frame stays identity instead of blacking out the preview`() {
        val black = reference.indices.map {
            sample(floatArrayOf(0f, 0f, 0f), it)
        }
        val result = ColorCalibration.solve(black)

        assertMatrix(InputProfile.IDENTITY_MATRIX.toFloatArray(), result.profile.matrix, 0f)
        assertEquals(1f, result.profile.exposureTrim, 0f)
        assertTrue(!result.accepted)
        assertTrue(result.deltaEMaxAfter > 0f)
    }

    @Test
    fun `non-finite and negative samples are ignored`() {
        val samples = reference.indices.map { sample(ref(it), it) }.toMutableList()
        samples += ColorCalibration.Sample(
            floatArrayOf(Float.NaN, 0.5f, 0.5f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
        )
        samples += ColorCalibration.Sample(
            floatArrayOf(-0.1f, 0.5f, 0.5f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
        )

        val result = ColorCalibration.solve(samples)

        assertMatrix(InputProfile.IDENTITY_MATRIX.toFloatArray(), result.profile.matrix, 0.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `too few usable samples is rejected`() {
        val samples = (0 until 7).map { sample(ref(it), it) }
        ColorCalibration.solve(samples)
    }

    @Test
    fun `the transform matches the fitted matrix`() {
        val result = solveIdentity()
        val patch = reference[6]

        assertArrayEquals(
            patch,
            result.profile.transform(patch[0], patch[1], patch[2]),
            0.02f,
        )
    }

    private fun apply(matrix: FloatArray, rgb: FloatArray): FloatArray = floatArrayOf(
        matrix[0] * rgb[0] + matrix[1] * rgb[1] + matrix[2] * rgb[2],
        matrix[3] * rgb[0] + matrix[4] * rgb[1] + matrix[5] * rgb[2],
        matrix[6] * rgb[0] + matrix[7] * rgb[1] + matrix[8] * rgb[2],
    )
}
