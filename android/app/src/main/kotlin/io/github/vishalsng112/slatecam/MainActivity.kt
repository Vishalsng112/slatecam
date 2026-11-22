package io.github.vishalsng112.slatecam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.github.vishalsng112.slatecam.bridge.SlateCamBridge
import io.github.vishalsng112.slatecam.feature.devicereport.DeviceReportService
import java.io.File

class MainActivity : FlutterActivity() {

    private var bridge: SlateCamBridge? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_RUN_PROBE, false) == true) {
            // Device testing hook: adb shell am start ... --ez run_probe true
            runProbeInBackground()
        }
        requestCameraPermission()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val installed = SlateCamBridge.install(flutterEngine, applicationContext)
        installed.applyStartupOverrides(
            profile = intent?.getStringExtra(EXTRA_PROFILE),
            zebra = intent?.getBooleanExtra(EXTRA_ZEBRA, false) == true,
            zebraThreshold = intent?.getFloatExtra(EXTRA_ZEBRA_THRESHOLD, 0.7f) ?: 0.7f,
        )
        bridge = installed
    }

    override fun onDestroy() {
        bridge?.dispose()
        bridge = null
        super.onDestroy()
    }

    override fun onPause() {
        bridge?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        bridge?.resume()
    }

    private fun requestCameraPermission() {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), CAMERA_REQUEST_CODE)
        }
    }

    private fun runProbeInBackground() {
        // Retained for the future device-report action; not called on launch.
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
        const val CAMERA_REQUEST_CODE = 1001
        const val EXTRA_RUN_PROBE = "run_probe"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_ZEBRA = "zebra"
        const val EXTRA_ZEBRA_THRESHOLD = "zebra_threshold"
    }
}
