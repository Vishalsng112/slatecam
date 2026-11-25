package io.github.vishalsng112.slatecam.core.probe.verify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import io.github.vishalsng112.slatecam.core.probe.eval.LogicalZoomMetrics
import io.github.vishalsng112.slatecam.core.probe.model.VerificationCheck
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Verifies that two cameras can run concurrently and that a sample boundary
 * between their streams has no timestamp gap or large exposure/WB jump.
 *
 * The GPU-side switch is not involved here; the check proves the camera/session
 * half that the renderer switch depends on. A pass still needs the runtime
 * standby path to be configured at the active recording mode.
 */
class DualSessionSwitchVerifier(private val context: Context) {

    fun verify(cameraA: String, cameraB: String): VerificationCheck {
        val started = System.currentTimeMillis()
        val checkId = "dualSessionSwitch_c${minOf(cameraA, cameraB)}_c${maxOf(cameraA, cameraB)}"
        if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return failure(checkId, "CAMERA permission not granted", started)
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val charsA = runCatching { manager.getCameraCharacteristics(cameraA) }.getOrNull()
            ?: return failure(checkId, "cannot read camera $cameraA", started)
        val charsB = runCatching { manager.getCameraCharacteristics(cameraB) }.getOrNull()
            ?: return failure(checkId, "cannot read camera $cameraB", started)
        val sizesA = runCatching {
            charsA.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.toSet()
        }.getOrNull().orEmpty()
        val sizesB = runCatching {
            charsB.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.toSet()
        }.getOrNull().orEmpty()
        val size = (sizesA intersect sizesB)
            .filter { it.width <= 1280 && it.height <= 720 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: (sizesA intersect sizesB).minByOrNull { it.width.toLong() * it.height }
            ?: return failure(checkId, "cameras have no common YUV size", started)

        val thread = HandlerThread("probe-dual-session").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { handler.post(it) }
        val samplesA = Collections.synchronizedList(mutableListOf<LogicalZoomMetrics.Sample>())
        val samplesB = Collections.synchronizedList(mutableListOf<LogicalZoomMetrics.Sample>())
        val devices = mutableMapOf<String, CameraDevice>()
        val sessions = mutableMapOf<String, CameraCaptureSession>()
        val readers = mutableListOf<ImageReader>()
        var error: String? = null

        try {
            val openLatch = CountDownLatch(2)
            listOf(cameraA, cameraB).forEach { id ->
                manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        devices[id] = camera
                        openLatch.countDown()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        runCatching { camera.close() }
                        error = "camera $id disconnected"
                        openLatch.countDown()
                    }

                    override fun onError(camera: CameraDevice, code: Int) {
                        runCatching { camera.close() }
                        error = "camera $id open error $code"
                        openLatch.countDown()
                    }
                })
            }
            if (!openLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) error = "open timed out"
            if (error != null) return failure(checkId, error!!, started)

            val configureLatch = CountDownLatch(2)
            listOf(cameraA, cameraB).forEach { id ->
                val device = devices[id] ?: return@forEach
                val reader = ImageReader.newInstance(
                    size.width,
                    size.height,
                    ImageFormat.YUV_420_888,
                    3,
                )
                readers += reader
                reader.setOnImageAvailableListener({ r ->
                    runCatching { r.acquireLatestImage()?.close() }
                }, handler)
                val configuration = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(reader.surface)),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            sessions[id] = session
                            configureLatch.countDown()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            error = "camera $id failed to configure"
                            configureLatch.countDown()
                        }
                    },
                )
                device.createCaptureSession(configuration)
            }
            if (!configureLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                error = "configure timed out"
            }
            if (error != null) return failure(checkId, error!!, started)

            val done = CountDownLatch(1)
            listOf(cameraA, cameraB).forEach { id ->
                val session = sessions[id] ?: return@forEach
                val builder = devices[id]?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    ?: return@forEach
                builder.addTarget(readers[if (id == cameraA) 0 else 1].surface)
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                val target = if (id == cameraA) samplesA else samplesB
                session.setRepeatingRequest(
                    builder.build(),
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                            target += LogicalZoomMetrics.Sample(
                                timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
                                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                                wbRed = gains?.red,
                                wbGreen = gains?.greenEven,
                                wbBlue = gains?.blue,
                                activePhysicalId = id,
                            )
                        }
                    },
                    handler,
                )
            }
            handler.postDelayed({ done.countDown() }, OBSERVE_MS)
            if (!done.await(OBSERVE_MS + TIMEOUT_SECONDS * 1_000L, TimeUnit.MILLISECONDS)) {
                error = "capture timed out"
            }
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            sessions.values.forEach { runCatching { it.close() } }
            devices.values.forEach { runCatching { it.close() } }
            readers.forEach { runCatching { it.close() } }
            handler.postDelayed({ thread.quitSafely() }, 500)
        }

        if (error != null) return failure(checkId, error!!, started)
        val a = synchronized(samplesA) { samplesA.toList() }.sortedBy { it.timestampNs }
        val b = synchronized(samplesB) { samplesB.toList() }.sortedBy { it.timestampNs }
        if (a.size < 8 || b.size < 8) {
            return failure(
                checkId,
                "not enough frames (A=${a.size}, B=${b.size})",
                started,
                size.width,
                size.height,
            )
        }
        val switchAt = (a.last().timestampNs + b.last().timestampNs) / 2
        val selected = a.filter { it.timestampNs <= switchAt } + b.filter { it.timestampNs > switchAt }
        val result = LogicalZoomMetrics.analyze(selected, checkHandoffExposure = false)
        return VerificationCheck(
            id = checkId,
            status = if (result.pass) "pass" else "fail",
            detail = "cameras $cameraA/$cameraB @ ${size.width}x${size.height}: ${result.reason}",
            durationMs = System.currentTimeMillis() - started,
            width = size.width,
            height = size.height,
        )
    }

    private fun failure(
        checkId: String,
        detail: String,
        started: Long,
        width: Int? = null,
        height: Int? = null,
    ) = VerificationCheck(
        id = checkId,
        status = "fail",
        detail = detail,
        durationMs = System.currentTimeMillis() - started,
        width = width,
        height = height,
    )

    private companion object {
        const val TIMEOUT_SECONDS = 6L
        const val OBSERVE_MS = 1_800L
    }
}
