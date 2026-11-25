package io.github.vishalsng112.slatecam.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusControllerTest {

    @Test
    fun `continuous video is the default when the lens reports it`() {
        val focus = FocusController()
        focus.bind(
            halModes = listOf("off", "auto", "continuous_picture", "continuous_video"),
            minimumFocusDistance = 5f,
        )

        assertEquals(FocusMode.CONTINUOUS_VIDEO, focus.mode)
        assertEquals(FocusMode.CONTINUOUS_VIDEO, focus.preferredMode)
    }

    @Test
    fun `software autofocus is offered last and never chosen implicitly`() {
        val focus = FocusController()
        focus.bind(
            halModes = listOf("continuous_video", "off"),
            minimumFocusDistance = 4.5f,
        )

        assertEquals(
            listOf(FocusMode.CONTINUOUS_VIDEO, FocusMode.OFF, FocusMode.SOFTWARE),
            focus.availableModes,
        )
        assertEquals(FocusMode.CONTINUOUS_VIDEO, focus.mode)
        assertTrue(focus.autoFocus)
    }

    @Test
    fun `a lens without a focus range hides software autofocus`() {
        val focus = FocusController()
        focus.bind(halModes = listOf("off", "auto"), minimumFocusDistance = 0f)

        assertFalse(FocusMode.SOFTWARE in focus.availableModes)
        assertEquals(FocusMode.AUTO, focus.mode)
    }

    @Test
    fun `preferred mode follows the operator across lenses`() {
        val focus = FocusController()
        focus.bind(listOf("off", "continuous_video"), 5f)
        assertTrue(focus.selectMode(FocusMode.SOFTWARE, 0L))

        // A fixed-focus lens cannot run SAF, but the choice is remembered.
        focus.bind(listOf("off"), 0f)
        assertEquals(FocusMode.OFF, focus.mode)
        assertEquals(FocusMode.SOFTWARE, focus.preferredMode)

        focus.bind(listOf("off", "continuous_video"), 5f)
        assertEquals(FocusMode.SOFTWARE, focus.mode)
    }

    @Test
    fun `manual focus selects the off mode and ramps to the target`() {
        val focus = FocusController()
        focus.bind(listOf("off", "continuous_video"), 5f)

        assertTrue(focus.selectManual(4f, 0L))
        assertEquals(FocusMode.OFF, focus.mode)
        assertEquals(FocusMode.OFF, focus.preferredMode)
        assertEquals(4f, focus.targetDiopters, 0.001f)
        assertTrue(focus.isRamping)

        var now = 0L
        while (focus.advanceRamp(0.033f, now)) {
            now += 33L
        }
        assertEquals(4f, focus.diopters, 0.001f)
        assertEquals(now, focus.settleUntilMs - 120L)
    }

    @Test
    fun `a scenario applies its preset through the software mode`() {
        val focus = FocusController()
        focus.bind(listOf("off", "continuous_video"), 5f)

        assertTrue(focus.selectScenario(FocusScenario.CINEMATIC.wireValue, 0L))
        assertEquals(FocusMode.SOFTWARE, focus.mode)
        assertEquals(FocusMode.SOFTWARE, focus.preferredMode)
        assertEquals(FocusScenario.CINEMATIC, focus.scenario)
        assertEquals(0.20f, focus.shiftSensitivity, 0.001f)
        assertEquals(0.15f, focus.transitionSpeed, 0.001f)
        assertEquals(0.5f + 11.5f * 0.15f, focus.shiftSpeed, 0.001f)
    }

    @Test
    fun `tapped focus is routed by the active mode`() {
        val focus = FocusController()
        focus.bind(listOf("off", "continuous_video"), 5f)

        assertEquals(FocusController.PointFocus.HARDWARE, focus.onPointFocus(0L))
        assertTrue(focus.selectMode(FocusMode.SOFTWARE, 0L))
        assertEquals(FocusController.PointFocus.SOFTWARE, focus.onPointFocus(1L))
        assertTrue(focus.selectMode(FocusMode.OFF, 0L))
        assertEquals(FocusController.PointFocus.IGNORE, focus.onPointFocus(2L))
    }

    @Test
    fun `unsupported mode switches are rejected`() {
        val focus = FocusController()
        focus.bind(listOf("off"), 0f)

        assertFalse(focus.selectMode(FocusMode.CONTINUOUS_VIDEO, 0L))
        assertEquals(FocusMode.OFF, focus.mode)
        assertFalse(focus.selectScenario(FocusScenario.WEDDING.wireValue, 0L))
        assertNull(FocusMode.toHal(FocusMode.SOFTWARE))
    }
}
