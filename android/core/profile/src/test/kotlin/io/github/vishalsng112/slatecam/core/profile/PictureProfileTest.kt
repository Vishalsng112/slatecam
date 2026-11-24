package io.github.vishalsng112.slatecam.core.profile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureProfileTest {

    @Test
    fun `standard profile is identity`() {
        val render = PictureProfile.STANDARD.render()
        assertEquals(0f, render.blackLevel, 0.0001f)
        assertEquals(1f, render.gammaPower, 0.0001f)
        assertEquals(1f, render.gammaContrast, 0.0001f)
        assertEquals(0f, render.gammaShoulder, 0.0001f)
        assertEquals(1f, render.kneePoint, 0.0001f)
        assertEquals(0f, render.blackGammaLevel, 0.0001f)
        assertEquals(1f, render.saturation, 0.0001f)
        assertFalse(render.colorOps)
        assertArrayEquals(
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            render.colorMatrix,
            0.0001f,
        )
    }

    @Test
    fun `slate look is an S-Cinetone-style curve`() {
        val profile = PictureProfile.SLATE_LOOK
        assertEquals(GammaPreset.SCINETONE, profile.settings.gamma)
        val render = profile.render()
        assertTrue(render.gammaShoulder > 0f)
        assertTrue(render.colorOps)
        assertTrue(render.kneePoint < 1f)
        assertEquals(0.02f, render.gammaToe, 0.0001f)
        assertEquals(-2, profile.settings.saturation)
    }

    @Test
    fun `custom settings are clamped`() {
        val settings = ProfileSettings(
            blackLevel = 99,
            blackGammaLevel = -99,
            kneeAutoMaxPoint = 200,
            kneePoint = -1,
            kneeSlope = 99,
            saturation = -200,
            colorPhase = 99,
            colorDepthR = 99,
            colorDepthG = -99,
        ).normalized()

        assertEquals(15, settings.blackLevel)
        assertEquals(-7, settings.blackGammaLevel)
        assertEquals(100, settings.kneeAutoMaxPoint)
        assertEquals(75, settings.kneePoint)
        assertEquals(5, settings.kneeSlope)
        assertEquals(-32, settings.saturation)
        assertEquals(7, settings.colorPhase)
        assertEquals(7, settings.colorDepthR)
        assertEquals(-7, settings.colorDepthG)
    }

    @Test
    fun `mono colour mode sets the monochrome flag`() {
        val profile = PictureProfile.custom(
            ProfileSettings(colorMode = ColorMode.MONO),
        )
        assertTrue(profile.monochrome)
        assertTrue(profile.render().monochrome)
        assertTrue(profile.render().colorOps)
    }

    @Test
    fun `saturation and phase map to renderer values`() {
        val render = PictureProfile.custom(
            ProfileSettings(saturation = 32, colorPhase = 7),
        ).render()
        assertEquals(2f, render.saturation, 0.0001f)
        assertEquals(0.08f, render.colorPhase, 0.0001f)
    }

    @Test
    fun `settable ranges map to the documented axis`() {
        val settings = ProfileSettings(saturation = -32)
        assertEquals(0f, PictureProfile.custom(settings).render().saturation, 0.0001f)
    }

    @Test
    fun `unknown wire values fall back safely`() {
        assertEquals(GammaPreset.STANDARD, GammaPreset.fromWire("nope"))
        assertEquals(ColorMode.BT709, ColorMode.fromWire("nope"))
        assertEquals(KneeMode.MANUAL, KneeMode.fromWire("nope"))
        assertEquals(BlackGammaRange.MIDDLE, BlackGammaRange.fromWire("nope"))
    }

    @Test
    fun `only three profiles are offered`() {
        assertEquals(
            listOf("standard", "slatelook", "custom"),
            PictureProfile.all.map { it.id },
        )
        assertEquals(
            ProfileKind.STANDARD,
            PictureProfile.byId("slatelog").kind,
        )
    }
}
