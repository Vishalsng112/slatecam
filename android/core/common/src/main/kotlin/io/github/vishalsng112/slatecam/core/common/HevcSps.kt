package io.github.vishalsng112.slatecam.core.common

/**
 * Minimal HEVC bitstream inspection.
 *
 * The point is trust: `MediaCodec.outputFormat` can claim Main10 while the
 * encoder emits an 8-bit Main stream (observed on MediaTek MT6897). Reading
 * `general_profile_idc` from the SPS is the only way to know what was actually
 * encoded.
 */
object HevcSps {

    const val PROFILE_MAIN = 1
    const val PROFILE_MAIN10 = 2

    private const val NAL_TYPE_SPS = 33

    /**
     * Returns `general_profile_idc` from the first SPS in [data], or null when
     * no SPS is present. Accepts Annex B and 4-byte length-prefixed streams.
     */
    fun profileIdc(data: ByteArray): Int? {
        for (nal in nalUnits(data)) {
            if (nal.size < 4) continue
            val type = (nal[0].toInt() shr 1) and 0x3F
            if (type != NAL_TYPE_SPS) continue
            // nal[0..1] header, nal[2] vps_id/max_sub_layers/nesting,
            // nal[3] = profile_space(2) | tier_flag(1) | profile_idc(5)
            return nal[3].toInt() and 0x1F
        }
        return null
    }

    fun isMain10(data: ByteArray): Boolean = profileIdc(data) == PROFILE_MAIN10

    private fun nalUnits(data: ByteArray): List<ByteArray> {
        val firstStart = findStartCode(data, 0)
        return if (firstStart >= 0) splitAnnexB(data, firstStart) else splitLengthPrefixed(data)
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
}
