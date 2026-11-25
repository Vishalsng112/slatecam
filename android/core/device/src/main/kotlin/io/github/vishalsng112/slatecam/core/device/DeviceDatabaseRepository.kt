package io.github.vishalsng112.slatecam.core.device

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.vishalsng112.slatecam.core.probe.ProbeRunner
import io.github.vishalsng112.slatecam.core.probe.model.ProbeReport
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DeviceReportResult(
    /** Always written; keeps adb/run-as and local debugging working. */
    val latest: File,
    /** Opt-in archive for the device database; null for debug launches. */
    val archived: File?,
    val report: ProbeReport,
)

/**
 * Android-facing device database repository.
 *
 * The bundled database lives in this module's assets. A user database at
 * `files/device-database.json` is merged over it, so a new model or calibration
 * can be dropped in without shipping an app update. Reports are opt-in by
 * default; the debug probe launch bypasses the preference but still writes only
 * on-device files.
 */
class DeviceDatabaseRepository(
    context: Context,
    private val identity: DeviceIdentity = currentIdentity(),
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cachedDatabase: DeviceDatabase? = null

    @Volatile
    private var cachedReport: ProbeReport? = null
    private var cachedReportModified = -1L

    fun database(): DeviceDatabase = cachedDatabase ?: loadDatabase().also { cachedDatabase = it }

    fun reload(): DeviceDatabase {
        cachedDatabase = null
        cachedReport = null
        cachedReportModified = -1L
        return database()
    }

    /**
     * Latest probe summary for this app, or null when the device has never been
     * probed. Cached by file timestamp so a report written by the device-report
     * screen (or dropped in over adb) is picked up without a restart.
     */
    fun latestReport(): ProbeReport? {
        val file = File(appContext.filesDir, LATEST_REPORT_NAME)
        if (!file.isFile) {
            cachedReport = null
            cachedReportModified = -1L
            return null
        }
        val modified = file.lastModified()
        cachedReport?.let { if (modified == cachedReportModified) return it }

        val report = runCatching { ProbeRunner.json.decodeFromString<ProbeReport>(file.readText()) }
            .onFailure { Log.w(TAG, "latest probe report invalid: ${it.message}") }
            .getOrNull()
        cachedReport = report
        cachedReportModified = modified
        return report
    }

    fun snapshot(
        cameraId: String? = null,
        lensKey: String? = null,
    ): DeviceDatabaseSnapshot {
        val database = database()
        return DeviceDatabaseSnapshot(
            identity = identity,
            databaseVersion = database.version,
            quirks = database.quirksFor(identity, cameraId),
            calibration = database.calibrationFor(identity, cameraId, lensKey),
            reportOptIn = reportOptIn(),
            lastReportPath = lastReportPath(),
            lastReportAtMs = lastReportAtMs(),
            probeReport = latestReport(),
        )
    }

    fun reportOptIn(): Boolean = prefs.getBoolean(KEY_REPORT_OPT_IN, false)

    fun setReportOptIn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REPORT_OPT_IN, enabled).apply()
    }

    fun lastReportPath(): String? = prefs.getString(KEY_LAST_REPORT_PATH, null)

    fun lastReportAtMs(): Long? =
        prefs.getLong(KEY_LAST_REPORT_AT_MS, -1L).takeIf { it > 0L }

    /**
     * Runs the prober and persists a summary report.
     *
     * Blocking: call from a background thread. `requireOptIn = false` is only
     * for the explicit debug launch extra; user-facing calls must honour the
     * preference.
     */
    fun runReport(requireOptIn: Boolean = true): DeviceReportResult {
        if (requireOptIn && !reportOptIn()) {
            throw IllegalStateException("device reports are opt-in")
        }

        val report = ProbeRunner(appContext).run()
        val json = ProbeRunner.json.encodeToString(report)

        val latest = File(appContext.filesDir, LATEST_REPORT_NAME)
        latest.writeText(json)
        cachedReport = null
        cachedReportModified = -1L
        runCatching {
            appContext.getExternalFilesDir(null)?.let { directory ->
                File(directory, LATEST_REPORT_NAME).writeText(json)
            }
        }

        val archive = if (reportOptIn()) {
            archiveReport(json)
        } else {
            null
        }

        return DeviceReportResult(latest = latest, archived = archive, report = report)
    }

    /** Imports a reviewed database, validates it, then reloads merged data. */
    fun importDatabase(jsonText: String): DeviceDatabase {
        val incoming = DeviceDatabase.fromJson(jsonText)
        val problems = incoming.validate()
        require(problems.isEmpty()) {
            "invalid device database: ${problems.joinToString("; ")}"
        }
        userDatabaseFile().apply { parentFile?.mkdirs() }.writeText(jsonText)
        cachedDatabase = null
        return database()
    }

    private fun archiveReport(json: String): File {
        val folderName = identity.deviceKey.replace('/', '_').replace(' ', '_')
        val directory = File(appContext.filesDir, "device-reports/$folderName").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(directory, "$stamp.json")
        file.writeText(json)

        prefs.edit()
            .putString(KEY_LAST_REPORT_PATH, file.absolutePath)
            .putLong(KEY_LAST_REPORT_AT_MS, System.currentTimeMillis())
            .apply()
        return file
    }

    private fun loadDatabase(): DeviceDatabase {
        val bundled = readAsset(BUNDLED_DATABASE_NAME)
            ?.let { text -> parseDatabase(text, "bundled") }
            ?: DeviceDatabase.EMPTY

        val userFile = userDatabaseFile()
        if (!userFile.isFile) return bundled

        val user = runCatching { userFile.readText() }
            .getOrNull()
            ?.let { text -> parseDatabase(text, "user") }
            ?: return bundled

        return bundled.mergedWith(user)
    }

    private fun parseDatabase(text: String, source: String): DeviceDatabase? {
        val database = runCatching { DeviceDatabase.fromJson(text) }
            .onFailure { Log.w(TAG, "$source device database invalid: ${it.message}") }
            .getOrNull()
            ?: return null
        val problems = database.validate()
        if (problems.isNotEmpty()) {
            Log.w(TAG, "$source device database rejected: ${problems.joinToString("; ")}")
            return null
        }
        return database
    }

    private fun readAsset(name: String): String? = runCatching {
        appContext.assets.open(name).bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun userDatabaseFile(): File = File(appContext.filesDir, USER_DATABASE_NAME)

    companion object {
        private const val TAG = "SlateCamDeviceDb"
        const val BUNDLED_DATABASE_NAME = "device-database.json"
        const val USER_DATABASE_NAME = "device-database.json"
        const val LATEST_REPORT_NAME = "probe.json"

        private const val PREFS_NAME = "device-database"
        private const val KEY_REPORT_OPT_IN = "report_opt_in"
        private const val KEY_LAST_REPORT_PATH = "last_report_path"
        private const val KEY_LAST_REPORT_AT_MS = "last_report_at_ms"

        fun currentIdentity(): DeviceIdentity = DeviceIdentity(
            deviceKey = "${Build.MANUFACTURER}/${Build.DEVICE}"
                .lowercase()
                .replace(" ", "_"),
            fingerprint = Build.FINGERPRINT,
            sdk = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
        )
    }
}
