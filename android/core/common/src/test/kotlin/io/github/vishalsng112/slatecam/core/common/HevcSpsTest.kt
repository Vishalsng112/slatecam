package io.github.vishalsng112.slatecam.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcSpsTest {

    /** Builds a minimal SPS NAL: header type 33, then profile_idc. */
    private fun spsNal(profileIdc: Int): ByteArray = byteArrayOf(
        0x42, // forbidden(0) + nal_unit_type 33 (0100 0010 >> 1 = 33)
        0x01,
        0x01, // vps_id / max_sub_layers / nesting
        profileIdc.toByte(), // profile_space | tier | profile_idc
        0x00,
    )

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        for (nal in nals) {
            out += listOf<Byte>(0, 0, 0, 1)
            out += nal.toList()
        }
        return out.toByteArray()
    }

    @Test
    fun `annex b main10 sps reports profile 2`() {
        assertEquals(2, HevcSps.profileIdc(annexB(spsNal(2))))
        assertTrue(HevcSps.isMain10(annexB(spsNal(2))))
    }

    @Test
    fun `annex b main sps reports profile 1`() {
        assertEquals(1, HevcSps.profileIdc(annexB(spsNal(1))))
        assertFalse(HevcSps.isMain10(annexB(spsNal(1))))
    }

    @Test
    fun `length prefixed stream is parsed`() {
        val nal = spsNal(2)
        val length = nal.size
        val prefix = byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        )
        assertEquals(2, HevcSps.profileIdc(prefix + nal))
    }

    @Test
    fun `sps is found after other nal units`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C, 0x01) // nal type 32
        val pps = byteArrayOf(0x44, 0x01, 0xC0.toByte()) // nal type 34
        assertEquals(2, HevcSps.profileIdc(annexB(vps, spsNal(2), pps)))
    }

    @Test
    fun `no sps returns null`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C, 0x01)
        assertNull(HevcSps.profileIdc(annexB(vps)))
        assertNull(HevcSps.profileIdc(byteArrayOf()))
    }
}
