package io.github.vishalsng112.slatecam.core.profile

/**
 * Reference data and conversions for colour-target calibration.
 *
 * Calibration runs in the same scene-linear Rec.2020 working space the shader
 * applies the input matrix in. The captured frame is display-encoded BT.709
 * (the calibration bypass writes BT.709 OETF), so [observedFromEncoded] turns a
 * sampled frame value into linear Rec.2020 before fitting.
 */
object ColorChecker {

    /**
     * ColorChecker Classic 24, patch order left-to-right, top-to-bottom,
     * as sRGB 8-bit values under D65. These are the conventional published
     * readings, not a fresh measurement of the user's chart.
     */
    val CLASSIC_24_SRGB: List<IntArray> = listOf(
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
    )

    /** Indices of the six neutral patches (white to black), zero-based. */
    val NEUTRAL_INDICES: IntRange = 18..23

    /** Linear Rec.2020 reference for every patch, in patch order. */
    fun referenceLinear2020(): List<FloatArray> = CLASSIC_24_SRGB.map { srgb ->
        bt709To2020(
            floatArrayOf(
                srgbToLinear(srgb[0] / 255f),
                srgbToLinear(srgb[1] / 255f),
                srgbToLinear(srgb[2] / 255f),
            ),
        )
    }

    /** One sampled frame pixel/channel (encoded BT.709, 0..1) to linear 2020. */
    fun observedFromEncoded(r: Float, g: Float, b: Float): FloatArray = bt709To2020(
        floatArrayOf(
            bt709ToLinear(r),
            bt709ToLinear(g),
            bt709ToLinear(b),
        ),
    )

    /** BT.709 OETF inverse; mirrors the renderer's `linearToBt709`. */
    fun bt709ToLinear(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return if (x <= 0.081f) {
            x / 4.5f
        } else {
            Math.pow(((x + 0.099f) / 1.099f).toDouble(), 1.0 / 0.45).toFloat()
        }
    }

    /** BT.709 OETF; used to model a captured display-encoded frame. */
    fun linearToBt709(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return if (x < 0.018f) {
            4.5f * x
        } else {
            1.099f * Math.pow(x.toDouble(), 0.45).toFloat() - 0.099f
        }
    }

    /** sRGB EOTF, used for the published ColorChecker values. */
    fun srgbToLinear(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return if (x <= 0.04045f) {
            x / 12.92f
        } else {
            Math.pow(((x + 0.055f) / 1.055).toDouble(), 2.4).toFloat()
        }
    }

    /** Linear BT.709/sRGB primaries to linear Rec.2020 (BT.2087). */
    fun bt709To2020(rgb: FloatArray): FloatArray = floatArrayOf(
        0.627404f * rgb[0] + 0.329283f * rgb[1] + 0.043313f * rgb[2],
        0.069097f * rgb[0] + 0.919540f * rgb[1] + 0.011362f * rgb[2],
        0.016391f * rgb[0] + 0.088013f * rgb[1] + 0.895595f * rgb[2],
    )

    /** Linear Rec.2020 luma weights (BT.2020). */
    fun luma2020(rgb: FloatArray): Float =
        0.2627f * rgb[0] + 0.6780f * rgb[1] + 0.0593f * rgb[2]

    /** Linear Rec.2020 to CIE XYZ (D65). */
    fun linear2020ToXyz(rgb: FloatArray): FloatArray = floatArrayOf(
        0.636958f * rgb[0] + 0.144617f * rgb[1] + 0.168881f * rgb[2],
        0.262700f * rgb[0] + 0.677998f * rgb[1] + 0.059302f * rgb[2],
        0.000000f * rgb[0] + 0.028073f * rgb[1] + 1.060985f * rgb[2],
    )

    /** D65 Lab from linear Rec.2020. */
    fun linear2020ToLab(rgb: FloatArray): FloatArray {
        val xyz = linear2020ToXyz(rgb)
        val fx = labF(xyz[0] / D65_X)
        val fy = labF(xyz[1] / D65_Y)
        val fz = labF(xyz[2] / D65_Z)
        return floatArrayOf(
            116f * fy - 16f,
            500f * (fx - fy),
            200f * (fy - fz),
        )
    }

    /**
     * CIEDE2000 colour difference. Returns 0 for identical colours.
     * Formula and constants follow Sharma, Wu & Dalal (2005).
     */
    fun deltaE2000(lab1: FloatArray, lab2: FloatArray): Float {
        val l1 = lab1[0].toDouble()
        val a1 = lab1[1].toDouble()
        val b1 = lab1[2].toDouble()
        val l2 = lab2[0].toDouble()
        val a2 = lab2[1].toDouble()
        val b2 = lab2[2].toDouble()

        val c1 = kotlin.math.sqrt(a1 * a1 + b1 * b1)
        val c2 = kotlin.math.sqrt(a2 * a2 + b2 * b2)
        val cBar = (c1 + c2) / 2.0
        val cBar7 = Math.pow(cBar, 7.0)
        val g = 0.5 * (1.0 - kotlin.math.sqrt(cBar7 / (cBar7 + POW_25_7)))
        val a1p = (1.0 + g) * a1
        val a2p = (1.0 + g) * a2
        val c1p = kotlin.math.sqrt(a1p * a1p + b1 * b1)
        val c2p = kotlin.math.sqrt(a2p * a2p + b2 * b2)
        val h1p = hueDegrees(b1, a1p)
        val h2p = hueDegrees(b2, a2p)

        val deltaLp = l2 - l1
        val deltaCp = c2p - c1p
        var deltaHp = 0.0
        if (c1p * c2p != 0.0) {
            deltaHp = h2p - h1p
            if (deltaHp > 180.0) deltaHp -= 360.0
            if (deltaHp < -180.0) deltaHp += 360.0
        }
        val deltaBigHp = 2.0 * kotlin.math.sqrt(c1p * c2p) *
            kotlin.math.sin(Math.toRadians(deltaHp / 2.0))

        val lBarP = (l1 + l2) / 2.0
        val cBarP = (c1p + c2p) / 2.0
        val hBarP = meanHue(c1p, c2p, h1p, h2p)

        val t = 1.0 -
            0.17 * kotlin.math.cos(Math.toRadians(hBarP - 30.0)) +
            0.24 * kotlin.math.cos(Math.toRadians(2.0 * hBarP)) +
            0.32 * kotlin.math.cos(Math.toRadians(3.0 * hBarP + 6.0)) -
            0.20 * kotlin.math.cos(Math.toRadians(4.0 * hBarP - 63.0))
        val deltaTheta = 30.0 * kotlin.math.exp(
            -Math.pow((hBarP - 275.0) / 25.0, 2.0),
        )
        val cBarP7 = Math.pow(cBarP, 7.0)
        val rC = 2.0 * kotlin.math.sqrt(cBarP7 / (cBarP7 + POW_25_7))
        val lBar50 = (lBarP - 50.0) * (lBarP - 50.0)
        val sL = 1.0 + 0.015 * lBar50 / kotlin.math.sqrt(20.0 + lBar50)
        val sC = 1.0 + 0.045 * cBarP
        val sH = 1.0 + 0.015 * cBarP * t
        val rT = -kotlin.math.sin(Math.toRadians(2.0 * deltaTheta)) * rC

        val termL = deltaLp / sL
        val termC = deltaCp / sC
        val termH = deltaBigHp / sH
        val squared = termL * termL + termC * termC + termH * termH + rT * termC * termH
        return kotlin.math.sqrt(squared.coerceAtLeast(0.0)).toFloat()
    }

    private fun hueDegrees(b: Double, a: Double): Double {
        if (a == 0.0 && b == 0.0) return 0.0
        val degrees = Math.toDegrees(kotlin.math.atan2(b, a))
        return if (degrees < 0.0) degrees + 360.0 else degrees
    }

    private fun meanHue(c1p: Double, c2p: Double, h1p: Double, h2p: Double): Double {
        if (c1p * c2p == 0.0) return h1p + h2p
        val diff = kotlin.math.abs(h1p - h2p)
        return when {
            diff <= 180.0 -> (h1p + h2p) / 2.0
            h1p + h2p < 360.0 -> (h1p + h2p + 360.0) / 2.0
            else -> (h1p + h2p - 360.0) / 2.0
        }
    }

    private fun labF(value: Float): Float {
        val v = value.coerceAtLeast(0f)
        return if (v > 0.008856f) {
            Math.cbrt(v.toDouble()).toFloat()
        } else {
            7.787f * v + 16f / 116f
        }
    }

    private const val D65_X = 0.95047f
    private const val D65_Y = 1.0f
    private const val D65_Z = 1.08883f
    private const val POW_25_7 = 6103515625.0 // 25^7
}
