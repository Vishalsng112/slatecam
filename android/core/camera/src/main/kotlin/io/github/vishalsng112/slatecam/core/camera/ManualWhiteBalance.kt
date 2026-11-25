package io.github.vishalsng112.slatecam.core.camera

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Manual white balance: a colour temperature plus a green/magenta tint.
 *
 * The camera cannot be given an absolute white point through public Camera2
 * metadata alone. A camcorder CCM has to neutralise the sensor's native
 * spectral response as well as the illuminant, and the device publishes neither
 * a usable CCM for an arbitrary temperature nor a CCT mode (the Pad 3
 * advertises only FAST, HIGH_QUALITY and TRANSFORM_MATRIX, and feeding the
 * reported sensor transforms back produces a strong green cast).
 *
 * So manual white balance is built on a HAL white-balance preset: the preset
 * owns the sensor colour science, and this class computes only the small
 * kelvin/tint residual between the preset and the requested temperature. The
 * residual is applied in the scene-linear shader, next to the per-lens input
 * calibration.
 *
 * The black-body curve is Tanner Helland's approximation. Absolute accuracy
 * therefore depends on how close the HAL preset sits to its nominal CCT, and is
 * a documented limitation rather than a per-device constant.
 */
object ManualWhiteBalance {

    /** Synthetic wire value. It is not a Camera2 AWB mode. */
    const val WIRE_VALUE = "manual"

    const val MIN_KELVIN = 2000
    const val MAX_KELVIN = 10000
    const val DEFAULT_KELVIN = 5600

    const val MIN_TINT = -100
    const val MAX_TINT = 100
    const val DEFAULT_TINT = 0

    /** Keeps the shader residual gentle; a preset anchor means it stays small. */
    private const val MIN_GAIN = 0.25f
    private const val MAX_GAIN = 4f

    /** Nominal CCT of the standard Camera2 AWB presets. */
    private val PRESETS = listOf(
        WhiteBalancePreset("incandescent", 2856),
        WhiteBalancePreset("warm_fluorescent", 3000),
        WhiteBalancePreset("fluorescent", 4230),
        WhiteBalancePreset("twilight", 4500),
        WhiteBalancePreset("daylight", 5500),
        WhiteBalancePreset("cloudy", 6500),
        WhiteBalancePreset("shade", 7500),
    )

    fun clampKelvin(kelvin: Int): Int = kelvin.coerceIn(MIN_KELVIN, MAX_KELVIN)

    fun clampTint(tint: Int): Int = tint.coerceIn(MIN_TINT, MAX_TINT)

    /**
     * Nearest supported preset to [kelvin], or `null` when the sensor offers
     * none. Manual white balance is gated on this: without a preset there is no
     * sensor colour base to build on.
     */
    fun presetFor(kelvin: Int, awbModes: List<String>): WhiteBalancePreset? {
        val target = clampKelvin(kelvin)
        return PRESETS
            .filter { it.mode in awbModes }
            .minByOrNull { abs(it.cct - target) }
    }

    /** The nominal preset for a wire mode, or `null` when it is not a preset. */
    fun presetForMode(mode: String): WhiteBalancePreset? =
        PRESETS.firstOrNull { it.mode == mode }

    /**
     * Keeps the user's white balance across a camera switch: manual survives
     * when the new sensor supports it, otherwise the mode falls back to auto.
     */
    fun restoreMode(
        current: String,
        awbModes: List<String>,
        manualSupported: Boolean,
    ): String = when {
        current == WIRE_VALUE && manualSupported -> WIRE_VALUE
        // Older builds persisted the raw AWB "off" state; manual owns it now.
        current == "off" && manualSupported -> WIRE_VALUE
        current in awbModes && current != WIRE_VALUE -> current
        "auto" in awbModes -> "auto"
        else -> awbModes.firstOrNull() ?: "auto"
    }

    /**
     * Red and blue scale factors that turn the [baseCct] preset into [kelvin],
     * with green normalised to 1.
     *
     * Positive tint adds magenta (red and blue up), negative adds green.
     */
    fun residualGains(kelvin: Int, tint: Int, baseCct: Int): WhiteBalanceGains {
        val target = illuminantRgb(kelvin)
        val base = illuminantRgb(baseCct)
        val greenRatio = base[1] / target[1].coerceAtLeast(1e-4f)
        val tintScale = 1f + (clampTint(tint) / 100f) * 0.5f
        val red = (base[0] / target[0].coerceAtLeast(1e-4f)) / greenRatio * tintScale
        val blue = (base[2] / target[2].coerceAtLeast(1e-4f)) / greenRatio * tintScale
        return WhiteBalanceGains(
            red = red.coerceIn(MIN_GAIN, MAX_GAIN),
            green = 1f,
            blue = blue.coerceIn(MIN_GAIN, MAX_GAIN),
        )
    }

    /** Approximate black-body colour at [kelvin], each channel 0..1. */
    fun illuminantRgb(kelvin: Int): FloatArray {
        val t = clampKelvin(kelvin) / 100f
        val red = if (t <= 66.0) {
            255.0
        } else {
            329.698727446 * (t - 60.0).pow(-0.1332047592)
        }
        val green = if (t <= 66.0) {
            99.4708025861 * ln(t) - 161.1195681661
        } else {
            288.1221695283 * (t - 60.0).pow(-0.0755148492)
        }
        val blue = when {
            t >= 66.0 -> 255.0
            t <= 19.0 -> 0.0
            else -> 138.5177312231 * ln(t - 10.0) - 305.0447927307
        }
        return floatArrayOf(
            (red / 255.0).toFloat().coerceIn(0f, 1f),
            (green / 255.0).toFloat().coerceIn(0f, 1f),
            (blue / 255.0).toFloat().coerceIn(0f, 1f),
        )
    }
}

/** A HAL white-balance preset and its nominal correlated colour temperature. */
data class WhiteBalancePreset(val mode: String, val cct: Int)

/** Channel scale factors with green normalised to 1. */
data class WhiteBalanceGains(
    val red: Float,
    val green: Float,
    val blue: Float,
)
