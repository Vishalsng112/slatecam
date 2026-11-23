package io.github.vishalsng112.slatecam.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcSpsTest {

    @Test
    fun `annex b main10 sps reports profile and bit depth`() {
        val info = HevcSps.parse(annexB(spsNal(profileIdc = 2, bitDepthLuma = 10)))
        assertNotNull(info)
        assertEquals(2, info!!.profileIdc)
        assertEquals(10, info.bitDepthLuma)
        assertEquals(10, info.bitDepthChroma)
        assertEquals(1, info.chromaFormatIdc)
        assertTrue(info.isMain10)
        assertTrue(info.isTenBit)
        assertFalse(info.fromConfigurationRecord)
    }

    @Test
    fun `annex b main sps reports eight bit`() {
        val info = HevcSps.parse(annexB(spsNal(profileIdc = 1, bitDepthLuma = 8)))!!
        assertEquals(1, info.profileIdc)
        assertEquals(8, info.bitDepthLuma)
        assertFalse(info.isMain10)
        assertFalse(info.isTenBit)
    }

    @Test
    fun `length prefixed stream is parsed`() {
        val nal = spsNal(profileIdc = 2, bitDepthLuma = 10)
        val length = nal.size
        val prefix = byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        )
        val info = HevcSps.parse(prefix + nal)
        assertEquals(2, info?.profileIdc)
        assertEquals(10, info?.bitDepthLuma)
    }

    @Test
    fun `hvcC configuration record is parsed`() {
        // This is the shape MediaCodec returns in csd-0. It has no start codes
        // and uses 2-byte NAL lengths inside a type array, which the old parser
        // could not read at all (profile_idc came back null on a real clip).
        val record = hvcC(profileIdc = 2, bitDepthLuma = 10, nalType = 33)

        val info = HevcSps.parse(record)
        assertNotNull(info)
        assertEquals(2, info!!.profileIdc)
        assertEquals(10, info.bitDepthLuma)
        assertEquals(1, info.chromaFormatIdc)
        assertTrue(info.isMain10)
    }

    @Test
    fun `hvcC header is used when the sps is unreadable`() {
        val record = hvcC(
            profileIdc = 2,
            bitDepthLuma = 10,
            nalType = 33,
            sps = byteArrayOf(0x42, 0x01, 0x00), // truncated SPS
        )

        val info = HevcSps.parse(record)
        assertNotNull(info)
        assertEquals(2, info!!.profileIdc)
        assertEquals(10, info.bitDepthLuma)
        assertTrue(info.fromConfigurationRecord)
    }

    @Test
    fun `sps is found after other nal units`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C, 0x01) // nal type 32
        val pps = byteArrayOf(0x44, 0x01, 0xC0.toByte()) // nal type 34
        val info = HevcSps.parse(
            annexB(vps, spsNal(profileIdc = 2, bitDepthLuma = 10), pps),
        )
        assertEquals(2, info?.profileIdc)
    }

    @Test
    fun `no sps returns null`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C, 0x01)
        assertNull(HevcSps.parse(annexB(vps)))
        assertNull(HevcSps.parse(byteArrayOf()))
        assertNull(HevcSps.profileIdc(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `hvcC built from first-sample nals parses back`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C, 0x01)
        val sps = spsNal(profileIdc = 2, bitDepthLuma = 10)
        val pps = byteArrayOf(0x44, 0x01, 0xC0.toByte())
        val record = HevcSps.buildHvcC(vps, sps, pps)
        assertNotNull(record)

        val info = HevcSps.parse(record!!)
        assertNotNull(info)
        assertEquals(2, info!!.profileIdc)
        assertEquals(10, info.bitDepthLuma)
        assertFalse(info.fromConfigurationRecord)
    }

    @Test
    fun `nal units split a length prefixed access unit`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C, 0x01)
        val sps = spsNal(profileIdc = 2, bitDepthLuma = 10)
        val payload = lengthPrefixed(vps) + lengthPrefixed(sps)
        val units = HevcSps.nalUnits(payload)
        assertEquals(2, units.size)
        assertEquals(32, (units[0][0].toInt() shr 1) and 0x3F)
        assertEquals(33, (units[1][0].toInt() shr 1) and 0x3F)
    }

    private fun lengthPrefixed(nal: ByteArray): ByteArray {
        val length = nal.size
        return byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        ) + nal
    }

    /** Builds a syntactically valid SPS through `bit_depth_chroma_minus8`. */
    private fun spsNal(profileIdc: Int, bitDepthLuma: Int): ByteArray {
        val bits = BitWriter()
        bits.u(0, 4) // sps_video_parameter_set_id
        bits.u(0, 3) // sps_max_sub_layers_minus1
        bits.u(1, 1) // sps_temporal_id_nesting_flag
        bits.u(0, 2) // general_profile_space
        bits.u(0, 1) // general_tier_flag
        bits.u(profileIdc, 5)
        bits.u(0, 32) // general_profile_compatibility_flag
        bits.u(0, 1) // progressive
        bits.u(0, 1) // interlaced
        bits.u(0, 1) // non_packed
        bits.u(1, 1) // frame_only
        bits.u(0, 44) // constraint flags
        bits.u(120, 8) // general_level_idc
        bits.ue(0) // sps_seq_parameter_set_id
        bits.ue(1) // chroma_format_idc
        bits.ue(1920) // pic_width_in_luma_samples
        bits.ue(1080) // pic_height_in_luma_samples
        bits.u(0, 1) // conformance_window_flag
        bits.ue(bitDepthLuma - 8)
        bits.ue(bitDepthLuma - 8)
        bits.u(1, 1) // rbsp_stop_one_bit
        val rbsp = bits.toByteArray()
        return byteArrayOf(0x42, 0x01) + rbsp
    }

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        for (nal in nals) {
            out += listOf<Byte>(0, 0, 0, 1)
            out += nal.toList()
        }
        return out.toByteArray()
    }

    /** Minimal hvcC with one array of [nalType] carrying [sps]. */
    private fun hvcC(
        profileIdc: Int,
        bitDepthLuma: Int,
        nalType: Int,
        sps: ByteArray = spsNal(profileIdc, bitDepthLuma),
    ): ByteArray {
        val header = mutableListOf<Byte>()
        header += 1 // configurationVersion
        header += profileIdc.toByte()
        repeat(4) { header += 0 } // compatibility flags
        repeat(6) { header += 0 } // constraint flags
        header += 120.toByte() // level_idc
        header += 0xF0.toByte()
        header += 0x00
        header += 0xFC.toByte() // parallelismType
        header += (0xFC or 1).toByte() // chromaFormat = 4:2:0
        header += (0xF8 or (bitDepthLuma - 8)).toByte()
        header += (0xF8 or (bitDepthLuma - 8)).toByte()
        header += 0
        header += 0
        header += 0x03 // lengthSizeMinusOne = 3
        header += 1 // numOfArrays
        header += (0x80 or nalType).toByte() // array_completeness | NAL type
        header += 0
        header += 1 // numNalus
        header += ((sps.size ushr 8) and 0xFF).toByte()
        header += (sps.size and 0xFF).toByte()
        header += sps.toList()
        return header.toByteArray()
    }

    private class BitWriter {
        private val bits = mutableListOf<Int>()

        fun u(value: Int, count: Int) {
            for (index in count - 1 downTo 0) {
                bits += (value ushr index) and 1
            }
        }

        fun ue(value: Int) {
            val code = value + 1
            val length = 32 - Integer.numberOfLeadingZeros(code)
            repeat(length - 1) { bits += 0 }
            u(code, length)
        }

        fun toByteArray(): ByteArray {
            while (bits.size % 8 != 0) bits += 0
            val out = ByteArray(bits.size / 8)
            bits.forEachIndexed { index, bit ->
                if (bit == 1) {
                    out[index / 8] = (out[index / 8].toInt() or (1 shl (7 - index % 8))).toByte()
                }
            }
            return out
        }
    }
}
