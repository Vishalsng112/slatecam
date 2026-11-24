package io.github.vishalsng112.slatecam.core.device

import io.github.vishalsng112.slatecam.core.profile.InputProfile
import io.github.vishalsng112.slatecam.core.probe.model.ProbeReport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val DEVICE_DATABASE_SCHEMA = "slatecam.device-db"

/**
 * Device data is data, not code. Quirks and colour calibration ship in a
 * versioned JSON database so a new device can be supported without a rebuild
 * of the camera pipeline.
 */
@Serializable
data class DeviceDatabase(
    val schema: String = DEVICE_DATABASE_SCHEMA,
    val version: Int = 1,
    @SerialName("generated_at") val generatedAt: String? = null,
    val quirks: List<QuirkEntry> = emptyList(),
    val calibrations: List<CalibrationEntry> = emptyList(),
) {

    fun quirksFor(identity: DeviceIdentity, cameraId: String? = null): List<QuirkEntry> =
        quirks.filter { it.match.matches(identity, cameraId) }

    /**
     * Exact camera calibration wins, then a wildcard entry. Newer versions win
     * when a database carries more than one entry for the same camera.
     */
    fun calibrationFor(
        identity: DeviceIdentity,
        cameraId: String? = null,
        lensKey: String? = null,
    ): CalibrationEntry? {
        val forDevice = calibrations.filter {
            it.deviceKey.equals(identity.deviceKey, ignoreCase = true)
        }
        val exactLens = if (lensKey != null) {
            forDevice
                .filter { it.lensKey.equals(lensKey, ignoreCase = true) }
                .maxByOrNull { it.version }
        } else {
            null
        }
        val exactCamera = forDevice
            .filter {
                cameraId != null &&
                    it.cameraId == cameraId &&
                    (it.lensKey.isNullOrBlank() || lensKey == null)
            }
            .maxByOrNull { it.version }
        val wildcard = forDevice
            .filter { it.cameraId == WILDCARD_CAMERA && it.lensKey.isNullOrBlank() }
            .maxByOrNull { it.version }
        return exactLens ?: exactCamera ?: wildcard
    }

    /**
     * User-supplied overrides win by identity: quirk id, or device/camera pair.
     * Everything else from the bundled database is retained.
     */
    fun mergedWith(override: DeviceDatabase): DeviceDatabase {
        val mergedQuirks = LinkedHashMap<String, QuirkEntry>()
        quirks.forEach { mergedQuirks[it.id] = it }
        override.quirks.forEach { mergedQuirks[it.id] = it }

        val mergedCalibrations = LinkedHashMap<String, CalibrationEntry>()
        calibrations.forEach { mergedCalibrations[it.key] = it }
        override.calibrations.forEach { mergedCalibrations[it.key] = it }

        return copy(
            version = maxOf(version, override.version),
            generatedAt = override.generatedAt ?: generatedAt,
            quirks = mergedQuirks.values.toList(),
            calibrations = mergedCalibrations.values.toList(),
        )
    }

    /** Returns human-readable problems; an empty list means the data is usable. */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (schema != DEVICE_DATABASE_SCHEMA) problems += "unknown schema: $schema"
        if (version <= 0) problems += "version must be positive"

        val quirkIds = mutableSetOf<String>()
        quirks.forEachIndexed { index, quirk ->
            if (quirk.id.isBlank()) problems += "quirks[$index].id is blank"
            if (!quirkIds.add(quirk.id)) problems += "duplicate quirk id: ${quirk.id}"
            if (quirk.match.deviceKey.isBlank()) {
                problems += "quirks[$index].match.device_key is blank"
            }
            if (quirk.match.minSdk < 0) problems += "quirks[$index].match.min_sdk is negative"
            val maxSdk = quirk.match.maxSdk
            if (maxSdk != null && maxSdk < quirk.match.minSdk) {
                problems += "quirks[$index].match.max_sdk is below min_sdk"
            }
        }

        val calibrationKeys = mutableSetOf<String>()
        calibrations.forEachIndexed { index, calibration ->
            if (calibration.deviceKey.isBlank()) {
                problems += "calibrations[$index].device_key is blank"
            }
            if (calibration.cameraId.isBlank()) {
                problems += "calibrations[$index].camera_id is blank"
            }
            if (!calibrationKeys.add(calibration.key)) {
                problems += "duplicate calibration: ${calibration.key}"
            }
            if (calibration.version <= 0) {
                problems += "calibrations[$index].version must be positive"
            }
            if (calibration.matrix.size != 9) {
                problems += "calibrations[$index].matrix must be 3x3"
            }
            if (calibration.matrix.any { !it.isFinite() }) {
                problems += "calibrations[$index].matrix contains a non-finite value"
            }
            if (calibration.exposureTrim <= 0f || !calibration.exposureTrim.isFinite()) {
                problems += "calibrations[$index].exposure_trim must be positive"
            }
            if (calibration.saturationTrim < 0f || !calibration.saturationTrim.isFinite()) {
                problems += "calibrations[$index].saturation_trim must be non-negative"
            }
            val lensKey = calibration.lensKey
            if (!lensKey.isNullOrBlank() && lensKey.split(':').size != 3) {
                problems += "calibrations[$index].lens_key must be facing:role:focal"
            }
            if (calibration.wbROffset !in 0.1f..10f || !calibration.wbROffset.isFinite()) {
                problems += "calibrations[$index].wb_r_offset is out of range"
            }
            if (calibration.wbBOffset !in 0.1f..10f || !calibration.wbBOffset.isFinite()) {
                problems += "calibrations[$index].wb_b_offset is out of range"
            }
            if (calibration.shadowLift !in -1f..1f || !calibration.shadowLift.isFinite()) {
                problems += "calibrations[$index].shadow_lift is out of range"
            }
        }
        return problems
    }

    fun toJson(): String = json.encodeToString(this)

    companion object {
        const val WILDCARD_CAMERA = "*"

        val json: Json = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
            isLenient = false
        }

        val EMPTY = DeviceDatabase()

        fun fromJson(jsonText: String): DeviceDatabase = json.decodeFromString(jsonText)
    }
}

/** Build identity used for quirk and calibration matching. */
data class DeviceIdentity(
    val deviceKey: String,
    val fingerprint: String,
    val sdk: Int,
    val manufacturer: String,
    val brand: String,
    val model: String,
)

@Serializable
data class QuirkMatch(
    @SerialName("device_key") val deviceKey: String,
    @SerialName("camera_id") val cameraId: String? = null,
    /** Version-scoped by build fingerprint prefix, e.g. "POCO/duchamp_in/duchamp:16/". */
    @SerialName("fingerprint_prefix") val fingerprintPrefix: String? = null,
    @SerialName("min_sdk") val minSdk: Int = 0,
    @SerialName("max_sdk") val maxSdk: Int? = null,
) {
    fun matches(identity: DeviceIdentity, cameraId: String? = null): Boolean {
        if (!deviceKey.equals(identity.deviceKey, ignoreCase = true)) return false
        if (identity.sdk < minSdk) return false
        if (maxSdk != null && identity.sdk > maxSdk) return false
        if (fingerprintPrefix != null &&
            !identity.fingerprint.startsWith(fingerprintPrefix, ignoreCase = true)
        ) {
            return false
        }
        if (this.cameraId != null && this.cameraId != cameraId) return false
        return true
    }
}

@Serializable
data class QuirkEntry(
    val id: String,
    val match: QuirkMatch,
    val issue: String,
    val action: String,
    val value: String? = null,
    val reason: String = "",
    val report: String? = null,
)

/** Known actions. Unknown actions are retained but ignored by consumers. */
object QuirkActions {
    const val FORCE_8BIT = "force_8bit"
    const val FORCE_ANTIBANDING = "force_antibanding"
    const val DISABLE_NOISE_REDUCTION = "disable_noise_reduction"
    const val NOTE = "note"

    val known: Set<String> = setOf(
        FORCE_8BIT,
        FORCE_ANTIBANDING,
        DISABLE_NOISE_REDUCTION,
        NOTE,
    )
}

/**
 * ColorChecker-derived normalisation for one sensor/camera.
 *
 * The matrix is row-major RGB. The actual measurement lives in the database;
 * code only applies the result. `camera_id` may be `*` for device-wide data.
 */
@Serializable
data class CalibrationEntry(
    @SerialName("device_key") val deviceKey: String,
    @SerialName("camera_id") val cameraId: String = DeviceDatabase.WILDCARD_CAMERA,
    /** Probe-stable lens key (`facing:role:focal`), preferred over camera_id. */
    @SerialName("lens_key") val lensKey: String? = null,
    val version: Int = 1,
    /** Row-major 3x3 CCM into the linear working space. */
    val matrix: List<Float> = InputProfile.IDENTITY_MATRIX,
    @SerialName("exposure_trim") val exposureTrim: Float = 1f,
    @SerialName("saturation_trim") val saturationTrim: Float = 1f,
    /** Per-lens white-balance multipliers applied before the CCM. */
    @SerialName("wb_r_offset") val wbROffset: Float = 1f,
    @SerialName("wb_b_offset") val wbBOffset: Float = 1f,
    /** Small display-space shadow lift used to align lenses. */
    @SerialName("shadow_lift") val shadowLift: Float = 0f,
    val source: String = "",
    @SerialName("created_at") val createdAt: String? = null,
) {
    internal val key: String
        get() = if (lensKey.isNullOrBlank()) {
            "${deviceKey.lowercase()}/${cameraId.lowercase()}"
        } else {
            "${deviceKey.lowercase()}/${lensKey.lowercase()}"
        }

    fun toInputProfile(): InputProfile = InputProfile(
        version = version,
        deviceKey = deviceKey,
        cameraId = cameraId,
        matrix = matrix,
        exposureTrim = exposureTrim,
        saturationTrim = saturationTrim,
        wbROffset = wbROffset,
        wbBOffset = wbBOffset,
        shadowLift = shadowLift,
    )
}

/** Everything the bridge needs for one camera. */
data class DeviceDatabaseSnapshot(
    val identity: DeviceIdentity,
    val databaseVersion: Int,
    val quirks: List<QuirkEntry>,
    val calibration: CalibrationEntry?,
    val reportOptIn: Boolean,
    val lastReportPath: String?,
    val lastReportAtMs: Long?,
    /** Latest probe summary on disk, or null when the device was never probed. */
    val probeReport: ProbeReport? = null,
) {
    val forceEightBit: Boolean
        get() = quirks.any { it.action == QuirkActions.FORCE_8BIT }

    val calibrationSource: String?
        get() = calibration?.source?.takeIf { it.isNotBlank() }
}
