package io.github.vishalsng112.slatecam.core.probe.verify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.view.Surface
import io.github.vishalsng112.slatecam.core.probe.model.VerificationCheck
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Proves the camera side of the cinema claim: open the back camera and configure
 * a regular capture session whose output carries the HLG10 dynamic range
 * profile at UHD.
 *
 * This is the fact no CameraCharacteristics read can establish. A device can
 * advertise `DYNAMIC_RANGE_TEN_BIT` and still reject the profile at a given
 * size.
 *
 * Phase 2's production session in `:core:camera` will replace this harness; it
 * exists now because `verified` must mean something.
 */
class CameraSessionVerifier(private val context: Context) {

    fun verifyHlg10Session(): VerificationCheck {
        val started = System.currentTimeMillis()

        if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return VerificationCheck(
                id = CHECK_ID,
                status = "not_run",
                detail = "CAMERA permission not granted",
                durationMs = System.currentTimeMillis() - started,
            )
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val candidate = runCatching { selectCandidate(manager) }.getOrNull()
            ?: return failure(
                "no back camera offering HLG10 and a UHD/FHD stream",
                started,
            )

        val executor = Executors.newSingleThreadExecutor()
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var texture: SurfaceTexture? = null
        var surface: Surface? = null
        var error: String? = null

        try {
            device = open(manager, candidate.id, executor)

            val localTexture = SurfaceTexture(0).apply {
                setDefaultBufferSize(candidate.width, candidate.height)
            }
            texture = localTexture
            val localSurface = Surface(localTexture)
            surface = localSurface

            val output = OutputConfiguration(localSurface).apply {
                setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
            }

            val latch = CountDownLatch(1)
            val configuration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(output),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        session = configured
                        latch.countDown()
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        error = "onConfigureFailed"
                        latch.countDown()
                    }
                },
            )

            device.createCaptureSession(configuration)
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                error = "session configure timed out"
            }
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { surface?.release() }
            runCatching { texture?.release() }
            runCatching { executor.shutdownNow() }
        }

        return if (error == null) {
            VerificationCheck(
                id = CHECK_ID,
                status = "pass",
                detail = "camera ${candidate.id}: HLG10 session configured at " +
                    "${candidate.width}x${candidate.height}",
                durationMs = System.currentTimeMillis() - started,
                width = candidate.width,
                height = candidate.height,
            )
        } else {
            failure(
                "camera ${candidate.id} ${candidate.width}x${candidate.height}: $error",
                started,
            )
        }
    }

    private fun open(manager: CameraManager, id: String, executor: Executor): CameraDevice {
        val latch = CountDownLatch(1)
        var opened: CameraDevice? = null
        var error: String? = null

        manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                opened = camera
                latch.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                error = "camera disconnected"
                latch.countDown()
            }

            override fun onError(camera: CameraDevice, code: Int) {
                error = "camera open error $code"
                latch.countDown()
            }
        })

        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) error = "camera open timed out"
        error?.let { throw IllegalStateException(it) }
        return opened ?: throw IllegalStateException("camera did not open")
    }

    private data class Candidate(val id: String, val width: Int, val height: Int)

    private fun selectCandidate(manager: CameraManager): Candidate? {
        for (id in manager.cameraIdList) {
            val characteristics = runCatching {
                manager.getCameraCharacteristics(id)
            }.getOrNull() ?: continue

            if (characteristics.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_BACK
            ) {
                continue
            }

            val capabilities = characteristics
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.toList()
                ?: continue
            if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT
                !in capabilities
            ) {
                continue
            }

            val profiles = characteristics
                .get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            if (profiles != null && DynamicRangeProfiles.HLG10 !in profiles.supportedProfiles) {
                continue
            }

            val map = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: continue
            val sizes = runCatching { map.getOutputSizes(ImageFormat.PRIVATE) }.getOrNull()
                ?: continue

            val chosen = sizes.firstOrNull { it.width == 3840 && it.height == 2160 }
                ?: sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
                ?: continue

            return Candidate(id, chosen.width, chosen.height)
        }
        return null
    }

    private fun failure(detail: String, started: Long) = VerificationCheck(
        id = CHECK_ID,
        status = "fail",
        detail = detail,
        durationMs = System.currentTimeMillis() - started,
    )

    private companion object {
        const val CHECK_ID = "camera_hlg10_session"
        const val TIMEOUT_SECONDS = 6L
    }
}
