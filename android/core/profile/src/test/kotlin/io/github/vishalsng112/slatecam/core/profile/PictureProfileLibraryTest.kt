package io.github.vishalsng112.slatecam.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureProfileLibraryTest {

    @Test
    fun `add stores normalized settings and a fallback name`() {
        val library = PictureProfileLibrary()
        val created = library.add(
            id = "custom-1",
            name = "  ",
            settings = ProfileSettings(saturation = 99),
        )
        assertEquals("Custom", created.name)
        assertEquals(32, library.get("custom-1")?.settings?.saturation)
        assertEquals(1, library.all().size)
    }

    @Test
    fun `rename updates known profiles and rejects blank names`() {
        val library = PictureProfileLibrary()
        library.add("custom-1", "Look A", ProfileSettings.DEFAULTS)
        assertTrue(library.rename("custom-1", "Fresh Look"))
        assertEquals("Fresh Look", library.get("custom-1")?.name)
        assertFalse(library.rename("custom-1", "   "))
        assertEquals("Fresh Look", library.get("custom-1")?.name)
        assertFalse(library.rename("missing", "Nope"))
    }

    @Test
    fun `settings update and reset are isolated per profile`() {
        val library = PictureProfileLibrary()
        library.add("a", "A", ProfileSettings.DEFAULTS)
        library.add("b", "B", ProfileSettings.DEFAULTS)

        library.updateSettings("a", ProfileSettings(blackLevel = 7, saturation = -4))
        assertEquals(7, library.get("a")?.settings?.blackLevel)
        assertEquals(0, library.get("b")?.settings?.blackLevel)

        library.resetSettings("a")
        assertEquals(ProfileSettings.DEFAULTS, library.get("a")?.settings)
    }

    @Test
    fun `single parameter reset touches only that parameter`() {
        val library = PictureProfileLibrary()
        library.add(
            "a",
            "A",
            ProfileSettings(blackLevel = 5, saturation = 9, gamma = GammaPreset.CINE4),
        )
        library.resetParameter("a", "saturation")
        val settings = library.get("a")!!.settings
        assertEquals(5, settings.blackLevel)
        assertEquals(0, settings.saturation)
        assertEquals(GammaPreset.CINE4, settings.gamma)
    }

    @Test
    fun `out of range keys and ids are ignored`() {
        val library = PictureProfileLibrary()
        library.add("a", "A", ProfileSettings(blackLevel = 5))
        assertFalse(library.resetParameter("a", "notAParameter"))
        assertEquals(5, library.get("a")?.settings?.blackLevel)
        assertFalse(library.updateSettings("missing", ProfileSettings.DEFAULTS))
        assertFalse(library.resetParameter("missing", "blackLevel"))
        assertNull(library.get("missing"))
    }

    @Test
    fun `delete removes only the requested profile`() {
        val library = PictureProfileLibrary()
        library.add("a", "A", ProfileSettings.DEFAULTS)
        library.add("b", "B", ProfileSettings.DEFAULTS)
        assertTrue(library.delete("a"))
        assertFalse(library.delete("a"))
        assertEquals(listOf("b"), library.all().map { it.id })
    }

    @Test
    fun `restore replaces the library and normalizes restored values`() {
        val library = PictureProfileLibrary()
        library.add("old", "Old", ProfileSettings.DEFAULTS)
        library.restore(
            listOf(
                UserPictureProfile("a", " A ", ProfileSettings(saturation = 100)),
                UserPictureProfile("b", "", ProfileSettings.DEFAULTS),
            ),
        )
        assertNull(library.get("old"))
        assertEquals("A", library.get("a")?.name)
        assertEquals(32, library.get("a")?.settings?.saturation)
        assertEquals("Custom", library.get("b")?.name)
    }

    @Test
    fun `every editor key has a reset to the documented default`() {
        val changed = ProfileSettings(
            blackLevel = 9,
            gamma = GammaPreset.CINE1,
            blackGammaRange = BlackGammaRange.WIDE,
            blackGammaLevel = 4,
            kneeMode = KneeMode.AUTO,
            kneeAutoMaxPoint = 99,
            kneeAutoSensitivity = KneeSensitivity.LOW,
            kneePoint = 88,
            kneeSlope = 3,
            colorMode = ColorMode.MONO,
            saturation = 12,
            colorPhase = 4,
            colorDepthR = 4,
            colorDepthG = 3,
            colorDepthB = 2,
            colorDepthC = 1,
            colorDepthM = -1,
            colorDepthY = -2,
        )
        val keys = ProfileSettings.EDITOR_KEYS
        // Every editor key resets to identity when applied together.
        assertEquals(ProfileSettings.DEFAULTS, keys.fold(changed) { acc, key -> acc.reset(key) })
        // And each key is individually accepted by the library.
        keys.forEach { key ->
            assertEquals(ProfileSettings.DEFAULTS, ProfileSettings.DEFAULTS.reset(key))
        }
    }
}
