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
    fun `manual is not automatic`() {
        assertFalse(FocusMode.isAutomatic(FocusMode.OFF))
        assertTrue(FocusMode.isAutomatic(FocusMode.CONTINUOUS_VIDEO))
    }
}
