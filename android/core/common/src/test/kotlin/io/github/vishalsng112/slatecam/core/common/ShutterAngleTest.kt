package io.github.vishalsng112.slatecam.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ShutterAngleTest {

    @Test
    fun `180 degrees at 30fps is one sixtieth`() {
        assertEquals(16_666_666L, ShutterAngle.exposureNs(180f, 30))
    }

    @Test
    fun `360 degrees is one frame`() {
        assertEquals(33_333_333L, ShutterAngle.exposureNs(360f, 30))
    }

    @Test
    fun `90 degrees at 24fps is one ninety-sixth`() {
        // (90/360)/24 = 1/96 s
        assertEquals(10_416_666L, ShutterAngle.exposureNs(90f, 24))
    }

    @Test
    fun `degrees round trips`() {
        val ns = ShutterAngle.exposureNs(172.8f, 25)
        assertEquals(172.8f, ShutterAngle.degrees(ns, 25), 0.2f)
    }

    @Test
    fun `angle is clamped`() {
        assertEquals(0L, ShutterAngle.exposureNs(-10f, 30))
        assertEquals(
            ShutterAngle.exposureNs(360f, 30),
            ShutterAngle.exposureNs(720f, 30),
        )
    }
}
