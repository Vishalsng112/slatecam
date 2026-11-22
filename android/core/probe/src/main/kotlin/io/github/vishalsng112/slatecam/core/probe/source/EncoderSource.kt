package io.github.vishalsng112.slatecam.core.probe.source

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import io.github.vishalsng112.slatecam.core.probe.model.EncoderInfo
import io.github.vishalsng112.slatecam.core.probe.model.EncoderProfile

class EncoderSource {

    fun read(warnings: MutableList<String>): List<EncoderInfo> {
        val codecInfos = try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        } catch (t: Throwable) {
            warnings += "MediaCodecList failed: ${t.message}"
            return emptyList()
        }

        return codecInfos.filter { it.isEncoder }.flatMap { info ->
            VIDEO_MIMES.mapNotNull { mime ->
                if (mime !in info.supportedTypes) return@mapNotNull null
                try {
                    buildEncoder(info, mime)
                } catch (t: Throwable) {
                    warnings += "encoder ${info.name} ($mime) failed: ${t.message}"
                    null
                }
            }
        }
    }

    private fun buildEncoder(info: MediaCodecInfo, mime: String): EncoderInfo {
        val caps: MediaCodecInfo.CodecCapabilities = info.getCapabilitiesForType(mime)
        val profiles = caps.profileLevels.map { level ->
            EncoderProfile(
                profile = profileName(mime, level.profile),
                level = levelName(mime, level.level),
            )
        }
        val main10 = profiles.any { it.profile.startsWith("main10") }
        return EncoderInfo(
            mime = mime,
            name = info.name,
            hardware = info.isHardwareAccelerated,
            softwareOnly = info.isSoftwareOnly,
            vendor = info.isVendor,
            surfaceInput = caps.colorFormats.contains(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            ),
            profiles = profiles.distinct().sortedBy { it.profile },
            main10Claimed = main10,
            main10Verified = "not_run",
            maxInstances = caps.maxSupportedInstances,
        )
    }

    private fun profileName(mime: String, profile: Int): String = when (mime) {
        "video/hevc" -> when (profile) {
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "main"
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "main10"
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> "main10_hdr10"
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "main10_hdr10plus"
            else -> "profile_$profile"
        }
        "video/avc" -> when (profile) {
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "baseline"
            MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline -> "constrained_baseline"
            MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "main"
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "high"
            MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh -> "constrained_high"
            else -> "profile_$profile"
        }
        else -> "profile_$profile"
    }

    private fun levelName(mime: String, level: Int): String = when (mime) {
        "video/hevc" -> HEVC_LEVELS[level] ?: "level_$level"
        "video/avc" -> AVC_LEVELS[level] ?: "level_$level"
        else -> "level_$level"
    }

    private companion object {
        val VIDEO_MIMES = listOf("video/hevc", "video/avc")

        val HEVC_LEVELS: Map<Int, String> = mapOf(
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1 to "1",
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel2 to "2",
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21 to "2.1",
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3 to "3",
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31 to "3.1",
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4 to "4",
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41 to "4.1",
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5 to "5",
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51 to "5.1",
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52 to "5.2",
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6 to "6",
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61 to "6.1",
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62 to "6.2",
        )

        val AVC_LEVELS: Map<Int, String> = mapOf(
            MediaCodecInfo.CodecProfileLevel.AVCLevel1 to "1",
            MediaCodecInfo.CodecProfileLevel.AVCLevel1b to "1b",
            MediaCodecInfo.CodecProfileLevel.AVCLevel11 to "1.1",
            MediaCodecInfo.CodecProfileLevel.AVCLevel12 to "1.2",
            MediaCodecInfo.CodecProfileLevel.AVCLevel13 to "1.3",
            MediaCodecInfo.CodecProfileLevel.AVCLevel2 to "2",
            MediaCodecInfo.CodecProfileLevel.AVCLevel21 to "2.1",
            MediaCodecInfo.CodecProfileLevel.AVCLevel22 to "2.2",
            MediaCodecInfo.CodecProfileLevel.AVCLevel3 to "3",
            MediaCodecInfo.CodecProfileLevel.AVCLevel31 to "3.1",
            MediaCodecInfo.CodecProfileLevel.AVCLevel32 to "3.2",
            MediaCodecInfo.CodecProfileLevel.AVCLevel4 to "4",
            MediaCodecInfo.CodecProfileLevel.AVCLevel41 to "4.1",
            MediaCodecInfo.CodecProfileLevel.AVCLevel42 to "4.2",
            MediaCodecInfo.CodecProfileLevel.AVCLevel5 to "5",
            MediaCodecInfo.CodecProfileLevel.AVCLevel51 to "5.1",
            MediaCodecInfo.CodecProfileLevel.AVCLevel52 to "5.2",
            MediaCodecInfo.CodecProfileLevel.AVCLevel6 to "6",
            MediaCodecInfo.CodecProfileLevel.AVCLevel61 to "6.1",
        )
    }
}
