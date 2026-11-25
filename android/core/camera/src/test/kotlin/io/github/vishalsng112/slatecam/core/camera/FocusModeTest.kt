package io.github.vishalsng112.slatecam.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusModeTest {

    @Test
    fun `video prefers continuous video`() {
        assertEquals(
            FocusMode.CONTINUOUS_VIDEO,
            FocusMode.defaultFor(
                listOf("off", "auto", "continuous_picture", "continuous_video"),
            ),
        )
    }

    @Test
    fun `falls back to continuous picture then auto`() {
        assertEquals(
            FocusMode.CONTINUOUS_PICTURE,
            FocusMode.defaultFor(listOf("off", "auto", "continuous_picture")),
        )
        assertEquals(
            FocusMode.AUTO,
            FocusMode.defaultFor(listOf("off", "auto")),
        )
    }

    @Test
    fun `front camera with only off stays manual`() {
        assertEquals(FocusMode.OFF, FocusMode.defaultFor(listOf("off")))
    }

    @Test
    fun `software focus is never the hardware default`() {
        assertEquals(
            FocusMode.OFF,
            FocusMode.defaultFor(listOf("off", FocusMode.SOFTWARE)),
        )
        assertEquals(
            FocusMode.CONTINUOUS_VIDEO,
            FocusMode.defaultFor(listOf("off", "continuous_video", FocusMode.SOFTWARE)),
        )
    }

    @Test
    fun `software focus is recognized`() {
        assertTrue(FocusMode.isSoftware(FocusMode.SOFTWARE))
        assertFalse(FocusMode.isSoftware(FocusMode.CONTINUOUS_VIDEO))
    }

    @Test
    fun `manual is not automatic`() {
        assertFalse(FocusMode.isAutomatic(FocusMode.OFF))
        assertTrue(FocusMode.isAutomatic(FocusMode.CONTINUOUS_VIDEO))
        assertFalse(FocusMode.isAutomatic(FocusMode.SOFTWARE))
    }

    @Test
    fun `options include every reported HAL mode plus software`() {
        assertEquals(
            listOf(
                FocusMode.CONTINUOUS_VIDEO,
                FocusMode.CONTINUOUS_PICTURE,
                FocusMode.AUTO,
                FocusMode.MACRO,
                FocusMode.EDOF,
                FocusMode.OFF,
                FocusMode.SOFTWARE,
            ),
            FocusMode.options(
                listOf("edof", "off", "macro", "auto", "continuous_picture", "continuous_video"),
                hasSoftware = true,
            ),
        )
        assertEquals(
            listOf(FocusMode.CONTINUOUS_VIDEO, FocusMode.OFF),
            FocusMode.options(listOf("continuous_video", "off"), hasSoftware = false),
        )
    }

    @Test
    fun `HAL names round-trip`() {
        for (mode in FocusMode.options(
            listOf("continuous_video", "continuous_picture", "auto", "macro", "edof", "off"),
            hasSoftware = false,
        )) {
            val value = FocusMode.toHal(mode)
            assertTrue(value != null)
            assertEquals(mode, FocusMode.fromHal(value!!))
            assertTrue(FocusMode.isKnown(mode))
        }
        assertEquals(null, FocusMode.toHal(FocusMode.SOFTWARE))
        assertFalse(FocusMode.isKnown("mode_99"))
    }

    @Test
    fun `continuous covers both camera video modes`() {
        assertTrue(FocusMode.isContinuous(FocusMode.CONTINUOUS_VIDEO))
        assertTrue(FocusMode.isContinuous(FocusMode.CONTINUOUS_PICTURE))
        assertFalse(FocusMode.isContinuous(FocusMode.AUTO))
    }
}
