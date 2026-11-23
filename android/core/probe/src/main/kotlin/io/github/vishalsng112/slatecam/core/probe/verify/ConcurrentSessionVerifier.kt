package io.github.vishalsng112.slatecam.core.probe.verify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.graphics.SurfaceTexture
import android.view.Surface
import io.github.vishalsng112.slatecam.core.probe.model.VerificationCheck
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tries to configure two cameras at once, using only pairs the platform reports
 * through `getConcurrentCameraIds`.
 *
 * Devices often advertise concurrency and then fail at stream configuration, so
 * this is a verification check, not a capability flag.
 */
class ConcurrentSessionVerifier(private val context: Context) {

    fun verify(): VerificationCheck {
        val started = System.currentTimeMillis()

        if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return result(
                "not_run",
                "CAMERA permission not granted",
                started,
            )
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val groups = runCatching { manager.concurrentCameraIds }.getOrDefault(emptySet())
        val pair = groups
            .filter { it.size >= 2 }
            .maxByOrNull { it.size }
            ?.take(2)
            ?.toList()
            ?: return result("not_run", "device reports no concurrent camera pair", started)

        val thread = android.os.HandlerThread("probe-concurrent").apply { start() }
        val handler = android.os.Handler(thread.looper)
        val executor = java.util.concurrent.Executor { handler.post(it) }
        val devices = mutableMapOf<String, CameraDevice>()
        val sessions = mutableMapOf<String, CameraCaptureSession>()
        val textures = mutableListOf<SurfaceTexture>()
        val surfaces = mutableListOf<Surface>()

        try {
            val openLatch = CountDownLatch(pair.size)
            var openError: String? = null

            for (id in pair) {
                manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        devices[id] = camera
                        openLatch.countDown()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        runCatching { camera.close() }
                        openError = "camera $id disconnected"
                        openLatch.countDown()
                    }

                    override fun onError(camera: CameraDevice, code: Int) {
                        runCatching { camera.close() }
                        openError = "camera $id open error $code"
                        openLatch.countDown()
                    }
                })
            }

            if (!openLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                openError = "timeout opening concurrent cameras"
            }
            openError?.let { return result("fail", it, started) }

            val configureLatch = CountDownLatch(pair.size)
            var configureError: String? = null

            for (id in pair) {
                val device = devices[id] ?: continue
                val texture = SurfaceTexture(0)
                texture.setDefaultBufferSize(PREVIEW_WIDTH, PREVIEW_HEIGHT)
                textures += texture
                val surface = Surface(texture)
                surfaces += surface

                val output = OutputConfiguration(surface)
                val configuration = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(output),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            sessions[id] = session
                            configureLatch.countDown()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            configureError = "camera $id failed to configure"
                            configureLatch.countDown()
                        }
                    },
                )
                device.createCaptureSession(configuration)
            }

            if (!configureLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                configureError = "timeout configuring concurrent sessions"
            }
            configureError?.let { return result("fail", it, started) }

            return result(
                "pass",
                "cameras ${pair.joinToString("+")} configured concurrently",
                started,
            )
        } catch (t: Throwable) {
            return result("fail", "${t.javaClass.simpleName}: ${t.message}", started)
        } finally {
            sessions.values.forEach { runCatching { it.close() } }
            devices.values.forEach { runCatching { it.close() } }
            surfaces.forEach { runCatching { it.release() } }
            textures.forEach { runCatching { it.release() } }
            // device.close() dispatches onClosed on this executor; shutting it down
            // immediately crashes some vendor camera stacks.
            handler.postDelayed({ thread.quitSafely() }, 500)
        }
    }

    private fun result(status: String, detail: String, started: Long) = VerificationCheck(
        id = CHECK_ID,
        status = status,
        detail = detail,
        durationMs = System.currentTimeMillis() - started,
    )

    private companion object {
        const val CHECK_ID = "concurrent_camera_session"
        const val TIMEOUT_SECONDS = 8L
        const val PREVIEW_WIDTH = 640
        const val PREVIEW_HEIGHT = 480
    }
}
