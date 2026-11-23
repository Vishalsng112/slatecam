package io.github.vishalsng112.slatecam.core.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Preview-only controller for two cameras.
 *
 * The ordering is the whole point: both devices are opened **before** either
 * session is configured. Opening the second camera after the first has a session
 * returns `ERROR_MAX_CAMERAS_IN_USE` on this platform.
 */
class DualPreviewController(private val context: Context) {

    fun interface Listener {
        fun onState(open: Boolean, error: String?, rotationPrimary: Int, rotationSecondary: Int)
    }

    var listener: Listener? = null

    private val thread = HandlerThread("slatecam-dual").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var devicePrimary: CameraDevice? = null
    private var deviceSecondary: CameraDevice? = null
    private var sessionPrimary: CameraCaptureSession? = null
    private var sessionSecondary: CameraCaptureSession? = null

    private var rotationPrimary = 0
    private var rotationSecondary = 0

    fun open(
        primaryId: String,
        secondaryId: String,
        primarySurface: Surface,
        secondarySurface: Surface,
        width: Int,
        height: Int,
    ) {
        handler.post {
            try {
                closeInternal()
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

                val primaryChars = manager.getCameraCharacteristics(primaryId)
                val secondaryChars = manager.getCameraCharacteristics(secondaryId)
                rotationPrimary = rotationFor(primaryChars)
                rotationSecondary = rotationFor(secondaryChars)

                val openLatch = CountDownLatch(2)
                var openError: String? = null

                manager.openCamera(primaryId, executor, callback(
                    onOpenedDevice = { devicePrimary = it },
                    onFailure = { openError = "primary: $it" },
                    latch = openLatch,
                ))
                manager.openCamera(secondaryId, executor, callback(
                    onOpenedDevice = { deviceSecondary = it },
                    onFailure = { openError = "secondary: $it" },
                    latch = openLatch,
                ))

                if (!openLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    openError = "timeout opening cameras"
                }
                if (openError != null) {
                    fail(openError!!)
                    return@post
                }

                val configureLatch = CountDownLatch(2)
                var configureError: String? = null

                val primaryDevice = devicePrimary
                val secondaryDevice = deviceSecondary
                if (primaryDevice == null || secondaryDevice == null) {
                    fail("cameras did not open")
                    return@post
                }

                configureSession(
                    devicePrimary = primaryDevice,
                    surface = primarySurface,
                    onSession = { sessionPrimary = it },
                    onFailure = { configureError = "primary: $it" },
                    latch = configureLatch,
                )
                configureSession(
                    devicePrimary = secondaryDevice,
                    surface = secondarySurface,
                    onSession = { sessionSecondary = it },
                    onFailure = { configureError = "secondary: $it" },
                    latch = configureLatch,
                )

                if (!configureLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    configureError = "timeout configuring sessions"
                }
                if (configureError != null) {
                    fail(configureError!!)
                    return@post
                }

                startPreview(primaryDevice, sessionPrimary, primarySurface)
                startPreview(secondaryDevice, sessionSecondary, secondarySurface)

                mainHandler.post {
                    listener?.onState(true, null, rotationPrimary, rotationSecondary)
                }
            } catch (t: Throwable) {
                fail("${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    fun close() {
        handler.post {
            closeInternal()
            mainHandler.post { listener?.onState(false, null, 0, 0) }
        }
    }

    fun release() {
        handler.post {
            closeInternal()
            mainHandler.postDelayed({ thread.quitSafely() }, 500)
        }
    }

    private fun callback(
        onOpenedDevice: (CameraDevice) -> Unit,
        onFailure: (String) -> Unit,
        latch: CountDownLatch,
    ) = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            onOpenedDevice(camera)
            latch.countDown()
        }

        override fun onDisconnected(camera: CameraDevice) {
            runCatching { camera.close() }
            onFailure("disconnected")
            latch.countDown()
        }

        override fun onError(camera: CameraDevice, code: Int) {
            runCatching { camera.close() }
            onFailure("error $code")
            latch.countDown()
        }
    }

    private fun configureSession(
        devicePrimary: CameraDevice,
        surface: Surface,
        onSession: (CameraCaptureSession) -> Unit,
        onFailure: (String) -> Unit,
        latch: CountDownLatch,
    ) {
        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(surface)),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    onSession(session)
                    latch.countDown()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onFailure("session configuration failed")
                    latch.countDown()
                }
            },
        )
        devicePrimary.createCaptureSession(configuration)
    }

    private fun startPreview(
        device: CameraDevice,
        session: CameraCaptureSession?,
        surface: Surface,
    ) {
        val captureSession = session ?: return
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        request.addTarget(surface)
        request.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        captureSession.setRepeatingRequest(request.build(), null, handler)
    }

    private fun fail(detail: String) {
        closeInternal()
        mainHandler.post {
            listener?.onState(false, detail, rotationPrimary, rotationSecondary)
        }
    }

    private fun closeInternal() {
        runCatching { sessionPrimary?.close() }
        runCatching { sessionSecondary?.close() }
        runCatching { devicePrimary?.close() }
        runCatching { deviceSecondary?.close() }
        sessionPrimary = null
        sessionSecondary = null
        devicePrimary = null
        deviceSecondary = null
    }

    private fun rotationFor(characteristics: CameraCharacteristics): Int {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val windowManager =
            context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay.rotation
        return when (display) {
            Surface.ROTATION_0 -> sensor
            Surface.ROTATION_90 -> (360 - sensor) % 360
            Surface.ROTATION_180 -> (sensor + 180) % 360
            Surface.ROTATION_270 -> (sensor + 90) % 360
            else -> sensor
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 12L
    }
}
