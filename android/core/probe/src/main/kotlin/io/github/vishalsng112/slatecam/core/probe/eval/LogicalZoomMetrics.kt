package io.github.vishalsng112.slatecam.core.probe.eval


/**
 * Pure analysis for the `logicalZoomHandoff` verdict.
 *
 * The verifier captures a short camera stream while sweeping across the
 * logical zoom stops. A pass means the frames kept arriving and exposure/WB did
 * not jump at a handoff. It does not judge image quality or focus.
 */
object LogicalZoomMetrics {
    data class Sample(
        val timestampNs: Long,
        /** SENSOR_EXPOSURE_TIME, when reported. */
        val exposureNs: Long? = null,
        /** SENSOR_SENSITIVITY, when reported. */
        val iso: Int? = null,
        /** COLOR_CORRECTION_GAINS red/green/blue, when reported. */
        val wbRed: Float? = null,
        val wbGreen: Float? = null,
        val wbBlue: Float? = null,
        /** LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID, when reported. */
        val activePhysicalId: String? = null,
    )

    data class Result(
        val pass: Boolean,
        val reason: String,
        val frames: Int,
        val durationMs: Long,
        val medianGapMs: Long,
        val maxGapMs: Long,
        val exposureJump: Float,
        val wbJump: Float,
        val physicalIds: List<String>,
    )

    private const val MIN_FRAMES = 20
    private const val MIN_DURATION_MS = 900L
    private const val MAX_EXPOSURE_RATIO = 2.5f
    private const val MAX_WB_RATIO = 1.6f
    private const val GAP_ABSOLUTE_MS = 100L

    fun analyze(
        samples: List<Sample>,
        checkHandoffExposure: Boolean = true,
    ): Result {
        val ordered = samples
            .filter { it.timestampNs > 0L }
            .sortedBy { it.timestampNs }
        if (ordered.size < MIN_FRAMES) {
            return failure(ordered, "only ${ordered.size} frames captured")
        }
        val durationMs = (ordered.last().timestampNs - ordered.first().timestampNs) / 1_000_000
        if (durationMs < MIN_DURATION_MS) {
            return failure(ordered, "capture was only ${durationMs} ms")
        }

        val gaps = ordered.zipWithNext { a, b ->
            (b.timestampNs - a.timestampNs) / 1_000_000
        }
        val median = gaps.sorted().let { it[it.size / 2] }.coerceAtLeast(1L)
        val maxGap = gaps.maxOrNull() ?: 0L
        val gapLimit = maxOf(median * 3L, GAP_ABSOLUTE_MS)

        // A dual-session switch joins two different sensors/scenes, so a
        // boundary exposure or WB difference is expected. The logical-zoom
        // path hands off within one scene and must keep it bounded.
        val exposureJump = if (checkHandoffExposure) maxExposureJump(ordered) else 1f
        val wbJump = if (checkHandoffExposure) maxWbJump(ordered) else 1f
        val physicalIds = ordered.mapNotNull { it.activePhysicalId }
            .filter { it.isNotBlank() }
            .distinct()

        val reasons = mutableListOf<String>()
        if (maxGap > gapLimit) {
            reasons += "frame gap ${maxGap}ms (median ${median}ms)"
        }
        if (checkHandoffExposure && exposureJump > MAX_EXPOSURE_RATIO) {
            reasons += "exposure jump ${"%.2f".format(exposureJump)}×"
        }
        if (checkHandoffExposure && wbJump > MAX_WB_RATIO) {
            reasons += "WB jump ${"%.2f".format(wbJump)}×"
        }
        if (physicalIds.size < 2) {
            reasons += "active physical lens did not change (${physicalIds.joinToString(",")})"
        }
        return Result(
            pass = reasons.isEmpty(),
            reason = if (reasons.isEmpty()) {
                "frames=${ordered.size} duration=${durationMs}ms median=${median}ms " +
                    "max=${maxGap}ms exposure=${"%.2f".format(exposureJump)}× " +
                    "wb=${"%.2f".format(wbJump)}× physical=${physicalIds.joinToString(",")}"
            } else {
                reasons.joinToString("; ")
            },
            frames = ordered.size,
            durationMs = durationMs,
            medianGapMs = median,
            maxGapMs = maxGap,
            exposureJump = exposureJump,
            wbJump = wbJump,
            physicalIds = physicalIds,
        )
    }

    private fun maxExposureJump(samples: List<Sample>): Float {
        var max = 1f
        for ((a, b) in samples.zipWithNext()) {
            val ea = exposureValue(a) ?: continue
            val eb = exposureValue(b) ?: continue
            if (ea <= 0f || eb <= 0f) continue
            max = maxOf(max, ratio(ea, eb))
        }
        return max
    }

    private fun maxWbJump(samples: List<Sample>): Float {
        var max = 1f
        for ((a, b) in samples.zipWithNext()) {
            val ar = a.wbRed ?: continue
            val ag = a.wbGreen ?: continue
            val ab = a.wbBlue ?: continue
            val br = b.wbRed ?: continue
            val bg = b.wbGreen ?: continue
            val bb = b.wbBlue ?: continue
            if (ag <= 0f || bg <= 0f) continue
            max = maxOf(
                max,
                ratio(ar / ag, br / bg),
                ratio(ab / ag, bb / bg),
            )
        }
        return max
    }

    private fun exposureValue(sample: Sample): Float? {
        val time = sample.exposureNs ?: return null
        val iso = sample.iso ?: return null
        if (time <= 0L || iso <= 0) return null
        return time.toFloat() * iso
    }

    private fun ratio(a: Float, b: Float): Float {
        if (a <= 0f || b <= 0f) return 1f
        return maxOf(a / b, b / a)
    }

    private fun failure(samples: List<Sample>, reason: String) = Result(
        pass = false,
        reason = reason,
        frames = samples.size,
        durationMs = if (samples.size >= 2) {
            (samples.last().timestampNs - samples.first().timestampNs) / 1_000_000
        } else {
            0L
        },
        medianGapMs = 0L,
        maxGapMs = 0L,
        exposureJump = 1f,
        wbJump = 1f,
        physicalIds = emptyList(),
    )
}
