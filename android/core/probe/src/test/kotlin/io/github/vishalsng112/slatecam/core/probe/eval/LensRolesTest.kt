package io.github.vishalsng112.slatecam.core.probe.eval

import org.junit.Assert.assertEquals
import org.junit.Test

class LensRolesTest {

    @Test
    fun `single camera is wide`() {
        assertEquals(listOf("wide"), LensRoles.classify(listOf(65.0)))
    }

    @Test
    fun `two cameras are ultrawide and wide`() {
        assertEquals(
            listOf("ultrawide", "wide"),
            LensRoles.classify(listOf(80.0, 65.0)),
        )
    }

    @Test
    fun `three cameras get ultrawide wide tele`() {
        assertEquals(
            listOf("ultrawide", "wide", "tele"),
            LensRoles.classify(listOf(80.0, 65.0, 30.0)),
        )
    }

    @Test
    fun `roles follow the field of view, not the order`() {
        assertEquals(
            listOf("tele", "ultrawide", "wide"),
            LensRoles.classify(listOf(30.0, 80.0, 65.0)),
        )
    }

    @Test
    fun `missing field of view stays unclassified`() {
        assertEquals(
            listOf(null, "wide"),
            LensRoles.classify(listOf(null, 65.0)),
        )
    }

    @Test
    fun `all missing stays unclassified`() {
        assertEquals(listOf(null, null), LensRoles.classify(listOf(null, null)))
    }
}
