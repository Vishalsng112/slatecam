package io.github.vishalsng112.slatecam.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class ColorCheckerTest {

    @Test
    fun `ciede2000 matches the published Sharma reference pairs`() {
        fun lab(l: Float, a: Float, b: Float) = floatArrayOf(l, a, b)
        assertEquals(
            2.0425f,
            ColorChecker.deltaE2000(lab(50f, 2.6772f, -79.7751f), lab(50f, 0f, -82.7485f)),
            0.0001f,
        )
        assertEquals(
            2.8615f,
            ColorChecker.deltaE2000(lab(50f, 3.1571f, -77.2803f), lab(50f, 0f, -82.7485f)),
            0.0001f,
        )
        assertEquals(
            3.4412f,
            ColorChecker.deltaE2000(lab(50f, 2.8361f, -74.02f), lab(50f, 0f, -82.7485f)),
            0.0001f,
        )
    }

    @Test
    fun `identical colours have zero difference`() {
        val lab = floatArrayOf(42f, -12f, 31f)
        assertEquals(0f, ColorChecker.deltaE2000(lab, lab.copyOf()), 0.0001f)
    }

    @Test
    fun `white maps to L100 in Lab`() {
        val lab = ColorChecker.linear2020ToLab(floatArrayOf(1f, 1f, 1f))
        assertEquals(100f, lab[0], 0.05f)
        assertEquals(0f, lab[1], 0.05f)
        assertEquals(0f, lab[2], 0.05f)
    }

    @Test
    fun `white balance offsets are derived from the neutral patches`() {        val reference = ColorChecker.referenceLinear2020()
        val samples = reference.mapIndexed { index, rgb ->
            val observed = if (index >= 18) {
                floatArrayOf(rgb[0], rgb[1], rgb[2] * 0.9f)
            } else {
                rgb.copyOf()
            }
            ColorCalibration.Sample(observed, rgb.copyOf())
        }
        val result = ColorCalibration.solve(
            samples,
            ColorChecker.NEUTRAL_INDICES.map { index ->
                ColorCalibration.Sample(
                    floatArrayOf(reference[index][0], reference[index][1], reference[index][2] * 0.9f),
                    reference[index].copyOf(),
                )
            },
        )

        assertTrue(result.profile.wbBOffset > 1.05f)
        assertTrue(result.deltaEAfter < result.deltaEBefore)
    }

    @Test
    fun `the encoded capture path recovers a known camera transform`() {
        // A plausible camera error: channel cross-talk plus a warm cast and an
        // exposure loss. The solver sees only the BT.709-encoded frame, exactly
        // as the bridge does after the bypass capture.
        val camera = floatArrayOf(
            1.04f, -0.02f, -0.02f,
            -0.03f, 1.05f, -0.02f,
            -0.01f, -0.03f, 1.04f,
        )
        val reference = ColorChecker.referenceLinear2020()
        fun capture(rgb: FloatArray): FloatArray {
            val mixed = floatArrayOf(
                camera[0] * rgb[0] + camera[1] * rgb[1] + camera[2] * rgb[2],
                camera[3] * rgb[0] + camera[4] * rgb[1] + camera[5] * rgb[2],
                camera[6] * rgb[0] + camera[7] * rgb[1] + camera[8] * rgb[2],
            )
            val exposed = floatArrayOf(
                mixed[0] * 0.85f,
                mixed[1] * 0.85f,
                mixed[2] * 0.9f * 0.85f,
            )
            return exposed
        }
        fun encoded(rgb: FloatArray): ColorCalibration.Sample {
            val observed = capture(rgb).map { channel ->
                val quantised =
                    (ColorChecker.linearToBt709(channel) * 255f).roundToInt() / 255f
                ColorChecker.bt709ToLinear(quantised)
            }.toFloatArray()
            return ColorCalibration.Sample(observed, rgb)
        }

        val samples = reference.map { encoded(capture(it)) }
        val neutrals = ColorChecker.NEUTRAL_INDICES.map { encoded(capture(reference[it])) }
        val result = ColorCalibration.solve(samples, neutrals)

        assertTrue(
            "mean deltaE should drop from ${result.deltaEBefore} to ${result.deltaEAfter}",
            result.deltaEAfter < result.deltaEBefore / 2f,
        )
        assertTrue("mean deltaE should be small", result.deltaEAfter < 1.0f)
        assertTrue("white balance should lift blue", result.profile.wbBOffset > 1.0f)
        assertTrue(
            "exposure trim should lift the loss",
            result.profile.exposureTrim > 1.0f,
        )
    }
}
