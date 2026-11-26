package io.github.vishalsng112.slatecam.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LensSetTest {

    @Test
    fun `a set needs at least two lenses`() {
        assertEquals(
            LensSetMode.SINGLE,
            LensSet.resolve(LensSetMode.SET, listOf("0")),
        )
        assertEquals(
            LensSetMode.SET,
            LensSet.resolve(LensSetMode.SET, listOf("0", "1")),
        )
        assertEquals(
            LensSetMode.SET,
            LensSetMode.fromWire("movie"),
        )
        assertEquals(LensSetMode.SET, LensSetMode.fromWire("set"))
    }

    @Test
    fun `deselect keeps a valid set`() {
        val set = LensSet(LensSetMode.SET, listOf("0", "1"))
        assertFalse(set.canDeselect("0"))
        assertTrue(set.canDeselect("2"))
        assertEquals(set, set.toggle("0", false))
        assertTrue(LensSet(LensSetMode.SINGLE, listOf("0")).canDeselect("0"))
    }

    @Test
    fun `toggle adds a lens only once`() {
        val set = LensSet(LensSetMode.SET, listOf("0", "1"))
        assertEquals(listOf("0", "1", "2"), set.toggle("2", true).selectedIds)
        assertEquals(set, set.toggle("0", true))
    }
}
