package io.github.vishalsng112.slatecam.core.probe.eval

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.tan

/**
 * Classifies lenses relative to each other, following CameraX's convention:
 * a "default" lens has zoom ratio 1.0, an ultra-wide lens is below it and a
 * telephoto lens is above it.
 *
 * Android exposes no "main camera" flag. The reference lens is therefore an
 * explicit policy: the lens with a field of view closest to 70° (a normal
 * phone lens), tie-broken by capability score and then ID. Everything else is
 * judged against that reference. Roles are relative on purpose — a phone's
 * "wide" is another phone's "ultra-wide".
 */
object CameraRoles {

    /** 35 mm film diagonal in millimetres. */
    private const val FULL_FRAME_DIAGONAL_MM = 43.266

    private const val DEFAULT_FOV_TARGET = 70.0
    private const val TELE_MAX_FOV = 50.0
    private const val ULTRAWIDE_MIN_FOV = 100.0
    private const val ULTRAWIDE_RATIO = 0.85
    private const val TELE_RATIO = 1.25
    private const val MACRO_MAX_PIXELS = 2_500_000L

    data class Candidate(
        val id: String,
        val facing: String,
        /** Horizontal field of view in degrees, null when geometry is missing. */
        val hfovDeg: Double?,
        /** Higher wins ties: RAW support, sensor size, verified presets, etc. */
        val score: Int = 0,
        /** Listed by cameraIdList; preferred as the default lens. */
        val exposed: Boolean = true,
        /** True when LENS_INFO_MINIMUM_FOCUS_DISTANCE is zero. */
        val fixedFocus: Boolean = false,
        /** Sensor pixel count, used to separate a macro from the main lens. */
        val pixelCount: Long = 0,
    )

    data class Role(
        /** default | ultrawide | tele | macro | unknown */
        val role: String,
        /** intrinsic_zoom_ratio | fov_ratio | absolute_fov | only_camera | no_optical_data | fixed_focus_small_sensor */
        val basis: String,
        /** high | medium | low */
        val confidence: String,
        val intrinsicZoomRatio: Double?,
    )

    fun classify(candidates: List<Candidate>): Map<String, Role> {
        val result = LinkedHashMap<String, Role>()
        candidates.groupBy { it.facing }.forEach { (_, group) ->
            result.putAll(classifyFacing(group))
        }
        return result
    }

    /** 35 mm-equivalent focal length from physical sensor geometry. */
    fun focalLength35mmEquivalent(
        focalLengthMm: Double?,
        sensorWidthMm: Double?,
        sensorHeightMm: Double?,
    ): Double? {
        if (focalLengthMm == null || focalLengthMm <= 0.0) return null
        val width = sensorWidthMm ?: return null
        val height = sensorHeightMm ?: return null
        if (width <= 0.0 || height <= 0.0) return null
        return focalLengthMm * FULL_FRAME_DIAGONAL_MM / hypot(width, height)
    }

    /** Horizontal field of view from focal length and sensor width. */
    fun horizontalFovDegrees(focalLengthMm: Double?, sensorWidthMm: Double?): Double? {
        if (focalLengthMm == null || focalLengthMm <= 0.0) return null
        if (sensorWidthMm == null || sensorWidthMm <= 0.0) return null
        return 2.0 * atan((sensorWidthMm / 2.0) / focalLengthMm) * 180.0 / PI
    }

    private fun classifyFacing(group: List<Candidate>): Map<String, Role> {
        val result = LinkedHashMap<String, Role>()

        if (group.size == 1) {
            result[group.first().id] = Role(
                role = "default",
                basis = "only_camera",
                confidence = "low",
                intrinsicZoomRatio = 1.0,
            )
            return result
        }

        val valid = group.filter { it.hfovDeg != null && it.hfovDeg in 5.0..170.0 }
        group.filter { it !in valid }.forEach {
            result[it.id] = Role("unknown", "no_optical_data", "low", null)
        }
        if (valid.isEmpty()) return result
        if (valid.size == 1) {
            result[valid.first().id] = Role("default", "only_camera", "low", 1.0)
            return result
        }

        val reference = valid
            .filter { it.hfovDeg!! > TELE_MAX_FOV && it.hfovDeg < ULTRAWIDE_MIN_FOV }
            .minWithOrNull(
                compareByDescending<Candidate> { it.exposed }
                    .thenByDescending { it.score }
                    .thenBy { abs(it.hfovDeg!! - DEFAULT_FOV_TARGET) }
                    .thenBy { it.id },
            )

        val refFov = reference?.hfovDeg
            ?: valid.map { it.hfovDeg!! }.sorted()[valid.size / 2]

        val roles = valid.associate { candidate ->
            val fov = candidate.hfovDeg!!
            val ratio = if (candidate === reference || fov == refFov) {
                1.0
            } else {
                tan(Math.toRadians(refFov / 2.0)) / tan(Math.toRadians(fov / 2.0))
            }
            val role = when {
                fov >= ULTRAWIDE_MIN_FOV -> "ultrawide"
                fov <= TELE_MAX_FOV && ratio >= TELE_RATIO -> "tele"
                reference != null && ratio <= ULTRAWIDE_RATIO -> "ultrawide"
                reference != null && ratio >= TELE_RATIO -> "tele"
                reference == null ->
                    if (fov >= ULTRAWIDE_MIN_FOV) "ultrawide"
                    else if (fov <= TELE_MAX_FOV) "tele"
                    else "default"
                else -> "default"
            }
            candidate to Role(
                role = role,
                basis = if (reference != null) "fov_ratio" else "absolute_fov",
                confidence = "high",
                intrinsicZoomRatio = round(ratio),
            )
        }

        val defaults = roles.count { it.value.role == "default" }
        val anyDecisive = roles.any { it.value.role != "default" }
        val largestDefault = roles
            .filter { it.value.role == "default" }
            .maxByOrNull { it.key.pixelCount }
            ?.key
        roles.forEach { (candidate, role) ->
            val isMacro = role.role == "default" &&
                candidate.fixedFocus &&
                candidate.pixelCount in 1..MACRO_MAX_PIXELS &&
                largestDefault != null &&
                candidate.pixelCount < largestDefault.pixelCount
            result[candidate.id] = if (isMacro) {
                role.copy(
                    role = "macro",
                    basis = "fixed_focus_small_sensor",
                    confidence = "medium",
                )
            } else {
                role.copy(
                    confidence = when {
                        role.role == "default" && (defaults > 1 || !anyDecisive) -> "medium"
                        else -> role.confidence
                    },
                )
            }
        }
        return result
    }

    private fun round(value: Double): Double = Math.round(value * 1000.0) / 1000.0
}
