package io.github.vishalsng112.slatecam.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDatabaseTest {

    private val poco = DeviceIdentity(
        deviceKey = "xiaomi/duchamp",
        fingerprint = "POCO/duchamp_in/duchamp:16/BP2A.250605.031.A3/OS3.0.302.0.WNLINXM:user/release-keys",
        sdk = 36,
        manufacturer = "Xiaomi",
        brand = "POCO",
        model = "2311DRK48I",
    )

    private fun quirk(
        id: String = "force-8bit",
        deviceKey: String = "xiaomi/duchamp",
        cameraId: String? = null,
        fingerprintPrefix: String? = "POCO/duchamp_in/duchamp:",
        minSdk: Int = 33,
        maxSdk: Int? = null,
        action: String = QuirkActions.FORCE_8BIT,
    ) = QuirkEntry(
        id = id,
        match = QuirkMatch(
            deviceKey = deviceKey,
            cameraId = cameraId,
            fingerprintPrefix = fingerprintPrefix,
            minSdk = minSdk,
            maxSdk = maxSdk,
        ),
        issue = "ten_bit_claim",
        action = action,
        reason = "test",
    )

    private fun calibration(
        deviceKey: String = "xiaomi/duchamp",
        cameraId: String = "0",
        version: Int = 1,
        exposureTrim: Float = 1f,
        lensKey: String? = null,
        wbR: Float = 1f,
        wbB: Float = 1f,
        shadowLift: Float = 0f,
    ) = CalibrationEntry(
        deviceKey = deviceKey,
        cameraId = cameraId,
        lensKey = lensKey,
        version = version,
        exposureTrim = exposureTrim,
        wbROffset = wbR,
        wbBOffset = wbB,
        shadowLift = shadowLift,
        source = "ColorChecker test",
    )

    @Test
    fun `quirk matches device key fingerprint sdk and camera`() {
        val database = DeviceDatabase(
            quirks = listOf(
                quirk(),
                quirk(id = "camera-1", cameraId = "1"),
                quirk(id = "wrong-sdk", minSdk = 40),
                quirk(id = "wrong-prefix", fingerprintPrefix = "Google/"),
            ),
        )

        val general = database.quirksFor(poco).map { it.id }
        assertEquals(listOf("force-8bit"), general)

        val cameraOne = database.quirksFor(poco, cameraId = "1").map { it.id }
        assertEquals(listOf("force-8bit", "camera-1"), cameraOne)

        val cameraZero = database.quirksFor(poco, cameraId = "0").map { it.id }
        assertEquals(listOf("force-8bit"), cameraZero)
    }

    @Test
    fun `quirk does not match another device or higher sdk`() {
        val database = DeviceDatabase(quirks = listOf(quirk(maxSdk = 35)))
        assertTrue(database.quirksFor(poco).isEmpty())
        assertTrue(database.quirksFor(poco.copy(sdk = 34)).isNotEmpty())
        assertTrue(database.quirksFor(poco.copy(deviceKey = "oneplus/op6190l1")).isEmpty())
    }

    @Test
    fun `unknown actions remain data and do not fail validation`() {
        val database = DeviceDatabase(quirks = listOf(quirk(action = "future_action")))
        assertTrue(database.validate().isEmpty())
        assertEquals("future_action", database.quirksFor(poco).single().action)
    }

    @Test
    fun `exact camera calibration wins over wildcard and newer version wins`() {
        val database = DeviceDatabase(
            calibrations = listOf(
                calibration(cameraId = "*", version = 9, exposureTrim = 0.8f),
                calibration(cameraId = "0", version = 1, exposureTrim = 1.1f),
                calibration(cameraId = "0", version = 2, exposureTrim = 1.2f),
            ),
        )

        val exact = database.calibrationFor(poco, "0")
        assertEquals(2, exact?.version)
        assertEquals(1.2f, exact?.exposureTrim)

        val wildcard = database.calibrationFor(poco, "1")
        assertEquals(DeviceDatabase.WILDCARD_CAMERA, wildcard?.cameraId)

        assertNull(database.calibrationFor(poco.copy(deviceKey = "other/device"), "0"))
    }

    @Test
    fun `lens key calibration wins over the camera id`() {
        val database = DeviceDatabase(
            calibrations = listOf(
                calibration(cameraId = "0", version = 5, exposureTrim = 1.5f),
                calibration(
                    cameraId = "0",
                    lensKey = "back:default:25",
                    version = 1,
                    exposureTrim = 1.1f,
                ),
            ),
        )
        val byLens = database.calibrationFor(poco, "0", "back:default:25")
        assertEquals(1.1f, byLens?.exposureTrim)
        val byCamera = database.calibrationFor(poco, "0")
        assertEquals(1.5f, byCamera?.exposureTrim)
    }

    @Test
    fun `per-lens white balance and shadow lift map to the input profile`() {
        val profile = calibration(
            lensKey = "back:default:25",
            wbR = 1.05f,
            wbB = 0.95f,
            shadowLift = 0.01f,
        ).toInputProfile()
        assertEquals(1.05f, profile.wbROffset, 0.0001f)
        assertEquals(0.95f, profile.wbBOffset, 0.0001f)
        assertEquals(0.01f, profile.shadowLift, 0.0001f)
    }

    @Test
    fun `validation rejects malformed lens keys and offsets`() {
        val database = DeviceDatabase(
            calibrations = listOf(
                calibration(lensKey = "not-a-lens-key", wbR = 0f, shadowLift = 2f),
            ),
        )
        val problems = database.validate()
        assertTrue(problems.any { it.contains("lens_key") })
        assertTrue(problems.any { it.contains("wb_r_offset") })
        assertTrue(problems.any { it.contains("shadow_lift") })
    }

    @Test
    fun `calibration maps to an input profile`() {
        val profile = calibration(exposureTrim = 0.9f).toInputProfile()
        assertEquals("xiaomi/duchamp", profile.deviceKey)
        assertEquals("0", profile.cameraId)
        assertEquals(0.9f, profile.exposureTrim)
        assertEquals(InputProfileIdentity.matrix, profile.matrix)
    }

    @Test
    fun `merge keeps bundled data and replaces matching override ids`() {
        val bundled = DeviceDatabase(
            quirks = listOf(quirk(id = "a"), quirk(id = "b")),
            calibrations = listOf(calibration(cameraId = "0", version = 1)),
        )
        val override = DeviceDatabase(
            version = 2,
            quirks = listOf(quirk(id = "b", action = QuirkActions.NOTE)),
            calibrations = listOf(calibration(cameraId = "0", version = 2)),
        )

        val merged = bundled.mergedWith(override)
        assertEquals(2, merged.version)
        assertEquals(setOf("a", "b"), merged.quirks.map { it.id }.toSet())
        assertEquals(
            QuirkActions.NOTE,
            merged.quirks.single { it.id == "b" }.action,
        )
        assertEquals(2, merged.calibrations.single().version)
    }

    @Test
    fun `json round trip ignores unknown fields`() {
        val json = """
            {
              "schema": "slatecam.device-db",
              "version": 3,
              "future_field": true,
              "quirks": [
                {
                  "id": "note",
                  "match": { "device_key": "xiaomi/duchamp", "min_sdk": 33 },
                  "issue": "note",
                  "action": "note",
                  "reason": "future-compatible",
                  "unknown_quirk_field": 1
                }
              ],
              "calibrations": [],
              "unknown_section": {}
            }
        """.trimIndent()

        val parsed = DeviceDatabase.fromJson(json)
        assertEquals(3, parsed.version)
        assertEquals(1, parsed.quirks.size)
        assertEquals("note", parsed.quirks.single().action)
        assertTrue(parsed.validate().isEmpty())
    }

    @Test
    fun `validation rejects duplicate ids and malformed calibration`() {
        val database = DeviceDatabase(
            quirks = listOf(quirk(id = "dup"), quirk(id = "dup")),
            calibrations = listOf(
                calibration(cameraId = "0"),
                calibration(cameraId = "0"),
                calibration(cameraId = "1").copy(matrix = listOf(1f)),
            ),
        )
        val problems = database.validate()
        assertTrue(problems.any { it.contains("duplicate quirk") })
        assertTrue(problems.any { it.contains("duplicate calibration") })
        assertTrue(problems.any { it.contains("3x3") })
    }

    @Test
    fun `snapshot exposes force-eight-bit and calibration source`() {
        val snapshot = DeviceDatabaseSnapshot(
            identity = poco,
            databaseVersion = 1,
            quirks = listOf(quirk()),
            calibration = calibration().copy(source = "ColorChecker 2026-11-23"),
            reportOptIn = false,
            lastReportPath = null,
            lastReportAtMs = null,
        )
        assertTrue(snapshot.forceEightBit)
        assertEquals("ColorChecker 2026-11-23", snapshot.calibrationSource)
        assertFalse(snapshot.reportOptIn)
    }

    private object InputProfileIdentity {
        val matrix = listOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
        )
    }
}
