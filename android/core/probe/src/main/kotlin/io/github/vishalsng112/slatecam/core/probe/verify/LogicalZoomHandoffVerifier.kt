package io.github.vishalsng112.slatecam.core.probe.verify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import io.github.vishalsng112.slatecam.core.probe.eval.LogicalZoomMetrics
import io.github.vishalsng112.slatecam.core.probe.model.CameraInfo
import io.github.vishalsng112.slatecam.core.probe.model.VerificationCheck
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Records a short camera stream while sweeping `CONTROL_ZOOM_RATIO` across a
 * logical camera's discovered stops, then checks frame continuity and 3A jumps.
 *
 * This is the `logicalZoomHandoff` verdict: advertising a logical camera and a
 * zoom range is not evidence that the HAL hands off cleanly. The verifier runs
 * last in the probe because opening a vendor-hidden logical ID can disturb the
 * camera list for other checks.
 */
class LogicalZoomHandoffVerifier(private val context: Context) {

    fun verify(camera: CameraInfo, checkId: String): VerificationCheck {
        val started = System.currentTimeMillis()
        if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return failure("CAMERA permission not granted", started, checkId)
        }
        val logicalZoom = camera.logicalZoom
            ?: return failure("camera has no logical_zoom data", started, checkId)
        if (!logicalZoom.handoffEligible) {
            return failure(
                "logical zoom is not eligible: ${logicalZoom.reason}",
                started,
                checkId,
            )
        }
        if (logicalZoom.stops.size < 2) {
            return failure("fewer than two zoom stops", started, checkId)
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = runCatching {
            manager.getCameraCharacteristics(camera.id)
        }.getOrNull()
            ?: return failure("cannot read camera ${camera.id}", started, checkId)
        val range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            ?: return failure("camera ${camera.id} has no CONTROL_ZOOM_RATIO_RANGE", started, checkId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return failure("camera ${camera.id} has no stream map", started, checkId)
        val size = map.getOutputSizes(ImageFormat.YUV_420_888)
            ?.filter { it.width <= 1280 && it.height <= 720 }
            ?.maxByOrNull { it.width.toLong() * it.height }
            ?: map.getOutputSizes(ImageFormat.YUV_420_888)?.minByOrNull {
                it.width.toLong() * it.height
            }
            ?: return failure("camera ${camera.id} has no YUV output size", started, checkId)

        val sequence = sweepSequence(
            logicalZoom.stops.map { it.ratio },
            range.lower.toDouble(),
            range.upper.toDouble(),
        )
        if (sequence.size < 3) {
            return failure("not enough reachable stops to sweep", started, checkId)
        }

        val thread = HandlerThread("probe-logical-zoom").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { handler.post(it) }
        val samples = Collections.synchronizedList(mutableListOf<LogicalZoomMetrics.Sample>())
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        var error: String? = null

        try {
            device = open(manager, camera.id, executor)
            val localReader = ImageReader.newInstance(
                size.width,
                size.height,
                ImageFormat.YUV_420_888,
                3,
            )
            reader = localReader
            localReader.setOnImageAvailableListener({ imageReader ->
                runCatching { imageReader.acquireLatestImage()?.close() }
            }, handler)

            val sessionLatch = CountDownLatch(1)
            val configuration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(android.hardware.camera2.params.OutputConfiguration(localReader.surface)),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        session = configured
                        sessionLatch.countDown()
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        error = "onConfigureFailed"
                        sessionLatch.countDown()
                    }
                },
            )
            device.createCaptureSession(configuration)
            if (!sessionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                error = "session configure timed out"
            }
            val configured = session
            if (error != null || configured == null) {
                return failure(
                    "camera ${camera.id} session failed: ${error ?: "null session"}",
                    started,
                    checkId,
                )
            }

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(localReader.surface)
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, sequence.first().toFloat())

            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                    samples += LogicalZoomMetrics.Sample(
                        timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
                        exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                        iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                        wbRed = gains?.red,
                        wbGreen = gains?.greenEven,
                        wbBlue = gains?.blue,
                        activePhysicalId = result.get(
                            CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID,
                        ),
                    )
                }
            }

            val done = CountDownLatch(1)
            configured.setRepeatingRequest(builder.build(), callback, handler)
            sequence.drop(1).forEachIndexed { index, ratio ->
                handler.postDelayed({
                    runCatching {
                        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio.toFloat())
                        configured.setRepeatingRequest(builder.build(), callback, handler)
                    }.onFailure { error = "zoom request: ${it.message}" }
                }, STEP_MS * (index + 1))
            }
            handler.postDelayed({ done.countDown() }, STEP_MS * sequence.size + SETTLE_MS)
            if (!done.await(STEP_MS * sequence.size + SETTLE_MS + TIMEOUT_SECONDS * 1_000L,
                    TimeUnit.MILLISECONDS)
            ) {
                error = "sweep timed out"
            }
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            handler.postDelayed({ thread.quitSafely() }, 500)
        }

        if (error != null) {
            return failure("camera ${camera.id}: $error", started, checkId)
        }
        val snapshot = synchronized(samples) { samples.toList() }
        val result = LogicalZoomMetrics.analyze(snapshot)
        return VerificationCheck(
            id = checkId,
            status = if (result.pass) "pass" else "fail",
            detail = "camera ${camera.id} @ ${size.width}x${size.height}: ${result.reason}",
            durationMs = System.currentTimeMillis() - started,
            width = size.width,
            height = size.height,
        )
    }

    /**
     * Start at 1.0×, visit every stop, and return to 1.0×. This exercises both
     * directions of the handoff and keeps the camera on its normal lens.
     */
    private fun sweepSequence(
        stops: List<Double>,
        min: Double,
        max: Double,
    ): List<Double> {
        val reachable = stops
            .filter { it in min - 0.01..max + 0.01 }
            .sorted()
        val base = if (reachable.any { kotlin.math.abs(it - 1.0) < 0.05 }) {
            1.0
        } else {
            reachable.firstOrNull() ?: return emptyList()
        }
        val sequence = mutableListOf(base)
        reachable.forEach { ratio ->
            if (kotlin.math.abs(ratio - sequence.last()) > 0.01) sequence += ratio
        }
        if (kotlin.math.abs(sequence.last() - base) > 0.01) sequence += base
        return sequence
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

    private fun failure(detail: String, started: Long, checkId: String) = VerificationCheck(
        id = checkId,
        status = "fail",
        detail = detail,
        durationMs = System.currentTimeMillis() - started,
    )

    private companion object {
        const val TIMEOUT_SECONDS = 6L
        const val STEP_MS = 800L
        const val SETTLE_MS = 1_200L
    }
}
