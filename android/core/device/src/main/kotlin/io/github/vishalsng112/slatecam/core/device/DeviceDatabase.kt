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
    fun calibrationFor(identity: DeviceIdentity, cameraId: String? = null): CalibrationEntry? {
        val forDevice = calibrations.filter {
            it.deviceKey.equals(identity.deviceKey, ignoreCase = true)
        }
        val exact = forDevice
            .filter { cameraId != null && it.cameraId == cameraId }
            .maxByOrNull { it.version }
        val wildcard = forDevice
            .filter { it.cameraId == WILDCARD_CAMERA }
            .maxByOrNull { it.version }
        return exact ?: wildcard
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
    val version: Int = 1,
    val matrix: List<Float> = InputProfile.IDENTITY_MATRIX,
    @SerialName("exposure_trim") val exposureTrim: Float = 1f,
    @SerialName("saturation_trim") val saturationTrim: Float = 1f,
    val source: String = "",
    @SerialName("created_at") val createdAt: String? = null,
) {
    internal val key: String
        get() = "${deviceKey.lowercase()}/${cameraId.lowercase()}"

    fun toInputProfile(): InputProfile = InputProfile(
        version = version,
        deviceKey = deviceKey,
        cameraId = cameraId,
        matrix = matrix,
        exposureTrim = exposureTrim,
        saturationTrim = saturationTrim,
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
