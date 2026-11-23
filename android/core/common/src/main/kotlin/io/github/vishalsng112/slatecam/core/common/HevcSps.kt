package io.github.vishalsng112.slatecam.core.common

/**
 * HEVC bitstream inspection.
 *
 * The point is trust: `MediaCodec.outputFormat` can claim Main10 while the
 * encoder emits an 8-bit Main stream (observed on MediaTek MT6897), and it can
 * claim a bit depth the SPS does not agree with. Only the encoded bytes settle
 * what was actually produced.
 *
 * Three containers are understood:
 * - a raw Annex B stream (`00 00 01` start codes),
 * - a 4-byte length-prefixed stream,
 * - an `hvcC` configuration record, which is what MediaCodec puts in `csd-0`
 *   and what the MP4 sample entry carries.
 */
object HevcSps {

    const val PROFILE_MAIN = 1
    const val PROFILE_MAIN10 = 2

    private const val NAL_TYPE_SPS = 33

    /** Decoded facts from an SPS or, when the SPS is unusable, the hvcC header. */
    data class SpsInfo(
        val profileIdc: Int,
        val tierFlag: Boolean,
        val bitDepthLuma: Int,
        val bitDepthChroma: Int,
        val chromaFormatIdc: Int,
        /** True when the numbers came from the hvcC header, not the SPS RBSP. */
        val fromConfigurationRecord: Boolean,
    ) {
        val isMain10: Boolean get() = profileIdc == PROFILE_MAIN10
        val isTenBit: Boolean get() = bitDepthLuma >= 10
    }

    /**
     * Returns `general_profile_idc` from the first SPS in [data], or null when
     * no SPS is present. Accepts Annex B, length-prefixed and hvcC input.
     */
    fun profileIdc(data: ByteArray): Int? = parse(data)?.profileIdc

    fun isMain10(data: ByteArray): Boolean = parse(data)?.isMain10 == true

    /** NAL units from an Annex B or length-prefixed access unit or config record. */
    fun nalUnits(data: ByteArray): List<ByteArray> {
        val firstStart = findStartCode(data, 0)
        return if (firstStart >= 0) splitAnnexB(data, firstStart) else splitLengthPrefixed(data)
    }

    /**
     * Builds an MP4 `hvcC` record from VPS/SPS/PPS NAL units. Some encoders emit
     * their config only with the first access unit instead of in
     * `outputFormat`'s `csd-0`; the muxer still needs a sample entry.
     *
     * NAL lengths in the record are 4 bytes, matching the length-prefixed
     * access units MediaCodec produces on Android.
     */
    fun buildHvcC(
        vps: ByteArray?,
        sps: ByteArray,
        pps: ByteArray?,
    ): ByteArray? {
        val info = parseSps(sps) ?: return null
        if (sps.size < 15) return null

        val nals = listOfNotNull(vps, sps, pps)
        val out = java.io.ByteArrayOutputStream()
        out.write(1) // configurationVersion
        out.write(sps[3].toInt() and 0xFF) // profile_space | tier | profile_idc
        out.write(sps, 4, 4) // general_profile_compatibility_flags
        out.write(sps, 8, 6) // general_constraint_indicator_flags
        out.write(sps[14].toInt() and 0xFF) // general_level_idc
        out.write(0xF0) // reserved | min_spatial_segmentation_idc
        out.write(0x00)
        out.write(0xFC) // reserved | parallelismType
        out.write(0xFC or (info.chromaFormatIdc and 0x03))
        out.write(0xF8 or ((info.bitDepthLuma - 8) and 0x07))
        out.write(0xF8 or ((info.bitDepthChroma - 8) and 0x07))
        out.write(0) // avgFrameRate
        out.write(0)
        out.write(0x03) // constantFrameRate | numTemporalLayers | lengthSizeMinusOne
        out.write(nals.size)
        nals.forEach { nal ->
            val type = ((nal[0].toInt() shr 1) and 0x3F)
            out.write(0x80 or type) // array_completeness | NAL_unit_type
            out.write(0)
            out.write(1) // numNalus
            out.write((nal.size ushr 8) and 0xFF)
            out.write(nal.size and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }

    /**
     * Parses the first SPS found in [data]. Prefers the SPS RBSP, because it
     * carries bit depth as well as profile; falls back to the hvcC header when
     * the record is present but the SPS cannot be decoded.
     */
    fun parse(data: ByteArray): SpsInfo? {
        val configuration = configurationRecord(data)
        if (configuration != null) {
            val fromSps = configuration.sps?.let(::parseSps)
            if (fromSps != null) return fromSps
            return SpsInfo(
                profileIdc = configuration.profileIdc,
                tierFlag = configuration.tierFlag,
                bitDepthLuma = configuration.bitDepthLuma,
                bitDepthChroma = configuration.bitDepthChroma,
                chromaFormatIdc = configuration.chromaFormatIdc,
                fromConfigurationRecord = true,
            )
        }

        for (nal in nalUnits(data)) {
            if (nalType(nal) != NAL_TYPE_SPS) continue
            parseSps(nal)?.let { return it }
        }
        return null
    }

    private data class ConfigurationRecord(
        val profileIdc: Int,
        val tierFlag: Boolean,
        val bitDepthLuma: Int,
        val bitDepthChroma: Int,
        val chromaFormatIdc: Int,
        val sps: ByteArray?,
    )

    private fun nalType(nal: ByteArray): Int {
        if (nal.size < 2) return -1
        return (nal[0].toInt() shr 1) and 0x3F
    }

    /**
     * Parses an hvcC record. Offsets follow ISO/IEC 14496-15 section 8.3.3.1.1.
     * A record shorter than the fixed header is not an hvcC.
     */
    private fun configurationRecord(data: ByteArray): ConfigurationRecord? {
        if (data.size < 23 || data[0].toInt() != 1) return null
        val numArrays = data[22].toInt() and 0xFF
        if (numArrays == 0 || numArrays > 32) return null

        val profile = data[1].toInt()
        var offset = 23
        var sps: ByteArray? = null
        repeat(numArrays) {
            if (offset + 3 > data.size) return ConfigurationRecord(
                profileIdc = profile and 0x1F,
                tierFlag = (profile and 0x20) != 0,
                bitDepthLuma = 8 + (data[17].toInt() and 0x07),
                bitDepthChroma = 8 + (data[18].toInt() and 0x07),
                chromaFormatIdc = data[16].toInt() and 0x03,
                sps = sps,
            )
            val type = data[offset].toInt() and 0x3F
            val count = ((data[offset + 1].toInt() and 0xFF) shl 8) or
                (data[offset + 2].toInt() and 0xFF)
            offset += 3
            repeat(count) {
                if (offset + 2 > data.size) return@repeat
                val length = ((data[offset].toInt() and 0xFF) shl 8) or
                    (data[offset + 1].toInt() and 0xFF)
                offset += 2
                if (offset + length > data.size || length <= 0) return@repeat
                if (type == NAL_TYPE_SPS && sps == null) {
                    sps = data.copyOfRange(offset, offset + length)
                }
                offset += length
            }
        }

        return ConfigurationRecord(
            profileIdc = profile and 0x1F,
            tierFlag = (profile and 0x20) != 0,
            bitDepthLuma = 8 + (data[17].toInt() and 0x07),
            bitDepthChroma = 8 + (data[18].toInt() and 0x07),
            chromaFormatIdc = data[16].toInt() and 0x03,
            sps = sps,
        )
    }

    /**
     * Parses the SPS up to `bit_depth_chroma_minus8`. Everything after that
     * point (reference-picture sets, scaling lists, VUI) is irrelevant to the
     * profile/bit-depth verdict, so the parser stops there instead of trying to
     * reproduce the whole syntax.
     */
    private fun parseSps(nal: ByteArray): SpsInfo? {
        if (nal.size < 4 || nalType(nal) != NAL_TYPE_SPS) return null
        val rbsp = removeEmulationPrevention(nal, start = 2)
        val reader = BitReader(rbsp)

        return runCatching {
            reader.skip(4) // sps_video_parameter_set_id
            val maxSubLayersMinus1 = reader.bits(3)
            reader.skip(1) // sps_temporal_id_nesting_flag

            val profileSpace = reader.bits(2)
            val tierFlag = reader.bits(1) == 1
            val profileIdc = reader.bits(5)
            reader.skip(32) // general_profile_compatibility_flag
            reader.skip(1) // general_progressive_source_flag
            reader.skip(1) // general_interlaced_source_flag
            reader.skip(1) // general_non_packed_constraint_flag
            reader.skip(1) // general_frame_only_constraint_flag
            reader.skip(44) // reserved constraint flags
            reader.skip(8) // general_level_idc

            if (maxSubLayersMinus1 > 0) {
                val profilePresent = IntArray(maxSubLayersMinus1)
                val levelPresent = IntArray(maxSubLayersMinus1)
                repeat(maxSubLayersMinus1) { index ->
                    profilePresent[index] = reader.bits(1)
                    levelPresent[index] = reader.bits(1)
                }
                repeat(8 - maxSubLayersMinus1) { reader.skip(2) }
                repeat(maxSubLayersMinus1) { index ->
                    if (profilePresent[index] == 1) {
                        reader.skip(2 + 1 + 5 + 32 + 4 + 44)
                    }
                    if (levelPresent[index] == 1) reader.skip(8)
                }
            }

            reader.skipUe() // sps_seq_parameter_set_id
            val chromaFormatIdc = reader.ue()
            if (chromaFormatIdc == 3) reader.skip(1) // separate_colour_plane_flag
            reader.skipUe() // pic_width_in_luma_samples
            reader.skipUe() // pic_height_in_luma_samples
            if (reader.bits(1) == 1) {
                repeat(4) { reader.skipUe() } // conformance window
            }
            val bitDepthLuma = 8 + reader.ue()
            val bitDepthChroma = 8 + reader.ue()

            SpsInfo(
                profileIdc = profileIdc,
                tierFlag = tierFlag,
                bitDepthLuma = bitDepthLuma,
                bitDepthChroma = bitDepthChroma,
                chromaFormatIdc = chromaFormatIdc,
                fromConfigurationRecord = false,
            ).also {
                // profile_space is not part of the verdict; read it to keep the
                // syntax honest if the parser is extended later.
                check(profileSpace in 0..3)
            }
        }.getOrNull()
    }

    private fun removeEmulationPrevention(nal: ByteArray, start: Int): ByteArray {
        val out = ByteArray(nal.size - start)
        var written = 0
        var zeros = 0
        var index = start
        while (index < nal.size) {
            val value = nal[index].toInt() and 0xFF
            if (zeros >= 2 && value == 0x03) {
                zeros = 0
                index++
                continue
            }
            out[written++] = nal[index]
            zeros = if (value == 0) zeros + 1 else 0
            index++
        }
        return out.copyOf(written)
    }

    private fun findStartCode(data: ByteArray, from: Int): Int {
        var i = from
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 &&
                data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 1
            ) {
                return i
            }
            i++
        }
        return -1
    }

    private fun splitAnnexB(data: ByteArray, firstStart: Int): List<ByteArray> {
        val units = mutableListOf<ByteArray>()
        var position = firstStart
        while (position >= 0) {
            val start = position + 3
            val next = findStartCode(data, start)
            val end = if (next >= 0) next else data.size
            if (end > start) units += data.copyOfRange(start, end)
            position = next
        }
        return units
    }

    private fun splitLengthPrefixed(data: ByteArray): List<ByteArray> {
        val units = mutableListOf<ByteArray>()
        var offset = 0
        while (offset + 4 <= data.size) {
            val length = ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
            if (length <= 0 || offset + 4 + length > data.size) break
            units += data.copyOfRange(offset + 4, offset + 4 + length)
            offset += 4 + length
        }
        return units
    }

    private class BitReader(private val data: ByteArray) {
        private var bit = 0

        fun bits(count: Int): Int {
            var value = 0
            repeat(count) {
                val byteIndex = bit ushr 3
                val bitIndex = 7 - (bit and 7)
                val set = if (byteIndex < data.size) {
                    (data[byteIndex].toInt() shr bitIndex) and 1
                } else {
                    0
                }
                value = (value shl 1) or set
                bit++
            }
            return value
        }

        fun skip(count: Int) {
            bit += count
        }

        fun ue(): Int {
            var leadingZeros = 0
            while (bits(1) == 0) {
                leadingZeros++
                if (leadingZeros > 31) return 0
            }
            if (leadingZeros == 0) return 0
            return (1 shl leadingZeros) - 1 + bits(leadingZeros)
        }

        fun skipUe() {
            ue()
        }
    }
}
