package io.github.vishalsng112.slatecam.core.probe.eval

import io.github.vishalsng112.slatecam.core.probe.model.FloatRange
import io.github.vishalsng112.slatecam.core.probe.model.LogicalZoomInfo
import io.github.vishalsng112.slatecam.core.probe.model.LogicalZoomStop
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Computes the lens stops a logical multi-camera can reach through
 * `CONTROL_ZOOM_RATIO`.
 *
 * This is discovery only: [LogicalZoomInfo.handoffEligible] means the session
 * shape, range and physical ratios look usable. The `logicalZoomHandoff`
 * recording verdict still has to pass before the controller takes the path.
 */
object LogicalZoom {
    /** One physical camera as the probe read it. */
    data class PhysicalLens(
        val id: String,
        val role: String,
        val intrinsicRatio: Double?,
        val confidence: String = "low",
    )

    private const val RANGE_EPSILON = 0.005
    private const val RATIO_EPSILON = 0.01

    fun evaluate(
        logicalMultiCamera: Boolean,
        physicalIds: List<String>,
        lenses: List<PhysicalLens>,
        ratioRange: FloatRange?,
    ): LogicalZoomInfo {
        if (!logicalMultiCamera) {
            return unavailable(ratioRange, "not_logical_multi_camera")
        }
        if (physicalIds.size < 2) {
            return unavailable(ratioRange, "fewer_than_two_physical_cameras")
        }
        if (ratioRange == null || ratioRange.min <= 0f || ratioRange.max < ratioRange.min) {
            return unavailable(ratioRange, "no_zoom_ratio_range")
        }
        // The zoom path must be able to reach the ultra-wide region; otherwise
        // the existing session switch is the only route to a wider lens.
        if (ratioRange.min >= 1.0f) {
            return unavailable(ratioRange, "zoom_range_does_not_cover_ultrawide")
        }

        val min = ratioRange.min.toDouble()
        val max = ratioRange.max.toDouble()
        val reachable = LinkedHashMap<Int, LogicalZoomStop>()
        val uncovered = LinkedHashSet<Double>()

        lenses.forEach { lens ->
            val ratio = lens.intrinsicRatio ?: return@forEach
            if (!ratio.isFinite() || ratio <= 0.0) return@forEach
            if (ratio < min - RANGE_EPSILON || ratio > max + RANGE_EPSILON) {
                uncovered += round(ratio)
                return@forEach
            }
            val key = (ratio * 100).roundToInt()
            val candidate = LogicalZoomStop(
                physicalId = lens.id,
                role = lens.role,
                ratio = round(ratio),
                basis = "physical_intrinsic",
                roleConfidence = lens.confidence,
            )
            val existing = reachable[key]
            if (existing == null || preference(candidate) > preference(existing)) {
                reachable[key] = candidate
            }
        }

        // CONTROL_ZOOM_RATIO is relative to the logical camera's default lens.
        // Add the 1.0 stop even when physical metadata is absent.
        if (reachable.keys.none { abs(it - 100) <= 1 }) {
            val default = LogicalZoomStop(
                physicalId = "",
                role = "default",
                ratio = 1.0,
                basis = "logical_default",
                roleConfidence = "low",
            )
            reachable[100] = default
        }

        val stops = reachable.values.sortedBy { it.ratio }
        val hasWide = stops.any { it.ratio < 1.0 }
        val hasNormalOrTele = stops.any { it.ratio >= 1.0 }
        val eligible = stops.size >= 2 && hasWide && hasNormalOrTele

        return LogicalZoomInfo(
            handoffEligible = eligible,
            ratioRange = ratioRange,
            stops = stops,
            uncoveredRatios = uncovered.sorted(),
            reason = if (eligible) "" else "fewer_than_two_reachable_stops",
        )
    }

    private fun preference(stop: LogicalZoomStop): Int {
        val role = when (stop.role) {
            "default" -> 3
            "ultrawide", "tele" -> 2
            "macro" -> 1
            else -> 0
        }
        val confidence = when (stop.roleConfidence) {
            "high" -> 2
            "medium" -> 1
            else -> 0
        }
        return role * 10 + confidence
    }

    private fun unavailable(ratioRange: FloatRange?, reason: String) =
        LogicalZoomInfo(
            handoffEligible = false,
            ratioRange = ratioRange,
            reason = reason,
        )

    private fun round(value: Double): Double = (value * 1000.0).roundToInt() / 1000.0
}
