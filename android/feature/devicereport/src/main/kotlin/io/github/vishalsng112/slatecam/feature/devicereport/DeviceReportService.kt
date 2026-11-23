package io.github.vishalsng112.slatecam.feature.devicereport

import android.content.Context
import io.github.vishalsng112.slatecam.core.device.DeviceDatabaseRepository
import io.github.vishalsng112.slatecam.core.device.DeviceReportResult
import java.io.File

/**
 * Device-report entry point used by the app shell and the bridge.
 *
 * The repository owns opt-in state, report archiving and the device database;
 * this service only exposes the two launch paths.
 */
class DeviceReportService(private val context: Context) {

    private val repository = DeviceDatabaseRepository(context.applicationContext)

    /** Debug path used by the `run_probe` launch extra; bypasses opt-in. */
    fun runAndPersist(): File = repository.runReport(requireOptIn = false).latest

    /** User path: requires the opt-in preference and archives the summary. */
    fun runOptedInReport(): DeviceReportResult = repository.runReport(requireOptIn = true)

    companion object {
        const val FILE_NAME = DeviceDatabaseRepository.LATEST_REPORT_NAME
    }
}
