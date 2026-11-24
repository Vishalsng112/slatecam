package io.github.vishalsng112.slatecam.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlickerGridTest {

    @Test
    fun fiftyHertzBlocksUseTheHundredHertzPeriod() {
        assertEquals(10_000_000L, FlickerGrid.stepNs("50hz", "IN")!!)
        // 1/60 s is not flicker-free under 50 Hz; it snaps to 1/50 s.
        assertEquals(
            20_000_000L,
            FlickerGrid.snap(16_666_667L, FlickerGrid.stepNs("50hz", "IN")!!, 1_000L, 1_000_000_000L),
        )
        // 1/200 s is half a flicker period and therefore still bands.
        assertEquals(
            10_000_000L,
            FlickerGrid.snap(5_000_000L, FlickerGrid.stepNs("50hz", "IN")!!, 1_000L, 1_000_000_000L),
        )
    }

    @Test
    fun sixtyHertzBlocksUseTheHundredTwentyHertzPeriod() {
        assertEquals(8_333_333L, FlickerGrid.stepNs("60hz", "US")!!)
        // The classic 180-degree 30 fps exposure is exactly two 60 Hz cycles.
        assertEquals(
            16_666_666L,
            FlickerGrid.snap(16_666_667L, FlickerGrid.stepNs("60hz", "US")!!, 1_000L, 1_000_000_000L),
        )
    }

    @Test
    fun autoUsesTheRegionMainsFrequency() {
        assertEquals(10_000_000L, FlickerGrid.stepNs("auto", "IN")!!)
        assertEquals(10_000_000L, FlickerGrid.stepNs("auto", "")!!)
        assertEquals(10_000_000L, FlickerGrid.stepNs("auto", null)!!)
        assertEquals(8_333_333L, FlickerGrid.stepNs("auto", "US")!!)
        assertEquals(8_333_333L, FlickerGrid.stepNs("auto", "us")!!)
    }

    @Test
    fun offLeavesTheShutterContinuous() {
        assertNull(FlickerGrid.stepNs("off", "IN"))
        assertEquals(
            12_345_678L,
            FlickerGrid.snap(12_345_678L, FlickerGrid.stepNs("off", "IN"), 1_000L, 1_000_000_000L),
        )
    }

    @Test
    fun snapStaysInsideTheSensorRange() {
        val step = FlickerGrid.stepNs("50hz", "IN")!!
        // The smallest multiple at or above the sensor minimum is used.
        assertEquals(10_000_000L, FlickerGrid.snap(1_000L, step, 1_000L, 33_000_000L))
        // The largest multiple below the ceiling is used.
        assertEquals(30_000_000L, FlickerGrid.snap(33_000_000L, step, 1_000L, 33_000_000L))
        // A range shorter than one step cannot be quantised.
        assertEquals(5_000_000L, FlickerGrid.snap(5_000_000L, step, 1_000L, 8_000_000L))
    }

    @Test
    fun nearestMultipleTiesRoundAwayFromZero() {
        val step = FlickerGrid.stepNs("50hz", "IN")!!
        assertEquals(20_000_000L, FlickerGrid.snap(15_000_000L, step, 1_000L, 1_000_000_000L))
        assertEquals(10_000_000L, FlickerGrid.snap(14_999_999L, step, 1_000L, 1_000_000_000L))
    }
}
