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

class LutLibraryTest {

    private val cube = """
        LUT_3D_SIZE 2
        0 0 0
        1 0 0
        0 1 0
        1 1 0
        0 0 1
        1 0 1
        0 1 1
        1 1 1
    """.trimIndent()

    @Test
    fun `scans cube files and ignores everything else`() {
        val directory = java.nio.file.Files.createTempDirectory("luts").toFile()
        try {
            java.io.File(directory, "look.cube").writeText(cube)
            java.io.File(directory, "notes.txt").writeText("not a lut")
            java.io.File(directory, "broken.cube").writeText("LUT_3D_SIZE 2\n0 0 0")

            val entries = LutLibrary.scan(directory)

            assertEquals(1, entries.size)
            assertEquals("lut:look", entries[0].id)
            assertEquals("look", entries[0].name)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `missing directory yields no entries`() {
        val directory = java.io.File("/tmp/slatecam-does-not-exist-12345")
        assertTrue(LutLibrary.scan(directory).isEmpty())
    }
}

class InputProfileTest {

    @Test
    fun `identity leaves colours alone`() {
        val out = InputProfile.IDENTITY.transform(0.2f, 0.4f, 0.6f)
        assertEquals(0.2f, out[0], 0.0001f)
        assertEquals(0.4f, out[1], 0.0001f)
        assertEquals(0.6f, out[2], 0.0001f)
        assertTrue(InputProfile.IDENTITY.isIdentity())
    }

    @Test
    fun `matrix swaps red and blue`() {
        val swap = InputProfile(
            matrix = listOf(
                0f, 0f, 1f,
                0f, 1f, 0f,
                1f, 0f, 0f,
            ),
        )
        val out = swap.transform(0.9f, 0.5f, 0.1f)
        assertEquals(0.1f, out[0], 0.0001f)
        assertEquals(0.5f, out[1], 0.0001f)
        assertEquals(0.9f, out[2], 0.0001f)
    }

    @Test
    fun `exposure and saturation trims apply`() {
        val profile = InputProfile(exposureTrim = 2f, saturationTrim = 0f)
        val out = profile.transform(1f, 0f, 0f)
        // fully desaturated to luma of the exposed colour
        assertEquals(0.2126f * 2f, out[0], 0.001f)
        assertEquals(0.2126f * 2f, out[1], 0.001f)
        assertEquals(0.2126f * 2f, out[2], 0.001f)
    }
}
