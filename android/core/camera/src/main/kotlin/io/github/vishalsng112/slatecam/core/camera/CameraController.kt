package io.github.vishalsng112.slatecam.core.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Surface
import io.github.vishalsng112.slatecam.core.common.ShutterAngle
import java.util.concurrent.Executor

/** Snapshot of camera state. Kotlin owns this; Dart only renders it. */
data class CameraUiState(
    val isOpen: Boolean,
    val error: String? = null,
    val iso: Int,
    val isoMin: Int,
    val isoMax: Int,
    val shutterNs: Long,
    val shutterMinNs: Long,
    val shutterMaxNs: Long,
    val focusDiopters: Float,
    val focusMin: Float,
    val focusMax: Float,
    val whiteBalanceMode: String,
    val whiteBalanceModes: List<String>,
    val autoExposure: Boolean,
    val autoFocus: Boolean,
    val previewWidth: Int,
    val previewHeight: Int,
    val previewRotationDegrees: Int,
    val isTenBit: Boolean = false,
    val shutterAngle: Float = 0f,
    val frameRate: Int = 30,
    val edgeMode: String = "off",
    val edgeModes: List<String> = emptyList(),
    val noiseReduction: String = "off",
    val noiseReductionModes: List<String> = emptyList(),
    val antibanding: String = "auto",
    val antibandingModes: List<String> = emptyList(),
)

/**
 * Owns the Camera2 device, session and repeating request.
 *
 * Threading: every Camera2 call happens on the module's own HandlerThread.
 * State is always emitted on the main thread.
 */
class CameraController(private val context: Context) {

    fun interface Listener {
        fun onState(state: CameraUiState)
    }

    var listener: Listener? = null

    private val thread = HandlerThread("slatecam-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executor { command -> handler.post(command) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var builder: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null

    private var previewSize = Size(1920, 1080)
    private var open = false
    private var errorMessage: String? = null

    private var isoMin = 100
    private var isoMax = 3200
    private var shutterMinNs = 1_000L
    private var shutterMaxNs = 1_000_000_000L
    private var focusMax = 0f
    private var wbModes: List<String> = listOf("auto")
    private var fpsRange: Range<Int>? = null

    private var iso = 400
    private var shutterNs = 1_000_000_000L / 50
    private var focusDiopters = 0f
    private var wbMode = "auto"
    private var autoExposure = true
    private var autoFocus = true
    private var rotationDegrees = 0
    private var initialized = false
    private var wantsTenBit = false
    private var tenBitActive = false
    private var retriedStandard = false

    private var edgeModes: List<String> = emptyList()
    private var noiseReductionModes: List<String> = emptyList()
    private var antibandingModes: List<String> = emptyList()
    private var hotPixelModes: List<String> = emptyList()
    private var shadingModes: List<String> = emptyList()
    private var aberrationModes: List<String> = emptyList()
    private var distortionModes: List<String> = emptyList()

    private var edgeMode = "off"
    private var noiseReduction = "off"
    private var antibanding = "auto"
    private var modesVerified = false
    private var frameRate = 30
    private var timestampRealtime = false

    val isTimestampRealtime: Boolean get() = timestampRealtime

    val isTenBitActive: Boolean get() = tenBitActive

    fun open(
        previewTarget: Surface,
        width: Int,
        height: Int,
        tenBit: Boolean = false,
        cameraIdOverride: String? = null,
    ) {
        handler.post {
            try {
                closeInternal()
                previewSurface = previewTarget
                previewSize = Size(width, height)
                wantsTenBit = tenBit
                tenBitActive = false
                retriedStandard = false
                errorMessage = null

                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cameraIdOverride ?: selectCamera(manager)
                val characteristics = manager.getCameraCharacteristics(cameraId)
                readCapabilities(characteristics)

                manager.openCamera(cameraId, callbackExecutor, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        createSession()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        device = null
                        open = false
                        errorMessage = "camera disconnected"
                        emit()
                    }

                    override fun onError(camera: CameraDevice, code: Int) {
                        camera.close()
                        device = null
                        open = false
                        errorMessage = "camera error $code"
                        emit()
                    }
                })
            } catch (t: Throwable) {
                open = false
                errorMessage = "${t.javaClass.simpleName}: ${t.message}"
                emit()
            }
        }
    }

    fun close() {
        handler.post {
            closeInternal()
            emit()
        }
    }

    fun setIso(value: Int) {
        handler.post {
            iso = value.coerceIn(isoMin, isoMax)
            autoExposure = false
            applyAndEmit()
        }
    }

    fun setShutterNs(value: Long) {
        handler.post {
            shutterNs = value.coerceIn(shutterMinNs, shutterMaxNs)
            autoExposure = false
            applyAndEmit()
        }
    }

    fun setShutterAngle(degrees: Float) {
        val exposure = ShutterAngle.exposureNs(degrees, frameRate)
        handler.post {
            shutterNs = exposure.coerceIn(shutterMinNs, shutterMaxNs)
            autoExposure = false
            applyAndEmit()
        }
    }

    fun setFocusDiopters(value: Float) {
        handler.post {
            focusDiopters = value.coerceIn(0f, focusMax)
            autoFocus = false
            applyAndEmit()
        }
    }

    fun setWhiteBalanceMode(mode: String) {
        handler.post {
            if (mode !in wbModes) return@post
            wbMode = mode
            applyAndEmit()
        }
    }

    fun setAutoExposure() {
        handler.post {
            autoExposure = true
            applyAndEmit()
        }
    }

    fun setAutoFocus() {
        handler.post {
            autoFocus = true
            applyAndEmit()
        }
    }

    fun setEdgeMode(mode: String) {
        handler.post {
            if (mode !in edgeModes) return@post
            edgeMode = mode
            applyAndEmit()
        }
    }

    fun setNoiseReduction(mode: String) {
        handler.post {
            if (mode !in noiseReductionModes) return@post
            noiseReduction = mode
            applyAndEmit()
        }
    }

    fun setAntibanding(mode: String) {
        handler.post {
            if (mode !in antibandingModes) return@post
            antibanding = mode
            applyAndEmit()
        }
    }

    /** Closes the camera and stops the camera thread. Call when the owner goes away. */
    fun shutdown() {
        handler.post {
            closeInternal()
            emit()
            thread.quitSafely()
        }
    }

    private fun selectCamera(manager: CameraManager): String {
        val ids = manager.cameraIdList
        val back = ids.firstOrNull { id ->
            runCatching {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }.getOrDefault(false)
        }
        return back ?: ids.firstOrNull() ?: error("no cameras available")
    }

    private fun readCapabilities(characteristics: CameraCharacteristics) {
        val sens = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        isoMin = sens?.lower ?: 100
        isoMax = sens?.upper ?: 3200

        val exposure = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        shutterMinNs = exposure?.lower ?: 1_000L
        shutterMaxNs = exposure?.upper ?: 1_000_000_000L

        focusMax = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        wbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?.map(::awbModeName)
            ?.distinct()
            ?: listOf("auto")

        // Ranges are always refreshed; user values survive a close/reopen so
        // backgrounding the app does not silently undo manual settings.
        if (!initialized) {
            iso = 400.coerceIn(isoMin, isoMax)
            shutterNs = (1_000_000_000L / 50).coerceIn(shutterMinNs, shutterMaxNs)
            focusDiopters = 0f
            wbMode = if ("auto" in wbModes) "auto" else wbModes.first()
            initialized = true
        } else {
            iso = iso.coerceIn(isoMin, isoMax)
            shutterNs = shutterNs.coerceIn(shutterMinNs, shutterMaxNs)
            if (wbMode !in wbModes) wbMode = if ("auto" in wbModes) "auto" else wbModes.first()
        }

        fpsRange = pickFpsRange(characteristics)
        frameRate = fpsRange?.upper ?: 30
        timestampRealtime = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

        edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            ?.map(::edgeModeName) ?: emptyList()
        noiseReductionModes = characteristics.get(
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
        )?.map(::noiseReductionName) ?: emptyList()
        antibandingModes = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES,
        )?.map(::antibandingName) ?: emptyList()
        hotPixelModes = characteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
            ?.map(::hotPixelName) ?: emptyList()
        shadingModes = characteristics.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)
            ?.map(::shadingName) ?: emptyList()
        aberrationModes = characteristics.get(
            CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
        )?.map(::aberrationName) ?: emptyList()
        distortionModes = characteristics.get(
            CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES,
        )?.map(::distortionName) ?: emptyList()

        if (!initialized) {
            edgeMode = if ("off" in edgeModes) "off" else edgeModes.firstOrNull() ?: "off"
            noiseReduction = if ("off" in noiseReductionModes) {
                "off"
            } else {
                noiseReductionModes.firstOrNull() ?: "off"
            }
            antibanding = when {
                "50hz" in antibandingModes -> "50hz"
                "auto" in antibandingModes -> "auto"
                else -> antibandingModes.firstOrNull() ?: "auto"
            }
        }

        rotationDegrees = displayRotationDegrees(characteristics)
        android.util.Log.i(
            "SlateCam",
            "preview rotation=$rotationDegrees " +
                "sensor=${characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)} " +
                "display=${currentDisplayRotation()}",
        )
    }

    private fun currentDisplayRotation(): Int = runCatching {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.rotation
    }.getOrDefault(Surface.ROTATION_0)

    /** Canonical Camera2 preview rotation for a back-facing camera. */
    private fun displayRotationDegrees(characteristics: CameraCharacteristics): Int {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        return when (currentDisplayRotation()) {
            Surface.ROTATION_0 -> sensor
            Surface.ROTATION_90 -> (360 - sensor) % 360
            Surface.ROTATION_180 -> (sensor + 180) % 360
            Surface.ROTATION_270 -> (sensor + 90) % 360
            else -> sensor
        }
    }

    private fun pickFpsRange(characteristics: CameraCharacteristics): Range<Int>? {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        return ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
            ?: ranges.maxByOrNull { it.upper }
    }

    private fun createSession() {
        val camera = device ?: return
        val target = previewSurface ?: return

        val useTenBit = wantsTenBit && !retriedStandard
        val output = OutputConfiguration(target)
        val profileApplied = if (useTenBit) {
            runCatching { output.setDynamicRangeProfile(DynamicRangeProfiles.HLG10) }.isSuccess
        } else {
            false
        }

        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(output),
            callbackExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    tenBitActive = useTenBit && profileApplied
                    session = configured
                    startPreview()
                }

                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    if (useTenBit) {
                        // HLG10 rejected: retry once with a standard session.
                        retriedStandard = true
                        tenBitActive = false
                        createSession()
                    } else {
                        open = false
                        errorMessage = "session configuration failed"
                        emit()
                    }
                }
            },
        )

        runCatching { camera.createCaptureSession(configuration) }
            .onFailure {
                open = false
                errorMessage = "createCaptureSession: ${it.message}"
                emit()
            }
    }

    private fun startPreview() {
        val camera = device ?: return
        val target = previewSurface ?: return
        val captureSession = session ?: return

        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            request.addTarget(target)
            applyControls(request)
            modesVerified = false
            captureSession.setRepeatingRequest(request.build(), captureCallback, handler)
            builder = request
            open = true
            errorMessage = null
            emit()
        } catch (t: Throwable) {
            open = false
            errorMessage = "preview start: ${t.message}"
            emit()
        }
    }

    private fun applyControls(request: CaptureRequest.Builder) {
        request.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        if (autoExposure) {
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        } else {
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            request.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
        }
        fpsRange?.let { request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

        if (autoFocus) {
            request.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            )
        } else {
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            request.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
        }

        request.set(CaptureRequest.CONTROL_AWB_MODE, awbModeValue(wbMode))

        if (edgeMode in edgeModes) {
            request.set(CaptureRequest.EDGE_MODE, edgeModeValue(edgeMode))
        }
        if (noiseReduction in noiseReductionModes) {
            request.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseReductionValue(noiseReduction))
        }
        if (antibanding in antibandingModes) {
            request.set(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                antibandingValue(antibanding),
            )
        }
        if ("off" in hotPixelModes) {
            request.set(CaptureRequest.HOT_PIXEL_MODE, CameraCharacteristics.HOT_PIXEL_MODE_OFF)
        }
        if ("off" in shadingModes) {
            request.set(CaptureRequest.SHADING_MODE, CameraCharacteristics.SHADING_MODE_OFF)
        }
        if ("off" in aberrationModes) {
            request.set(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                CameraCharacteristics.COLOR_CORRECTION_ABERRATION_MODE_OFF,
            )
        }
        if ("off" in distortionModes) {
            request.set(
                CaptureRequest.DISTORTION_CORRECTION_MODE,
                CameraCharacteristics.DISTORTION_CORRECTION_MODE_OFF,
            )
        }
    }

    private fun applyAndEmit() {
        val request = builder
        val captureSession = session
        if (request != null && captureSession != null) {
            runCatching {
                applyControls(request)
                captureSession.setRepeatingRequest(request.build(), captureCallback, handler)
            }.onFailure { errorMessage = it.message }
        }
        emit()
    }

    private fun closeInternal() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
        builder = null
        previewSurface = null
        open = false
    }

    private fun emit() {
        val state = CameraUiState(
            isOpen = open,
            error = errorMessage,
            iso = iso,
            isoMin = isoMin,
            isoMax = isoMax,
            shutterNs = shutterNs,
            shutterMinNs = shutterMinNs,
            shutterMaxNs = shutterMaxNs,
            focusDiopters = focusDiopters,
            focusMin = 0f,
            focusMax = focusMax,
            whiteBalanceMode = wbMode,
            whiteBalanceModes = wbModes,
            autoExposure = autoExposure,
            autoFocus = autoFocus,
            previewWidth = previewSize.width,
            previewHeight = previewSize.height,
            previewRotationDegrees = rotationDegrees,
            isTenBit = tenBitActive,
            shutterAngle = ShutterAngle.degrees(shutterNs, frameRate),
            frameRate = frameRate,
            edgeMode = edgeMode,
            edgeModes = edgeModes,
            noiseReduction = noiseReduction,
            noiseReductionModes = noiseReductionModes,
            antibanding = antibanding,
            antibandingModes = antibandingModes,
        )
        mainHandler.post { listener?.onState(state) }
    }

    private fun awbModeName(mode: Int): String = when (mode) {
        CameraCharacteristics.CONTROL_AWB_MODE_OFF -> "off"
        CameraCharacteristics.CONTROL_AWB_MODE_AUTO -> "auto"
        CameraCharacteristics.CONTROL_AWB_MODE_INCANDESCENT -> "incandescent"
        CameraCharacteristics.CONTROL_AWB_MODE_FLUORESCENT -> "fluorescent"
        CameraCharacteristics.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "warm_fluorescent"
        CameraCharacteristics.CONTROL_AWB_MODE_DAYLIGHT -> "daylight"
        CameraCharacteristics.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "cloudy"
        CameraCharacteristics.CONTROL_AWB_MODE_TWILIGHT -> "twilight"
        CameraCharacteristics.CONTROL_AWB_MODE_SHADE -> "shade"
        else -> "mode_$mode"
    }

    private fun awbModeValue(mode: String): Int = when (mode) {
        "off" -> CameraCharacteristics.CONTROL_AWB_MODE_OFF
        "incandescent" -> CameraCharacteristics.CONTROL_AWB_MODE_INCANDESCENT
        "fluorescent" -> CameraCharacteristics.CONTROL_AWB_MODE_FLUORESCENT
        "warm_fluorescent" -> CameraCharacteristics.CONTROL_AWB_MODE_WARM_FLUORESCENT
        "daylight" -> CameraCharacteristics.CONTROL_AWB_MODE_DAYLIGHT
        "cloudy" -> CameraCharacteristics.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        "twilight" -> CameraCharacteristics.CONTROL_AWB_MODE_TWILIGHT
        "shade" -> CameraCharacteristics.CONTROL_AWB_MODE_SHADE
        else -> CameraCharacteristics.CONTROL_AWB_MODE_AUTO
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (modesVerified) return
            modesVerified = true
            checkApplied("EDGE_MODE", edgeMode, result.get(CaptureResult.EDGE_MODE), edgeModeValue(edgeMode))
            checkApplied(
                "NOISE_REDUCTION_MODE",
                noiseReduction,
                result.get(CaptureResult.NOISE_REDUCTION_MODE),
                noiseReductionValue(noiseReduction),
            )
            checkApplied(
                "ANTIBANDING",
                antibanding,
                result.get(CaptureResult.CONTROL_AE_ANTIBANDING_MODE),
                antibandingValue(antibanding),
            )
            if ("off" in hotPixelModes) {
                checkApplied(
                    "HOT_PIXEL_MODE",
                    "off",
                    result.get(CaptureResult.HOT_PIXEL_MODE),
                    CameraCharacteristics.HOT_PIXEL_MODE_OFF,
                )
            }
            if ("off" in shadingModes) {
                checkApplied(
                    "SHADING_MODE",
                    "off",
                    result.get(CaptureResult.SHADING_MODE),
                    CameraCharacteristics.SHADING_MODE_OFF,
                )
            }
            if ("off" in aberrationModes) {
                checkApplied(
                    "ABERRATION_MODE",
                    "off",
                    result.get(CaptureResult.COLOR_CORRECTION_ABERRATION_MODE),
                    CameraCharacteristics.COLOR_CORRECTION_ABERRATION_MODE_OFF,
                )
            }
            if ("off" in distortionModes) {
                checkApplied(
                    "DISTORTION_CORRECTION_MODE",
                    "off",
                    result.get(CaptureResult.DISTORTION_CORRECTION_MODE),
                    CameraCharacteristics.DISTORTION_CORRECTION_MODE_OFF,
                )
            }
        }
    }

    private fun checkApplied(name: String, requested: String, applied: Int?, expected: Int) {
        if (applied == null || applied == expected) return
        android.util.Log.w(
            "SlateCam",
            "HAL ignored $name=$requested (applied=$applied, requested=$expected)",
        )
    }

    private fun edgeModeName(value: Int): String = when (value) {
        CameraCharacteristics.EDGE_MODE_OFF -> "off"
        CameraCharacteristics.EDGE_MODE_FAST -> "fast"
        CameraCharacteristics.EDGE_MODE_HIGH_QUALITY -> "high_quality"
        CameraCharacteristics.EDGE_MODE_ZERO_SHUTTER_LAG -> "zero_shutter_lag"
        else -> "mode_$value"
    }

    private fun edgeModeValue(mode: String): Int = when (mode) {
        "off" -> CameraCharacteristics.EDGE_MODE_OFF
        "fast" -> CameraCharacteristics.EDGE_MODE_FAST
        "high_quality" -> CameraCharacteristics.EDGE_MODE_HIGH_QUALITY
        "zero_shutter_lag" -> CameraCharacteristics.EDGE_MODE_ZERO_SHUTTER_LAG
        else -> CameraCharacteristics.EDGE_MODE_OFF
    }

    private fun noiseReductionName(value: Int): String = when (value) {
        CameraCharacteristics.NOISE_REDUCTION_MODE_OFF -> "off"
        CameraCharacteristics.NOISE_REDUCTION_MODE_FAST -> "fast"
        CameraCharacteristics.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "high_quality"
        CameraCharacteristics.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> "zero_shutter_lag"
        else -> "mode_$value"
    }

    private fun noiseReductionValue(mode: String): Int = when (mode) {
        "off" -> CameraCharacteristics.NOISE_REDUCTION_MODE_OFF
        "fast" -> CameraCharacteristics.NOISE_REDUCTION_MODE_FAST
        "high_quality" -> CameraCharacteristics.NOISE_REDUCTION_MODE_HIGH_QUALITY
        "zero_shutter_lag" -> CameraCharacteristics.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG
        else -> CameraCharacteristics.NOISE_REDUCTION_MODE_OFF
    }

    private fun antibandingName(value: Int): String = when (value) {
        CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_OFF -> "off"
        CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_50HZ -> "50hz"
        CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_60HZ -> "60hz"
        CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_AUTO -> "auto"
        else -> "mode_$value"
    }

    private fun antibandingValue(mode: String): Int = when (mode) {
        "off" -> CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_OFF
        "50hz" -> CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_50HZ
        "60hz" -> CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_60HZ
        else -> CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_AUTO
    }

    private fun hotPixelName(value: Int): String = when (value) {
        CameraCharacteristics.HOT_PIXEL_MODE_OFF -> "off"
        CameraCharacteristics.HOT_PIXEL_MODE_FAST -> "fast"
        CameraCharacteristics.HOT_PIXEL_MODE_HIGH_QUALITY -> "high_quality"
        else -> "mode_$value"
    }

    private fun shadingName(value: Int): String = when (value) {
        CameraCharacteristics.SHADING_MODE_OFF -> "off"
        CameraCharacteristics.SHADING_MODE_FAST -> "fast"
        CameraCharacteristics.SHADING_MODE_HIGH_QUALITY -> "high_quality"
        else -> "mode_$value"
    }

    private fun aberrationName(value: Int): String = when (value) {
        CameraCharacteristics.COLOR_CORRECTION_ABERRATION_MODE_OFF -> "off"
        CameraCharacteristics.COLOR_CORRECTION_ABERRATION_MODE_FAST -> "fast"
        CameraCharacteristics.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY -> "high_quality"
        else -> "mode_$value"
    }

    private fun distortionName(value: Int): String = when (value) {
        CameraCharacteristics.DISTORTION_CORRECTION_MODE_OFF -> "off"
        CameraCharacteristics.DISTORTION_CORRECTION_MODE_FAST -> "fast"
        CameraCharacteristics.DISTORTION_CORRECTION_MODE_HIGH_QUALITY -> "high_quality"
        else -> "mode_$value"
    }

    companion object {
        /** Size supported by every listed camera, preferring 720p. */
        fun chooseCommonPreviewSize(context: Context, cameraIds: List<String>): Size {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val common = cameraIds.mapNotNull { id ->
                runCatching {
                    manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                        ?.map { it.width to it.height }
                        ?.toSet()
                }.getOrNull()
            }.reduceOrNull { a, b -> a intersect b } ?: emptySet()

            for (preferred in listOf(1280 to 720, 1920 to 1080, 640 to 480)) {
                if (preferred in common) return Size(preferred.first, preferred.second)
            }
            return common.minByOrNull { it.first * it.second }
                ?.let { Size(it.first, it.second) }
                ?: Size(1280, 720)
        }

        /**
         * Picks a 16:9 preview size up to 1080p from the back camera's stream map.
         * Falls back to the largest size under 1080p, then 720p.
         */
        fun choosePreviewSize(context: Context, preferredCameraId: String? = null): Size {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = runCatching { manager.cameraIdList }.getOrDefault(emptyArray())
            val cameraId = preferredCameraId ?: ids.firstOrNull { id ->
                runCatching {
                    manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                }.getOrDefault(false)
            } ?: ids.firstOrNull() ?: return Size(1280, 720)

            val map = runCatching {
                manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            }.getOrNull() ?: return Size(1280, 720)

            val sizes = runCatching {
                map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            }.getOrNull() ?: return Size(1280, 720)

            val bounded = sizes.filter { it.width <= 1920 && it.height <= 1080 }
            return bounded.filter { it.width * 9 == it.height * 16 }
                .maxByOrNull { it.width * it.height }
                ?: bounded.maxByOrNull { it.width * it.height }
                ?: Size(1280, 720)
        }
    }
}
