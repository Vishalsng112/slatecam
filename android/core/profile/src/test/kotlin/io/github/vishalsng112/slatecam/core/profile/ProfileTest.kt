package io.github.vishalsng112.slatecam.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlateLogTest {

    @Test
    fun `black maps to a lifted toe`() {
        assertEquals(0.09f, SlateLog.encode(0f), 0.01f)
    }

    @Test
    fun `18 percent grey maps to 0_40`() {
        assertEquals(0.40f, SlateLog.encode(0.18f), 0.01f)
    }

    @Test
    fun `100 percent white maps to about 0_75`() {
        assertEquals(0.754f, SlateLog.encode(1.0f), 0.01f)
    }

    @Test
    fun `encode and decode round trip within the representable range`() {
        for (value in listOf(0.05f, 0.1f, 0.18f, 0.5f, 1.0f, 2.5f)) {
            val roundTrip = SlateLog.decode(SlateLog.encode(value))
            assertEquals(value, roundTrip, value * 0.03f + 0.002f)
        }
    }

    @Test
    fun `highlights are clamped at white`() {
        assertEquals(1f, SlateLog.encode(10_000f), 0f)
    }
}

class PictureProfileTest {

    @Test
    fun `ids resolve and unknown falls back to standard`() {
        assertEquals(ProfileKind.SLATE_LOG, PictureProfile.byId("slatelog").kind)
        assertEquals(ProfileKind.MONO, PictureProfile.byId("mono").kind)
        assertEquals(ProfileKind.STANDARD, PictureProfile.byId("does-not-exist").kind)
    }

    @Test
    fun `log and mono flags follow the kind`() {
        assertTrue(PictureProfile.SLATE_LOG.logEnabled)
        assertTrue(PictureProfile.MONO.monochrome)
        assertEquals(0f, PictureProfile.MONO.saturation, 0f)
    }
}

class Lut3DTest {

    private val identity = """
        TITLE "identity"
        LUT_3D_SIZE 2
        0.0 0.0 0.0
        1.0 0.0 0.0
        0.0 1.0 0.0
        1.0 1.0 0.0
        0.0 0.0 1.0
        1.0 0.0 1.0
        0.0 1.0 1.0
        1.0 1.0 1.0
    """.trimIndent()

    @Test
    fun `parses a cube file`() {
        val lut = Lut3D.parse(identity)
        assertNotNull(lut)
        assertEquals(2, lut!!.size)
    }

    @Test
    fun `identity lut samples back to input`() {
        val lut = Lut3D.parse(identity)!!
        for (value in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val out = lut.sample(value, value, value)
            assertEquals(value, out[0], 0.001f)
            assertEquals(value, out[1], 0.001f)
            assertEquals(value, out[2], 0.001f)
        }
    }

    @Test
    fun `invalid cube files are rejected`() {
        assertNull(Lut3D.parse("LUT_3D_SIZE 2\n0 0 0"))
        assertNull(Lut3D.parse("LUT_1D_SIZE 2\n0 0 0"))
    }
}
