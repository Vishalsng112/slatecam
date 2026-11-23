package io.github.vishalsng112.slatecam.core.profile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorCalibrationTest {
    // ColorChecker Classic sRGB (8-bit), in patch order.
    private val reference = listOf(
        intArrayOf(115, 82, 68),
        intArrayOf(194, 150, 130),
        intArrayOf(98, 122, 157),
        intArrayOf(87, 108, 67),
        intArrayOf(133, 128, 177),
        intArrayOf(103, 189, 170),
        intArrayOf(214, 126, 44),
        intArrayOf(80, 91, 166),
        intArrayOf(193, 90, 99),
        intArrayOf(94, 60, 108),
        intArrayOf(157, 188, 64),
        intArrayOf(224, 163, 46),
        intArrayOf(56, 61, 150),
        intArrayOf(70, 148, 73),
        intArrayOf(175, 54, 60),
        intArrayOf(231, 199, 31),
        intArrayOf(187, 86, 149),
        intArrayOf(8, 133, 161),
        intArrayOf(243, 243, 242),
        intArrayOf(200, 200, 200),
        intArrayOf(160, 160, 160),
        intArrayOf(122, 122, 121),
        intArrayOf(85, 85, 85),
        intArrayOf(52, 52, 52),
    ).map { rgb -> rgb.map { it / 255f }.toFloatArray() }

    private val neutralRange = 18..23

    private fun ref(i: Int) = reference[i].copyOf()

    private fun sample(observed: FloatArray, referenceIndex: Int) =
        ColorCalibration.Sample(observed, reference[referenceIndex].copyOf())

    private fun neutral(i: Int) = reference[i].copyOf()

    private fun solveIdentity(): InputProfile {
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
        val profile = solveIdentity()

        assertMatrix(InputProfile.IDENTITY_MATRIX.toFloatArray(), profile.matrix, 0.01f)
        assertEquals(1f, profile.exposureTrim, 0.02f)
        assertEquals(1f, profile.saturationTrim, 0f)
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
        val profile = ColorCalibration.solve(
            samples,
            neutralRange.map { ColorCalibration.Sample(neutral(it), apply(known, neutral(it))) },
        )

        assertMatrix(known, profile.matrix, 0.01f)
        assertEquals(1f, profile.exposureTrim, 0.02f)
    }

    @Test
    fun `a darker exposure is corrected by the matrix`() {
        val samples = reference.indices.map { index ->
            sample(ref(index).map { it * 0.8f }.toFloatArray(), index)
        }
        val profile = ColorCalibration.solve(
            samples,
            neutralRange.map { sample(neutral(it).map { ch -> ch * 0.8f }.toFloatArray(), it) },
        )

        assertMatrix(InputProfile.IDENTITY_MATRIX.map { it * 1.25f }.toFloatArray(), profile.matrix, 0.02f)
        assertEquals(1f, profile.exposureTrim, 0.03f)
    }

    @Test
    fun `a blue cast is neutralised per channel`() {
        val samples = reference.indices.map { index ->
            sample(ref(index).also { it[2] *= 0.9f }, index)
        }
        val profile = ColorCalibration.solve(
            samples,
            neutralRange.map { sample(neutral(it).also { ch -> ch[2] *= 0.9f }, it) },
        )

        val expected = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f / 0.9f,
        )
        assertMatrix(expected, profile.matrix, 0.02f)
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
        val profile = ColorCalibration.solve(samples, doubledNeutrals)

        // The matrix itself is identity; the trim has to remove a 2x lift that
        // only the neutral patches saw.
        assertEquals(0.5f, profile.exposureTrim, 0.05f)
    }

    @Test
    fun `a black frame stays identity instead of blacking out the preview`() {
        val black = reference.indices.map {
            sample(floatArrayOf(0f, 0f, 0f), it)
        }
        val profile = ColorCalibration.solve(black)

        assertMatrix(InputProfile.IDENTITY_MATRIX.toFloatArray(), profile.matrix, 0f)
        assertEquals(1f, profile.exposureTrim, 0f)
    }

    @Test
    fun `non-finite and out-of-range samples are ignored`() {
        val samples = reference.indices.map { sample(ref(it), it) }.toMutableList()
        samples += ColorCalibration.Sample(
            floatArrayOf(Float.NaN, 0.5f, 0.5f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
        )
        samples += ColorCalibration.Sample(
            floatArrayOf(1.5f, 0.5f, 0.5f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
        )

        val profile = ColorCalibration.solve(samples)

        assertMatrix(InputProfile.IDENTITY_MATRIX.toFloatArray(), profile.matrix, 0.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `too few usable samples is rejected`() {
        val samples = (0 until 7).map { sample(ref(it), it) }
        ColorCalibration.solve(samples)
    }

    @Test
    fun `the transform matches the fitted matrix`() {
        val profile = solveIdentity()
        val patch = reference[6]

        assertArrayEquals(patch, profile.transform(patch[0], patch[1], patch[2]), 0.02f)
    }

    private fun apply(matrix: FloatArray, rgb: FloatArray): FloatArray = floatArrayOf(
        matrix[0] * rgb[0] + matrix[1] * rgb[1] + matrix[2] * rgb[2],
        matrix[3] * rgb[0] + matrix[4] * rgb[1] + matrix[5] * rgb[2],
        matrix[6] * rgb[0] + matrix[7] * rgb[1] + matrix[8] * rgb[2],
    )
}
