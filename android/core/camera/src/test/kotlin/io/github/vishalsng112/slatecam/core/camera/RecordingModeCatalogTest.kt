package io.github.vishalsng112.slatecam.core.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingModeCatalogTest {

    private fun caps(
        id: String,
        sizes: List<Pair<Int, Int>>,
        exact: Set<Int> = setOf(24, 25, 30),
        tenBit: Boolean = true,
    ) = CameraCapabilities(
        cameraId = id,
        facing = "back",
        hardwareLevel = "level_3",
        sizes = sizes.map { CameraSize(it.first, it.second) },
        exactFrameRates = exact,
        tenBitClaimed = tenBit,
        afModes = listOf("auto", "continuous_video"),
        zoomRange = 1f..10f,
    )

    private val verifiedAll: RecordingModeCatalog.TenBitVerifier = { _, _, _ -> null }

    @Test
    fun `mode requires size and exact frame rate`() {
        val modes = RecordingModeCatalog.modesFor(
            caps("0", listOf(1920 to 1080), exact = setOf(30)),
            verifiedAll,
        )
        val fullHd30 = modes.first { it.id == "1920x1080@30/8" }
        val fullHd24 = modes.first { it.id == "1920x1080@24/8" }
        assertTrue(fullHd30.enabled)
        assertFalse(fullHd24.enabled)
        assertTrue(fullHd24.reason.contains("24 fps"))
    }

    @Test
    fun `unsupported aspect is disabled with a reason`() {
        val modes = RecordingModeCatalog.modesFor(
            caps("0", listOf(1920 to 1080)),
            verifiedAll,
        )
        val fourThree = modes.first { it.id == "1440x1080@30/8" }
        assertFalse(fourThree.enabled)
        assertTrue(fourThree.reason.contains("1440×1080"))
    }

    @Test
    fun `four three 4K sizes stay in the 4K family`() {
        val modes = RecordingModeCatalog.modesFor(
            caps("0", listOf(4096 to 3072, 2880 to 2160)),
            verifiedAll,
        )
        val dci = modes.first { it.id == "4096x3072@30/8" }
        val phone4k = modes.first { it.id == "2880x2160@30/8" }
        assertTrue(dci.enabled)
        assertTrue(phone4k.enabled)
        assertTrue(dci.label.contains("4:3"))
        assertTrue(phone4k.label.contains("4:3"))
    }

    @Test
    fun `ten bit needs the probe verifier`() {
        val modes = RecordingModeCatalog.modesFor(
            caps("0", listOf(1920 to 1080)),
        ) { _, _, _ -> "not verified (probe: not_run)" }

        val tenBit = modes.first { it.id == "1920x1080@30/10" }
        assertFalse(tenBit.enabled)
        assertTrue(tenBit.reason.contains("not verified"))
    }

    @Test
    fun `common modes are the intersection across cameras`() {
        val back = caps("0", listOf(1920 to 1080), exact = setOf(24, 30))
        val aux = caps("2", listOf(1920 to 1080), exact = setOf(24), tenBit = false)
        val modes = RecordingModeCatalog.commonModes(
            listOf(back to verifiedAll, aux to verifiedAll),
        )

        val hd24 = modes.first { it.id == "1920x1080@24/8" }
        val hd30 = modes.first { it.id == "1920x1080@30/8" }
        val hd24ten = modes.first { it.id == "1920x1080@24/10" }
        assertTrue(hd24.enabled)
        assertFalse(hd30.enabled)
        assertTrue(hd30.reason.contains("2: 30 fps"))
        assertFalse(hd24ten.enabled)
        assertTrue(hd24ten.reason.contains("does not advertise 10-bit"))
    }
}
