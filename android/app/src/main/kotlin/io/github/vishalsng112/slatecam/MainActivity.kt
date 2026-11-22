package io.github.vishalsng112.slatecam

import android.os.Bundle
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.github.vishalsng112.slatecam.feature.devicereport.DeviceReportService
import java.io.File

class MainActivity : FlutterActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phase 1: run the probe on launch and persist the report. Wiring only;
        // no camera state or pixels touch this class. Replaced by a Pigeon call
        // driven from the UI in Phase 2.
        Thread {
            runCatching { DeviceReportService(applicationContext).runAndPersist() }
                .onSuccess { file: File ->
                    Log.i(TAG, "probe report written: ${file.absolutePath}")
                }
                .onFailure { error ->
                    Log.e(TAG, "probe failed", error)
                }
        }.start()
    }

    private companion object {
        const val TAG = "SlateCam"
    }
}
