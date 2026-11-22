package io.github.vishalsng112.slatecam.feature.devicereport

import android.content.Context
import io.github.vishalsng112.slatecam.core.probe.ProbeRunner
import java.io.File

/**
 * Phase 1 entry point: run the probe and persist the JSON so it can be pulled
 * from the device. The interactive Flutter surface arrives with Pigeon in
 * Phase 2; this service is what the bridge will eventually call.
 */
class DeviceReportService(private val context: Context) {

    fun runAndPersist(): File {
        val json = ProbeRunner(context.applicationContext).runJson()
        val internal = File(context.filesDir, FILE_NAME)
        internal.writeText(json)

        // Convenience copy for adb pull on builds where that path is reachable.
        runCatching {
            val externalDir = context.getExternalFilesDir(null) ?: return@runCatching
            File(externalDir, FILE_NAME).writeText(json)
        }

        return internal
    }

    companion object {
        const val FILE_NAME = "probe.json"
    }
}
