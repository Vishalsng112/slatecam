package io.github.vishalsng112.slatecam.core.probe.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRolesTest {

    private fun candidate(
        id: String,
        fov: Double?,
        facing: String = "back",
        score: Int = 0,
        fixedFocus: Boolean = false,
        pixelCount: Long = 0,
    ) = CameraRoles.Candidate(
        id = id,
        facing = facing,
        hfovDeg = fov,
        score = score,
        fixedFocus = fixedFocus,
        pixelCount = pixelCount,
    )

    @Test
    fun `single camera is default with low confidence`() {
        val roles = CameraRoles.classify(listOf(candidate("0", 65.0)))
        assertEquals("default", roles.getValue("0").role)
        assertEquals("only_camera", roles.getValue("0").basis)
        assertEquals("low", roles.getValue("0").confidence)
        assertEquals(1.0, roles.getValue("0").intrinsicZoomRatio!!, 0.001)
    }

    @Test
    fun `ultrawide and default are separated`() {
        val roles = CameraRoles.classify(
            listOf(candidate("0", 80.0), candidate("1", 65.0)),
        )
        assertEquals("ultrawide", roles.getValue("0").role)
        assertEquals("default", roles.getValue("1").role)
        assertTrue(roles.getValue("0").intrinsicZoomRatio!! < 1.0)
        assertEquals(1.0, roles.getValue("1").intrinsicZoomRatio!!, 0.001)
        assertEquals("fov_ratio", roles.getValue("0").basis)
    }

    @Test
    fun `three lenses get ultrawide default tele`() {
        val roles = CameraRoles.classify(
            listOf(candidate("0", 120.0), candidate("1", 78.0), candidate("2", 30.0)),
        )
        assertEquals("ultrawide", roles.getValue("0").role)
        assertEquals("default", roles.getValue("1").role)
        assertEquals("tele", roles.getValue("2").role)
        assertTrue(roles.getValue("2").intrinsicZoomRatio!! > 2.0)
    }

    @Test
    fun `a two lens main plus tele does not invent an ultrawide`() {
        val roles = CameraRoles.classify(
            listOf(candidate("0", 75.0), candidate("1", 45.0)),
        )
        assertEquals("default", roles.getValue("0").role)
        assertEquals("tele", roles.getValue("1").role)
    }

    @Test
    fun `without a default band roles fall back to absolute fov`() {
        val roles = CameraRoles.classify(
            listOf(candidate("0", 120.0), candidate("1", 40.0)),
        )
        assertEquals("ultrawide", roles.getValue("0").role)
        assertEquals("tele", roles.getValue("1").role)
        assertEquals("absolute_fov", roles.getValue("0").basis)
    }

    @Test
    fun `missing optics stay unknown`() {
        val roles = CameraRoles.classify(
            listOf(candidate("0", null), candidate("1", 65.0)),
        )
        assertEquals("unknown", roles.getValue("0").role)
        assertEquals("no_optical_data", roles.getValue("0").basis)
        assertEquals("default", roles.getValue("1").role)
        assertNull(roles.getValue("0").intrinsicZoomRatio)
    }

    @Test
    fun `facings are classified independently`() {
        val roles = CameraRoles.classify(
            listOf(
                candidate("0", 120.0, facing = "back"),
                candidate("1", 75.0, facing = "back"),
                candidate("2", 70.0, facing = "front"),
            ),
        )
        assertEquals("ultrawide", roles.getValue("0").role)
        assertEquals("default", roles.getValue("1").role)
        assertEquals("default", roles.getValue("2").role)
        assertEquals("only_camera", roles.getValue("2").basis)
    }

    @Test
    fun `capability score breaks ties between similar lenses`() {
        val roles = CameraRoles.classify(
            listOf(
                candidate("2", 66.0, score = 1),
                candidate("5", 74.0, score = 9),
            ),
        )
        // Both are equally close to the 70-degree target; the higher-capability
        // one becomes the reference.
        assertEquals(1.0, roles.getValue("5").intrinsicZoomRatio!!, 0.001)
        assertEquals("default", roles.getValue("5").role)
    }

    @Test
    fun `a fixed focus small sensor is labelled macro, not default`() {
        val roles = CameraRoles.classify(
            listOf(
                candidate("0", 78.0, score = 20, pixelCount = 16_000_000),
                candidate("2", 120.0, pixelCount = 8_000_000),
                candidate(
                    "3",
                    76.0,
                    fixedFocus = true,
                    pixelCount = 1_920_000,
                ),
            ),
        )
        assertEquals("default", roles.getValue("0").role)
        assertEquals("ultrawide", roles.getValue("2").role)
        assertEquals("macro", roles.getValue("3").role)
        assertEquals("fixed_focus_small_sensor", roles.getValue("3").basis)
    }

    @Test
    fun `thirty-five millimetre equivalent uses the sensor diagonal`() {
        val value = CameraRoles.focalLength35mmEquivalent(4.0, 7.0, 5.0)
        assertEquals(20.12, value!!, 0.05)
        assertNull(CameraRoles.focalLength35mmEquivalent(null, 7.0, 5.0))
        assertNull(CameraRoles.focalLength35mmEquivalent(4.0, 0.0, 5.0))
    }

    @Test
    fun `horizontal fov comes from focal length and sensor width`() {
        val fov = CameraRoles.horizontalFovDegrees(4.0, 7.0)
        assertEquals(82.4, fov!!, 0.2)
        assertNull(CameraRoles.horizontalFovDegrees(0.0, 7.0))
    }
}
