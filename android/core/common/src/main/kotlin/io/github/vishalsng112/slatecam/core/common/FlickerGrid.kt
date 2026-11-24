package io.github.vishalsng112.slatecam.core.common

import java.util.Locale

/**
 * Flicker-safe exposure times.
 *
 * Camera2 only honours `CONTROL_AE_ANTIBANDING_MODE` while auto-exposure owns
 * the sensor. In manual/S mode the app fixes `SENSOR_EXPOSURE_TIME`, so it must
 * avoid banding itself: the light under 50 Hz mains pulses at 100 Hz, and an
 * exposure only integrates a constant amount of light when it covers whole
 * 10 ms flicker periods. 60 Hz mains pulses at 120 Hz, so the grid is 1/120 s.
 *
 * `auto` cannot be read back after AE is disabled. The fallback is the device
 * region's mains frequency, defaulting to 50 Hz (most of the world) when the
 * region is unknown. This is environment data, not a per-model branch.
 */
object FlickerGrid {

    private const val NS_PER_SECOND = 1_000_000_000L

    /** One 100 Hz flicker period. */
    const val FIFTY_HZ_STEP_NS = NS_PER_SECOND / 100L

    /** One 120 Hz flicker period. */
    const val SIXTY_HZ_STEP_NS = NS_PER_SECOND / 120L

    /**
     * Countries with a 60 Hz public supply. Everywhere else, and for an empty
     * or unknown region, the safe default is 50 Hz. Japan is intentionally
     * omitted because its regions use both frequencies.
     */
    private val SIXTY_HZ_REGIONS = setOf(
        "US", "CA", "MX", "BR", "CL", "PE", "CO", "VE", "EC",
        "PA", "PR", "DO", "GT", "HN", "SV", "NI", "CR", "JM", "TT", "BS",
        "CU", "HT", "BB", "BZ", "GD", "LC", "VC", "AG", "DM", "KN",
        "KR", "TW", "PH", "SA", "GU", "AS",
    )

    /**
     * Exposure grid for an anti-banding mode, or `null` when the mode has no
     * safe grid (`off`) and the shutter should stay continuous.
     */
    fun stepNs(antibanding: String, region: String? = Locale.getDefault().country): Long? =
        when (antibanding) {
            "50hz" -> FIFTY_HZ_STEP_NS
            "60hz" -> SIXTY_HZ_STEP_NS
            "auto" -> if (region?.uppercase(Locale.US) in SIXTY_HZ_REGIONS) {
                SIXTY_HZ_STEP_NS
            } else {
                FIFTY_HZ_STEP_NS
            }
            else -> null
        }

    /**
     * Rounds [ns] to the nearest multiple of [stepNs] inside the sensor's
     * `[minNs, maxNs]` range. The first valid multiple is the smallest one at
     * or above `minNs`; a range shorter than one step is returned clamped but
     * unquantised.
     */
    fun snap(ns: Long, stepNs: Long?, minNs: Long, maxNs: Long): Long {
        val clamped = ns.coerceIn(minNs, maxNs)
        if (stepNs == null || stepNs <= 0L) return clamped
        val first = ceilToMultiple(minNs, stepNs)
        val last = (maxNs / stepNs) * stepNs
        if (first > last) return clamped
        val rounded = ((clamped + stepNs / 2L) / stepNs) * stepNs
        return rounded.coerceIn(first, last)
    }

    private fun ceilToMultiple(value: Long, step: Long): Long {
        val remainder = value % step
        return if (remainder == 0L) value else value + (step - remainder)
    }
}
