package io.github.vishalsng112.slatecam.core.camera

/** Resolution family offered by the recording-mode catalog. */
enum class RecordingResolution(val label: String, val minHeight: Int) {
    UHD_4K("4K", 2160),
    FHD_1080("1080", 1080),
    HD_720("720", 720),
    ;

    companion object {
        fun forHeight(height: Int): RecordingResolution = when {
            height >= UHD_4K.minHeight -> UHD_4K
            height >= FHD_1080.minHeight -> FHD_1080
            else -> HD_720
        }
    }
}

/** Sensor aspect family; portrait presentation is a UI concern. */
enum class RecordingAspect(val label: String) {
    WIDE_16_9("16:9"),
    OPEN_4_3("4:3"),
    ;

    companion object {
        fun forSize(width: Int, height: Int): RecordingAspect =
            if (width * 9 == height * 16) WIDE_16_9 else OPEN_4_3
    }
}

enum class RecordingBitDepth(val bits: Int) {
    EIGHT(8),
    TEN(10),
}

/** How the encoder spends the bitrate target. */
enum class BitrateControl(val wireValue: String) {
    /** System variable bitrate: quality scales with the scene, target as a guide. */
    VBR("vbr"),

    /** Constant bitrate: fixed target, predictable file size for takes. */
    CBR("cbr"),
    ;

    companion object {
        fun fromWire(value: String): BitrateControl =
            entries.firstOrNull { it.wireValue == value } ?: VBR
    }
}

/** Bitrate policy in one place so every consumer sees the same number. */
object RecordingBitrate {
    /**
     * Fixed CBR targets for vlog and movie takes. 20–40 Mbps covers 1080p
     * vlogging, 60–100 Mbps covers 4K movie work with headroom for grain.
     */
    val cbrPresets: List<Int> = listOf(20, 40, 60, 80, 100).map { it * 1_000_000 }

    fun forPixels(width: Int, height: Int, fps: Int): Int {
        val pixels = width.toLong() * height
        val base = when {
            pixels >= 3840L * 2160 -> 40_000_000
            pixels >= 1920L * 1080 -> 12_000_000
            else -> 9_000_000
        }
        return (base * fps.coerceAtLeast(1) / 30).coerceAtLeast(4_000_000)
    }

    /** Snap a request to a supported preset; unknown values fall back to 20 Mbps. */
    fun cbrTarget(requestedBps: Int): Int =
        cbrPresets.firstOrNull { it == requestedBps } ?: cbrPresets.first()

    /** Target handed to the encoder for the next take. */
    fun effectiveBps(control: BitrateControl, cbrBps: Int, modeBps: Int): Int =
        if (control == BitrateControl.CBR) cbrBps else modeBps
}

/**
 * The single recording-mode model.
 *
 * The operator-facing elements are [resolution], [aspect], [fps], [bitrate]
 * and [bitDepth]. [width]/[height] are the concrete Camera2 stream size derived
 * from the resolution and aspect; they stay for the session and encoder but no
 * caller needs to infer the family or the aspect from them.
 */
data class RecordingMode(
    val id: String,
    val resolution: RecordingResolution,
    val aspect: RecordingAspect,
    val fps: Int,
    val bitrate: Int,
    val bitDepth: RecordingBitDepth,
    val width: Int,
    val height: Int,
    val enabled: Boolean,
    val reason: String,
) {
    val label: String
        get() = "$width×$height · ${fps}p · ${aspect.label} · " +
            if (bitDepth == RecordingBitDepth.TEN) "10-bit HLG" else "8-bit SDR"
}

/**
 * Builds recording modes from live camera capabilities and probe verification.
 *
 * Two callers matter:
 * - single-camera mode: modes of the active camera, full capability surface;
 * - movie/vlog mode: the intersection across a set of cameras, so one recording
 *   configuration can survive a lens switch.
 */
object RecordingModeCatalog {

    private data class Canonical(
        val width: Int,
        val height: Int,
        val resolution: RecordingResolution,
        val aspect: RecordingAspect,
    )

    private val canonicalSizes = listOf(
        3840 to 2160,
        4096 to 3072,
        3840 to 2880,
        2880 to 2160,
        1920 to 1080,
        1920 to 1440,
        1440 to 1080,
        1280 to 720,
    ).map { (width, height) ->
        Canonical(
            width = width,
            height = height,
            resolution = RecordingResolution.forHeight(height),
            aspect = RecordingAspect.forSize(width, height),
        )
    }

    private val frameRates = listOf(24, 25, 30)

    /** Null when 10-bit is verified for this size/rate; otherwise the reason. */
    typealias TenBitVerifier = (width: Int, height: Int, fps: Int) -> String?

    fun modesFor(
        capabilities: CameraCapabilities,
        tenBitVerifier: TenBitVerifier,
    ): List<RecordingMode> = evaluate(
        cameras = listOf(capabilities to tenBitVerifier),
    )

    /** Only modes enabled for every camera in the set. */
    fun commonModes(
        cameras: List<Pair<CameraCapabilities, TenBitVerifier>>,
    ): List<RecordingMode> {
        if (cameras.isEmpty()) return emptyList()
        return evaluate(cameras)
    }

    private fun evaluate(
        cameras: List<Pair<CameraCapabilities, TenBitVerifier>>,
    ): List<RecordingMode> {
        val modes = mutableListOf<RecordingMode>()

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
                    val bitDepth =
                        if (tenBit) RecordingBitDepth.TEN else RecordingBitDepth.EIGHT
                    modes += RecordingMode(
                        id = id(size.width, size.height, fps, tenBit),
                        resolution = size.resolution,
                        aspect = size.aspect,
                        fps = fps,
                        bitrate = RecordingBitrate.forPixels(size.width, size.height, fps),
                        bitDepth = bitDepth,
                        width = size.width,
                        height = size.height,
                        enabled = reasons.isEmpty(),
                        reason = reason,
                    )
                }
            }
        }

        return modes.sortedWith(
            compareByDescending<RecordingMode> { it.enabled }
                .thenByDescending { it.width * it.height }
                .thenBy { it.fps }
                .thenByDescending { it.bitDepth.bits },
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
