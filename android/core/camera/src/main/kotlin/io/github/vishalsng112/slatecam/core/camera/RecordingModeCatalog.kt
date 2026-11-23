package io.github.vishalsng112.slatecam.core.camera

/**
 * A recording mode that the UI can offer. A mode is valid only when the camera
 * actually exposes the size, can frame-lock the rate, and (for 10-bit) the probe
 * verified the same size and rate.
 */
data class ShootMode(
    val id: String,
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val tenBit: Boolean,
    val enabled: Boolean,
    val reason: String,
)

/**
 * Builds recording modes from live camera capabilities and probe verification.
 *
 * Two callers matter:
 * - single-camera mode: modes of the active camera, full capability surface;
 * - movie/vlog mode: the intersection across a set of cameras, so one recording
 *   configuration can survive a lens switch.
 */
object RecordingModeCatalog {

    private data class Canonical(val width: Int, val height: Int, val aspect: String)

    private val canonicalSizes = listOf(
        Canonical(3840, 2160, "16:9"),
        Canonical(1920, 1080, "16:9"),
        Canonical(1440, 1080, "4:3"),
        Canonical(1280, 720, "16:9"),
    )

    private val frameRates = listOf(24, 25, 30)

    /** Null when 10-bit is verified for this size/rate; otherwise the reason. */
    typealias TenBitVerifier = (width: Int, height: Int, fps: Int) -> String?

    fun modesFor(
        capabilities: CameraCapabilities,
        tenBitVerifier: TenBitVerifier,
    ): List<ShootMode> = evaluate(
        cameras = listOf(capabilities to tenBitVerifier),
    )

    /** Only modes enabled for every camera in the set. */
    fun commonModes(
        cameras: List<Pair<CameraCapabilities, TenBitVerifier>>,
    ): List<ShootMode> {
        if (cameras.isEmpty()) return emptyList()
        return evaluate(cameras)
    }

    private fun evaluate(
        cameras: List<Pair<CameraCapabilities, TenBitVerifier>>,
    ): List<ShootMode> {
        val modes = mutableListOf<ShootMode>()

        canonicalSizes.forEach { size ->
            frameRates.forEach { fps ->
                listOf(true, false).forEach { tenBit ->
                    val reasons = cameras.mapNotNull { (caps, verifier) ->
                        reasonFor(caps, verifier, size, fps, tenBit)?.let {
                            "${caps.cameraId}: $it"
                        }
                    }
                    val reason = when {
                        reasons.isEmpty() -> ""
                        cameras.size == 1 -> reasons.first().substringAfter(": ")
                        else -> reasons.joinToString("; ")
                    }
                    modes += ShootMode(
                        id = id(size.width, size.height, fps, tenBit),
                        label = "${size.width}×${size.height} · ${fps}p · " +
                            "${size.aspect} · ${if (tenBit) "10-bit HLG" else "8-bit SDR"}",
                        width = size.width,
                        height = size.height,
                        fps = fps,
                        tenBit = tenBit,
                        enabled = reasons.isEmpty(),
                        reason = reason,
                    )
                }
            }
        }

        return modes.sortedWith(
            compareByDescending<ShootMode> { it.enabled }
                .thenByDescending { it.width * it.height }
                .thenBy { it.fps }
                .thenByDescending { it.tenBit },
        )
    }

    private fun reasonFor(
        caps: CameraCapabilities,
        verifier: TenBitVerifier,
        size: Canonical,
        fps: Int,
        tenBit: Boolean,
    ): String? {
        if (!caps.supports(size.width, size.height)) {
            return "camera does not offer ${size.width}×${size.height}"
        }
        if (!caps.supportsExactFrameRate(fps)) {
            return "$fps fps cannot be frame-locked"
        }
        if (tenBit) {
            if (!caps.tenBitClaimed) return "camera does not advertise 10-bit"
            verifier(size.width, size.height, fps)?.let { return it }
        }
        return null
    }

    fun id(width: Int, height: Int, fps: Int, tenBit: Boolean): String =
        "${width}x${height}@${fps}/${if (tenBit) 10 else 8}"
}
