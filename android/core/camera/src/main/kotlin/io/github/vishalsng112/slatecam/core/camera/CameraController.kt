package io.github.vishalsng112.slatecam.core.camera

import android.content.Context
import android.graphics.Rect
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
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import io.github.vishalsng112.slatecam.core.common.FlickerGrid
import io.github.vishalsng112.slatecam.core.common.ShutterAngle
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/** Snapshot of camera state. Kotlin owns this; Dart only renders it. */
data class CameraUiState(
    val isOpen: Boolean,
    val error: String? = null,
    val cameraId: String = "0",
    val cameraIds: List<String> = emptyList(),
    val cameraLabels: List<String> = emptyList(),
    val iso: Int,
    val isoMin: Int,
    val isoMax: Int,
    val shutterNs: Long,
    val shutterMinNs: Long,
    val shutterMaxNs: Long,
    /**
     * Flicker-safe exposure step in nanoseconds for the active anti-banding
     * mode, or `null` when it is off and the shutter may move continuously.
     */
    val shutterStepNs: Long? = null,
    val focusDiopters: Float,
    val focusMin: Float,
    val focusMax: Float,
    val focusShiftSpeed: Float = 2f,
    val whiteBalanceMode: String,
    val whiteBalanceModes: List<String>,
    /** Colour temperature for manual white balance, in kelvin. */
    val whiteBalanceKelvin: Int = ManualWhiteBalance.DEFAULT_KELVIN,
    /** Green/magenta trim for manual white balance, -100..100. */
    val whiteBalanceTint: Int = ManualWhiteBalance.DEFAULT_TINT,
    /** True when the sensor advertises AWB off, so manual WB can be offered. */
    val manualWhiteBalanceSupported: Boolean = false,
    val autoExposure: Boolean,
    val autoFocus: Boolean,
    val autoFocusSupported: Boolean = true,
    val focusMode: String = "continuous_video",
    val focusModes: List<String> = emptyList(),
    val focusScenario: String = FocusScenario.WEDDING.wireValue,
    val focusScenarios: List<String> = FocusScenario.wireValues,
    /** 0 = sticky, 1 = quick to refocus when the scene changes. */
    val focusShiftSensitivity: Float = 0.5f,
    /** 0 = slow cinematic rack, 1 = quick rack. */
    val focusTransitionSpeed: Float = 0.5f,
    val previewWidth: Int,
    val previewHeight: Int,
    val previewRotationDegrees: Int,
    /** Rotation the GL shader must apply to make the sensor image upright. */
    val glRotationDegrees: Int = 0,
    /** Fixed landscape rotation for the encoder, independent of the display. */
    val encoderRotationDegrees: Int = 0,
    /** Clockwise MP4 orientation hint; native portrait encoding leaves it 0. */
    val recordingOrientationHint: Int = 0,
    /** Quarter turns the viewfinder applies to match the display orientation. */
    val previewQuarterTurns: Int = 0,
    val zoomRatio: Float = 1f,
    val zoomMin: Float = 1f,
    val zoomMax: Float = 1f,
    /** Physical sensor chosen by a logical multi-camera handoff. */
    val activePhysicalCameraId: String? = null,
    val isTenBit: Boolean = false,
    val shutterAngle: Float = 0f,
    val frameRate: Int = 30,
    val edgeMode: String = "off",
    val edgeModes: List<String> = emptyList(),
    val noiseReduction: String = "off",
    val noiseReductionModes: List<String> = emptyList(),
    val antibanding: String = "auto",
    val antibandingModes: List<String> = emptyList(),
    /** Lens-shading (vignette) correction preference for the active sensor. */
    val shadingCorrection: Boolean = true,
    /** True when the sensor advertises FAST or HIGH_QUALITY shading. */
    val shadingSupported: Boolean = false,
    val oisSupported: Boolean = false,
    val oisEnabled: Boolean = false,
    val eisSupported: Boolean = false,
    val eisEnabled: Boolean = false,
    val exposureMode: String = ExposureMode.PROGRAM.wireValue,
    val exposureModes: List<String> = ExposureMode.wireValues,
    val meteringMode: String = MeteringMode.MULTI.wireValue,
    val meteringModes: List<String> = MeteringMode.wireValues,
    /** 0 = slowest, 1 = fastest exposure response. */
    val meteringSpeed: Float = 0.5f,
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
    private var currentCharacteristics: CameraCharacteristics? = null
    private var session: CameraCaptureSession? = null
    private var builder: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null

    private var previewSize = Size(1920, 1080)
    private var open = false
    private var errorMessage: String? = null
    private var cameraId = "0"
    private var preferredCameraId: String? = null
    private var activePhysicalCameraId: String? = null
    private var availableCameraIds: List<String> = emptyList()
    private var availableCameraLabels: List<String> = emptyList()

    private var isoMin = 100
    private var isoMax = 3200
    private var shutterMinNs = 1_000L
    private var shutterMaxNs = 1_000_000_000L
    private val focus = FocusController { ensureFocusRamp() }
    private var wbModes: List<String> = listOf("auto")
    private var fpsRange: Range<Int>? = null

    private var iso = 400
    private var shutterNs = 1_000_000_000L / 50
    private var wbMode = "auto"
    private var wbKelvin = ManualWhiteBalance.DEFAULT_KELVIN
    private var wbTint = ManualWhiteBalance.DEFAULT_TINT
    private var manualWbSupported = false
    private var manualPreset = "daylight"
    private var manualPresetCct = 5500
    private var autoExposure = true
    private var exposureMode = ExposureMode.PROGRAM
    private val exposureController = ExposureController()
    private var meteringMode = MeteringMode.MULTI
    private var meteringSpeed = 0.5f
    private var lastMeteringNs = 0L
    private var lastMeterLogMs = 0L
    private var aeCompensationSteps = 0
    private var aeCompensationMin = 0
    private var aeCompensationMax = 0
    private var aeCompensationStepEv = 0f
    private var aeCompensationAccumulator = 0f
    private var lastPCompensationMs = 0L
    private var lastFocusLogMs = 0L
    private var lastWbLog: String? = null
    private var focusRampScheduled = false
    private var lastFocusRampMs = 0L
    private var rotationDegrees = 0
    private var glRotationDegrees = 0
    private var encoderRotationDegrees = 0
    private var recordingOrientationHint = 0
    private var previewQuarterTurns = 0
    private var initialized = false
    private var wantsTenBit = false
    private var tenBitActive = false
    private var retriedStandard = false

    private var edgeModes: List<String> = emptyList()
    private var noiseReductionModes: List<String> = emptyList()
    private var antibandingModes: List<String> = emptyList()
    private var hotPixelModes: List<String> = emptyList()
    private var shadingModes: List<String> = emptyList()
    private var shadingCorrection = true
    private var shadingSupported = false
    private var oisModes: List<String> = emptyList()
    private var oisSupported = false
    private var oisEnabled = true
    private var videoStabilizationModes: List<String> = emptyList()
    private var eisSupported = false
    private var eisEnabled = true
    private var aberrationModes: List<String> = emptyList()
    private var distortionModes: List<String> = emptyList()

    private var edgeMode = "off"
    private var noiseReduction = "off"
    private var antibanding = "auto"
    private var modesVerified = false
    private var frameRate = 30
    private var timestampRealtime = false

    private val zoomRamp = SmoothZoom()
    private var zoomAnimating = false
    private var lastZoomTickMs = 0L
    private var zoomMin = 1f
    private var zoomMax = 1f
    private var zoomByRatioControl = false
    private var activeArray: Rect? = null
    private var sensorOrientation = 0
    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var maxAfRegions = 0
    private var afRegion: Rect? = null

    private var targetFps = 30

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
                readCameraList(manager)
                val selectedId = cameraIdOverride
                    ?: preferredCameraId
                    ?: selectCamera(manager)
                val switchingCamera = selectedId != cameraId
                cameraId = selectedId
                preferredCameraId = selectedId
                activePhysicalCameraId = null
                val characteristics = manager.getCameraCharacteristics(selectedId)
                currentCharacteristics = characteristics
                readCapabilities(characteristics)
                if (switchingCamera) {
                    zoomRamp.snapTo(1f)
                } else {
                    zoomRamp.stop()
                }

                manager.openCamera(selectedId, callbackExecutor, object : CameraDevice.StateCallback() {
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

    /** Re-emit the current state after the bridge changes which controller is active. */
    fun refreshState() {
        handler.post { emit() }
    }

    fun setIso(value: Int) {
        handler.post {
            iso = value.coerceIn(isoMin, isoMax)
            exposureMode = ExposureMode.MANUAL
            autoExposure = false
            applyAndEmit()
        }
    }

    /**
     * Exposure grid for the active anti-banding mode. Manual exposure ignores
     * Camera2's anti-banding control, so the app must keep whole flicker
     * periods itself.
     */
    private fun shutterStepNs(): Long? = FlickerGrid.stepNs(antibanding)

    private fun snapShutter(ns: Long): Long =
        FlickerGrid.snap(ns, shutterStepNs(), shutterMinNs, shutterMaxNs)

    fun setShutterNs(value: Long) {
        handler.post {
            shutterNs = snapShutter(value)
            android.util.Log.i(
                "SlateCamExposure",
                "shutter requested=$value applied=$shutterNs " +
                    "antibanding=$antibanding stepNs=${shutterStepNs()}",
            )
            // S keeps the shutter fixed and lets the ISO loop do the work;
            // changing the shutter elsewhere is a manual exposure change.
            if (exposureMode != ExposureMode.SHUTTER_PRIORITY) {
                exposureMode = ExposureMode.MANUAL
            }
            autoExposure = false
            lastMeteringNs = 0L
            applyAndEmit()
        }
    }

    fun setShutterAngle(degrees: Float) {
        handler.post {
            shutterNs = snapShutter(ShutterAngle.exposureNs(degrees, frameRate))
            if (exposureMode != ExposureMode.SHUTTER_PRIORITY) {
                exposureMode = ExposureMode.MANUAL
            }
            autoExposure = false
            lastMeteringNs = 0L
            applyAndEmit()
        }
    }

    /**
     * Selects P, S, or M. Frames that arrive with an unknown value stay in P.
     */
    fun setExposureMode(mode: String) {
        val selected = ExposureMode.fromWire(mode)
        handler.post {
            exposureMode = selected
            autoExposure = selected == ExposureMode.PROGRAM
            lastMeteringNs = 0L
            // The HAL's last automatic exposure was not grid-aligned; snap it
            // before S or M starts holding it.
            if (!autoExposure) shutterNs = snapShutter(shutterNs)
            if (selected == ExposureMode.PROGRAM) {
                // Start from a neutral bias; the meter takes over from there.
                aeCompensationSteps = 0
                aeCompensationAccumulator = 0f
            }
            applyAndEmit()
        }
    }

    /**
     * Switches to another camera. Exposed IDs are validated; a probe-verified
     * hidden lens may be passed with [allowUnlisted] so it can be tested.
     */
    fun setCameraId(id: String, allowUnlisted: Boolean = false) {
        handler.post {
            if (id == cameraId) return@post
            if (!allowUnlisted && id !in availableCameraIds) return@post

            preferredCameraId = id
            val target = previewSurface
            val width = previewSize.width
            val height = previewSize.height
            val tenBit = wantsTenBit
            if (open && target != null) {
                open(target, width, height, tenBit, id)
            } else {
                cameraId = id
                emit()
            }
        }
    }

    /**
     * Sets a new zoom target. The applied ratio eases toward it on the camera
     * thread instead of jumping in one capture request, so slider steps and
     * pinch deltas both produce a continuous push-in / pull-out.
     */
    fun setZoomRatio(ratio: Float) {
        handler.post {
            if (!zoomRamp.retarget(ratio)) {
                if (zoomAnimating) {
                    stopZoomRamp()
                    applyAndEmit()
                }
                return@post
            }
            if (!zoomAnimating) {
                zoomAnimating = true
                lastZoomTickMs = SystemClock.elapsedRealtime()
                handler.postDelayed(zoomRampTick, ZOOM_TICK_MS)
            }
        }
    }

    /**
     * One camera-frame step of the zoom ramp. 33 ms matches the 30 fps stream,
     * so the recorded clip sees an intermediate zoom on every frame during the
     * move.
     */
    private val zoomRampTick = object : Runnable {
        override fun run() {
            if (!zoomAnimating) return
            if (!open) {
                zoomAnimating = false
                return
            }
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastZoomTickMs) / 1000f
            lastZoomTickMs = now
            val moving = zoomRamp.advance(dt)
            zoomAnimating = moving
            applyAndEmit()
            if (moving) handler.postDelayed(this, ZOOM_TICK_MS)
        }
    }

    private fun stopZoomRamp() {
        zoomAnimating = false
        handler.removeCallbacks(zoomRampTick)
        zoomRamp.stop()
    }

    /** Frame rate for the next session; reopens only when a size changes. */
    fun setFrameRate(fps: Int) {
        targetFps = fps.coerceIn(1, 240)
        handler.post {
            val characteristics = currentCharacteristics ?: return@post
            fpsRange = pickFpsRange(characteristics, targetFps)
            frameRate = fpsRange?.upper ?: targetFps
            applyAndEmit()
        }
    }

    /** Reopens the camera with a new size, frame rate and bit-depth request. */
    fun configure(
        width: Int,
        height: Int,
        fps: Int,
        tenBit: Boolean,
        cameraIdOverride: String? = null,
    ) {
        targetFps = fps.coerceIn(1, 240)
        handler.post {
            previewSize = Size(width, height)
            wantsTenBit = tenBit
            val target = previewSurface
            if (open && target != null) {
                open(target, width, height, tenBit, cameraIdOverride ?: cameraId)
            } else {
                emit()
            }
        }
    }

    /**
     * Re-opens the current camera with a different dynamic-range request.
     *
     * Capability is verified per camera, so switching cameras can change whether
     * HLG10 may be requested. The caller decides; this only applies the answer.
     */
    fun setTenBit(enabled: Boolean) {
        handler.post {
            if (wantsTenBit == enabled) return@post
            val target = previewSurface ?: return@post
            open(target, previewSize.width, previewSize.height, enabled, cameraId)
        }
    }

    /**
     * Feeds the GPU luma grid into the metering loop.
     *
     * P applies the error as AE exposure compensation, S as ISO, and M leaves
     * exposure untouched. Safe to call from the GL thread.
     */
    fun submitMetering(lumaGrid: FloatArray, gridWidth: Int, gridHeight: Int) {
        if (gridWidth <= 0 || gridHeight <= 0 ||
            lumaGrid.size < gridWidth * gridHeight
        ) {
            return
        }
        handler.post {
            if (session == null || builder == null) return@post
            if (exposureMode == ExposureMode.MANUAL) return@post
            val sample = ExposureMeter.measure(
                meteringMode,
                lumaGrid,
                gridWidth,
                gridHeight,
            ) ?: return@post

            val now = SystemClock.elapsedRealtimeNanos()
            val elapsed = if (lastMeteringNs == 0L) {
                DEFAULT_METERING_SECONDS
            } else {
                ((now - lastMeteringNs) / 1_000_000_000f)
            }
            lastMeteringNs = now

            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastMeterLogMs >= 1_000L) {
                lastMeterLogMs = nowMs
                android.util.Log.i(
                    "SlateCamMeter",
                    "mode=${meteringMode.wireValue} " +
                        "measured=${sample.measuredLuma} target=${sample.targetLuma} " +
                        "ev=${sample.errorEv} exposure=${exposureMode.wireValue} " +
                        "iso=$iso comp=$aeCompensationSteps",
                )
            }

            if (exposureMode == ExposureMode.PROGRAM) {
                if (!autoExposure || aeCompensationMax <= aeCompensationMin ||
                    aeCompensationStepEv <= 0f
                ) {
                    return@post
                }
                // The software focus search changes sharpness; do not also
                // chase brightness with the AE bias during that search.
                if (focus.isSearching) return@post
                // P has the HAL AE and our bias in the same feedback path. At
                // 15 Hz analysis the two loops can fight, so update the bias at
                // 5 Hz, use a wider dead band, and move at most one step.
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastPCompensationMs < 200L) return@post
                lastPCompensationMs = nowMs
                if (kotlin.math.abs(sample.errorEv) < P_DEAD_BAND_EV) {
                    aeCompensationAccumulator = 0f
                    return@post
                }
                val correctionEv = exposureController.correctionEv(
                    sample,
                    P_UPDATE_SECONDS,
                    meteringSpeed,
                )
                if (correctionEv == 0f) return@post
                aeCompensationAccumulator += correctionEv / aeCompensationStepEv
                val deltaSteps = aeCompensationAccumulator
                    .roundToInt()
                    .coerceIn(-1, 1)
                if (deltaSteps == 0) return@post
                val next = (aeCompensationSteps + deltaSteps)
                    .coerceIn(aeCompensationMin, aeCompensationMax)
                aeCompensationAccumulator -= deltaSteps
                if (next != aeCompensationSteps) {
                    aeCompensationSteps = next
                    applyAndEmit()
                } else {
                    // At the compensation limit; do not accumulate further.
                    aeCompensationAccumulator = 0f
                }
                return@post
            }

            val next = exposureController.nextIso(
                sample,
                iso,
                isoMin,
                isoMax,
                elapsed,
                meteringSpeed,
            )
            if (next != iso) {
                iso = next
                applyAndEmit()
            }
        }
    }

    /**
     * Focuses at a point. Coordinates are camera-buffer normalised (from the
     * renderer's viewfinder-to-buffer transform). Hardware AF gets an AF region
     * and a trigger; SAF resets the search around the new ROI.
     */
    fun setFocusPoint(bufferX: Float, bufferY: Float) {
        handler.post {
            lastMeteringNs = 0L
            when (focus.onPointFocus(SystemClock.elapsedRealtime())) {
                FocusController.PointFocus.SOFTWARE -> emit()
                FocusController.PointFocus.IGNORE -> return@post
                FocusController.PointFocus.HARDWARE -> {
                    if (maxAfRegions <= 0) return@post
                    afRegion = activeRegionFor(bufferX, bufferY) ?: return@post
                    applyAndEmit()
                    // Continuous modes follow the new region themselves.
                    // Triggering a full scan on top of CAF makes it hunt.
                    if (!focus.isContinuous) triggerAf()
                }
            }
        }
    }

    /**
     * Converts buffer coordinates to the active-array AF rectangle. The stream
     * is a centred crop of the active array (16:9 from a 4:3 sensor, for
     * example), shrunk by the current zoom.
     */
    private fun activeRegionFor(bufferX: Float, bufferY: Float): Rect? {
        val array = activeArray ?: return null
        if (previewSize.width <= 0 || previewSize.height <= 0) return null
        val streamAspect = previewSize.width.toFloat() / previewSize.height
        val activeAspect = array.width().toFloat() / array.height()
        val base = if (streamAspect > activeAspect) {
            val height = (array.width() / streamAspect).roundToInt().coerceAtLeast(1)
            Rect(
                array.left,
                array.centerY() - height / 2,
                array.right,
                array.centerY() + height / 2,
            )
        } else {
            val width = (array.height() * streamAspect).roundToInt().coerceAtLeast(1)
            Rect(
                array.centerX() - width / 2,
                array.top,
                array.centerX() + width / 2,
                array.bottom,
            )
        }
        val zoom = zoomRamp.value.coerceAtLeast(1f)
        val cropWidth = (base.width() / zoom).roundToInt().coerceAtLeast(1)
        val cropHeight = (base.height() / zoom).roundToInt().coerceAtLeast(1)
        val crop = Rect(
            base.centerX() - cropWidth / 2,
            base.centerY() - cropHeight / 2,
            base.centerX() + cropWidth / 2,
            base.centerY() + cropHeight / 2,
        )
        // Camera buffer texture coordinates are GL-style (origin bottom-left).
        val x = (crop.left + bufferX.coerceIn(0f, 1f) * crop.width()).roundToInt()
        val y = (crop.top + (1f - bufferY.coerceIn(0f, 1f)) * crop.height()).roundToInt()
        val half = (kotlin.math.min(crop.width(), crop.height()) * AF_REGION_FRACTION / 2f)
            .roundToInt()
            .coerceAtLeast(4)
        return Rect(
            (x - half).coerceIn(array.left, array.right),
            (y - half).coerceIn(array.top, array.bottom),
            (x + half).coerceIn(array.left, array.right),
            (y + half).coerceIn(array.top, array.bottom),
        )
    }

    /** One-shot AF trigger without disturbing the repeating request. */
    private fun triggerAf() {
        val request = builder ?: return
        val captureSession = session ?: return
        runCatching {
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            captureSession.capture(request.build(), null, handler)
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            captureSession.setRepeatingRequest(request.build(), captureCallback, handler)
        }
    }

    fun setFocusDiopters(value: Float) {
        handler.post {
            afRegion = null
            if (focus.selectManual(value, SystemClock.elapsedRealtime())) {
                applyAndEmit()
            }
        }
    }

    private fun ensureFocusRamp() {
        if (!focus.isRamping || focusRampScheduled) return
        focusRampScheduled = true
        lastFocusRampMs = SystemClock.elapsedRealtime()
        handler.postDelayed(focusRampTick, FOCUS_RAMP_MS)
    }

    private val focusRampTick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val seconds = ((now - lastFocusRampMs).coerceAtLeast(1L)) / 1000f
            lastFocusRampMs = now
            val moving = focus.advanceRamp(seconds, now)
            applyAndEmit()
            if (moving) {
                handler.postDelayed(this, FOCUS_RAMP_MS)
            } else {
                focusRampScheduled = false
            }
        }
    }

    fun setWhiteBalanceMode(mode: String) {
        handler.post {
            val manual = mode == ManualWhiteBalance.WIRE_VALUE
            if (manual.not() && mode !in wbModes) return@post
            if (manual && !manualWbSupported) return@post
            // Picking a preset moves the temperature/tint controls to that
            // preset's values so the sliders always describe the active look;
            // touching them then switches to manual.
            val preset = ManualWhiteBalance.presetForMode(mode)
            val presetValues = preset?.let { it.cct to ManualWhiteBalance.DEFAULT_TINT }
            val unchanged = wbMode == mode &&
                (presetValues == null || (wbKelvin to wbTint) == presetValues)
            if (unchanged) return@post
            if (presetValues != null) {
                wbKelvin = presetValues.first
                wbTint = presetValues.second
            }
            wbMode = mode
            applyAndEmit()
        }
    }

    /**
     * Sets the manual colour temperature and tint. Selecting manual white
     * balance is implicit: the request leaves AWB for fixed channel gains.
     */
    fun setManualWhiteBalance(kelvin: Int, tint: Int) {
        handler.post {
            wbKelvin = ManualWhiteBalance.clampKelvin(kelvin)
            wbTint = ManualWhiteBalance.clampTint(tint)
            val previousPreset = manualPreset
            if (!manualWbSupported || !selectManualPreset()) {
                emit()
                return@post
            }
            val changed = wbMode != ManualWhiteBalance.WIRE_VALUE ||
                previousPreset != manualPreset
            wbMode = ManualWhiteBalance.WIRE_VALUE
            if (changed) applyAndEmit() else emit()
        }
    }

    /**
     * Picks the preset nearest [wbKelvin]. Returns false when the sensor offers
     * no preset, which is the only case that disables manual white balance.
     */
    private fun selectManualPreset(): Boolean {
        val preset = ManualWhiteBalance.presetFor(wbKelvin, wbModes) ?: return false
        manualPreset = preset.mode
        manualPresetCct = preset.cct
        return true
    }

    /**
     * Restores a persisted white balance choice before the camera is opened.
     * Support is resolved from the characteristics once they are known.
     */
    fun restoreWhiteBalance(mode: String, kelvin: Int, tint: Int) {
        handler.post {
            wbKelvin = ManualWhiteBalance.clampKelvin(kelvin)
            wbTint = ManualWhiteBalance.clampTint(tint)
            wbMode = mode
            emit()
        }
    }

    fun setAutoExposure() {
        handler.post {
            exposureMode = ExposureMode.PROGRAM
            autoExposure = true
            lastMeteringNs = 0L
            aeCompensationSteps = 0
            aeCompensationAccumulator = 0f
            applyAndEmit()
        }
    }

    fun setMeteringMode(mode: String) {
        val selected = MeteringMode.fromWire(mode)
        handler.post {
            if (meteringMode == selected) return@post
            meteringMode = selected
            lastMeteringNs = 0L
            aeCompensationAccumulator = 0f
            emit()
        }
    }

    fun setMeteringSpeed(speed: Float) {
        handler.post {
            val clamped = speed.coerceIn(0f, 1f)
            if (meteringSpeed == clamped) return@post
            meteringSpeed = clamped
            emit()
        }
    }

    fun setFocusMode(mode: String) {
        handler.post {
            if (!focus.selectMode(mode, SystemClock.elapsedRealtime())) return@post
            if (mode == FocusMode.OFF || mode == FocusMode.SOFTWARE) afRegion = null
            applyAndEmit()
        }
    }

    /** Restores the persisted operator choice before the lens is opened. */
    fun restoreFocusMode(mode: String) {
        focus.restorePreferredMode(mode)
    }

    fun setFocusScenario(scenario: String) {
        handler.post {
            if (!focus.selectScenario(scenario, SystemClock.elapsedRealtime())) {
                emit()
                return@post
            }
            applyAndEmit()
        }
    }

    /** Restores persisted SAF values without switching the active mode. */
    fun restoreFocusSettings(scenario: String, sensitivity: Float, speed: Float) {
        handler.post {
            focus.restoreScenario(scenario, sensitivity, speed)
            emit()
        }
    }

    fun setFocusShiftSensitivity(value: Float) {
        handler.post {
            focus.setShiftSensitivity(value)
            emit()
        }
    }

    fun setFocusTransitionSpeed(value: Float) {
        handler.post {
            focus.setTransitionSpeed(value)
            emit()
        }
    }

    /** Feeds the GPU sharpness score into the software focus engine. */
    fun submitFocus(score: Float) {
        handler.post {
            if (session == null || builder == null) return@post
            val now = SystemClock.elapsedRealtime()
            val changed = focus.onSharpness(score, now)
            if (now - lastFocusLogMs >= 1_000L || changed) {
                lastFocusLogMs = now
                android.util.Log.i(
                    "SlateCamFocus",
                    "score=$score diopters=${focus.diopters} " +
                        "target=${focus.targetDiopters} search=${focus.isSearching} " +
                        "sens=${focus.shiftSensitivity} speed=${focus.transitionSpeed}",
                )
            }
        }
    }

    fun setOis(enabled: Boolean) {
        handler.post {
            if (oisEnabled == enabled) return@post
            oisEnabled = enabled
            applyAndEmit()
        }
    }

    fun setEis(enabled: Boolean) {
        handler.post {
            if (eisEnabled == enabled) return@post
            eisEnabled = enabled
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
            // Moving to another mains frequency must not leave a now-unsafe
            // shutter in place. In P the HAL owns exposure, so leave the
            // readback untouched.
            if (!autoExposure) shutterNs = snapShutter(shutterNs)
            android.util.Log.i(
                "SlateCamExposure",
                "antibanding=$mode stepNs=${shutterStepNs()} shutterNs=$shutterNs",
            )
            applyAndEmit()
        }
    }

    /**
     * Turns lens-shading (vignette) correction on or off for the active sensor.
     * The preference is applied to the controller only; the bridge persists it
     * per camera ID so each lens remembers its own choice.
     */
    fun setShadingCorrection(enabled: Boolean) {
        handler.post {
            if (shadingCorrection == enabled) return@post
            shadingCorrection = enabled
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

    private fun readCameraList(manager: CameraManager) {
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        availableCameraIds = ids
        availableCameraLabels = ids.map { id ->
            val facing = runCatching {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)
            }.getOrNull()
            val facingName = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                else -> "camera"
            }
            "$id · $facingName"
        }
    }

    private fun readCapabilities(characteristics: CameraCharacteristics) {
        val sens = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        isoMin = sens?.lower ?: 100
        isoMax = sens?.upper ?: 3200

        val exposure = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        shutterMinNs = exposure?.lower ?: 1_000L
        shutterMaxNs = exposure?.upper ?: 1_000_000_000L

        // P-mode metering is applied as an EV bias on top of the HAL AE. The
        // range and step are per device.
        val compensation = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        aeCompensationStepEv = characteristics
            .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?.toFloat()
            ?.takeIf { it > 0f }
            ?: 0f
        if (compensation != null && aeCompensationStepEv > 0f) {
            aeCompensationMin = compensation.lower
            aeCompensationMax = compensation.upper
        } else {
            aeCompensationMin = 0
            aeCompensationMax = 0
        }
        aeCompensationSteps = aeCompensationSteps.coerceIn(aeCompensationMin, aeCompensationMax)
        aeCompensationAccumulator = 0f

        focus.bind(
            halModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.map { FocusMode.fromHal(it) }
                ?.filterNotNull()
                ?.distinct()
                ?: emptyList(),
            minimumFocusDistance = characteristics
                .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                ?: 0f,
        )

        oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.map(::oisModeName)
            ?.distinct()
            ?: emptyList()
        oisSupported = "on" in oisModes
        videoStabilizationModes =
            characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.map(::videoStabilizationName)
                ?.distinct()
                ?: emptyList()
        eisSupported = "on" in videoStabilizationModes

        wbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?.map(::awbModeName)
            ?.distinct()
            ?: listOf("auto")
        // Manual white balance is anchored on a HAL white-balance preset, so it
        // needs at least one preset besides auto/off.
        manualWbSupported = selectManualPreset()

        // Ranges are always refreshed; user values survive a close/reopen so
        // backgrounding the app does not silently undo manual settings.
        wbMode = ManualWhiteBalance.restoreMode(wbMode, wbModes, manualWbSupported)
        ManualWhiteBalance.presetForMode(wbMode)?.let { preset ->
            wbKelvin = preset.cct
            wbTint = ManualWhiteBalance.DEFAULT_TINT
        }
        if (!initialized) {
            iso = 400.coerceIn(isoMin, isoMax)
            shutterNs = (1_000_000_000L / 50).coerceIn(shutterMinNs, shutterMaxNs)
            focus.resetLensPosition()
            initialized = true
        } else {
            iso = iso.coerceIn(isoMin, isoMax)
            shutterNs = shutterNs.coerceIn(shutterMinNs, shutterMaxNs)
        }

        fpsRange = pickFpsRange(characteristics, targetFps)
        frameRate = fpsRange?.upper ?: targetFps
        timestampRealtime = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

        val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        zoomByRatioControl = zoomRange != null && zoomRange.upper > zoomRange.lower
        zoomMin = zoomRange?.lower ?: 1f
        zoomMax = zoomRange?.upper
            ?: (characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
        activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_BACK
        maxAfRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        afRegion = null
        zoomRamp.setBounds(zoomMin, zoomMax)

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
        shadingSupported = ShadingCorrection.isSupported(shadingModes)
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

        // Anti-banding is known only after the mode list above, so the
        // flicker-safe snap happens here rather than with the range clamping.
        if (!autoExposure) shutterNs = snapShutter(shutterNs)

        updateDisplayRotation(characteristics)
    }

    /**
     * Recomputes the display-relative preview rotation without reopening the
     * camera. Called when the activity rotates while the session stays alive.
     */
    fun refreshDisplayRotation() {
        handler.post {
            val characteristics = currentCharacteristics ?: return@post
            updateDisplayRotation(characteristics)
            emit()
        }
    }

    private fun updateDisplayRotation(characteristics: CameraCharacteristics) {
        rotationDegrees = displayRotationDegrees(characteristics)
        glRotationDegrees = glRotationFor(characteristics)
        val displayTurns = (recordingHintFor(characteristics) / 90) % 4
        val displayPortrait = currentDisplayRotation() == Surface.ROTATION_0 ||
            currentDisplayRotation() == Surface.ROTATION_180
        // 16:9 is the vertical-video family: in portrait it is delivered as
        // 9:16. 4:3 is the sensor's open gate and keeps its landscape frame
        // even when the phone is upright. This mirrors cinema-camera aspect
        // behaviour instead of flipping every ratio with the device.
        val verticalTake = displayPortrait && isWideStream()
        recordingOrientationHint = if (verticalTake) displayTurns * 90 else 0
        previewQuarterTurns = if (verticalTake) displayTurns else 0
        encoderRotationDegrees = glRotationDegrees
        android.util.Log.i(
            "SlateCam",
            "preview rotation=$rotationDegrees gl=$glRotationDegrees " +
                "encode=$encoderRotationDegrees hint=$recordingOrientationHint " +
                "sensor=${characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)} " +
                "display=${currentDisplayRotation()}",
        )
    }

    /** Clockwise rotation a player applies to make the take upright. */
    private fun recordingHintFor(characteristics: CameraCharacteristics): Int {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        val display = when (currentDisplayRotation()) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensor + display) % 360
        } else {
            (sensor - display + 360) % 360
        }
    }

    /**
     * The GL preview always renders the sensor-upright landscape frame, the
     * same pixels the encoder receives. Flutter applies the display quarter
     * turn from the MP4 orientation hint, so preview and playback share one
     * transform and there is no second pixel path.
     */
    private fun glRotationFor(characteristics: CameraCharacteristics): Int {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        return sensor % 360
    }

    /** True for the 16:9 video family, false for the 4:3 open gate. */
    private fun isWideStream(): Boolean =
        previewSize.width * 9 == previewSize.height * 16

    private fun currentDisplayRotation(): Int = runCatching {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.rotation
    }.getOrDefault(Surface.ROTATION_0)

    /** Preview rotation that matches the camera stream orientation on this platform. */
    private fun displayRotationDegrees(characteristics: CameraCharacteristics): Int {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        val display = currentDisplayRotation()
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            when (display) {
                Surface.ROTATION_90 -> (sensor + 90) % 360
                Surface.ROTATION_180 -> (sensor + 180) % 360
                Surface.ROTATION_270 -> (sensor + 270) % 360
                else -> sensor % 360
            }
        } else {
            // Back camera. These are the values the viewfinder has to apply to
            // the SurfaceTexture stream; they are not a mathematical derivation
            // from the display rotation alone because the camera transform matrix
            // already carries part of the orientation.
            when (display) {
                Surface.ROTATION_90 -> (360 - sensor) % 360
                Surface.ROTATION_180 -> (sensor + 180) % 360
                Surface.ROTATION_270 -> (sensor + 90) % 360
                else -> sensor % 360
            }
        }
    }

    private fun pickFpsRange(
        characteristics: CameraCharacteristics,
        target: Int,
    ): Range<Int>? {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        return ranges.firstOrNull { it.lower == target && it.upper == target }
            ?: ranges.filter { target in it }.minByOrNull { it.upper - it.lower }
            ?: ranges.filter { it.upper <= target }.maxByOrNull { it.upper }
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

        if (zoomByRatioControl) {
            request.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRamp.value)
        } else {
            activeArray?.let { array ->
                request.set(CaptureRequest.SCALER_CROP_REGION, cropRegion(array, zoomRamp.value))
            }
        }

        if (autoExposure) {
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            if (aeCompensationMax > aeCompensationMin && aeCompensationStepEv > 0f) {
                request.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    aeCompensationSteps,
                )
            }
        } else {
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            request.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
        }
        fpsRange?.let { request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

        focus.applyTo(request, afRegion, maxAfRegions)

        // Optical and electronic stabilisation are independent requests. Each is
        // applied only when the active sensor advertises it; the bridge keeps the
        // per-lens preference.
        if (oisSupported) {
            request.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (oisEnabled) {
                    CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON
                } else {
                    CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF
                },
            )
        }
        if (eisSupported) {
            request.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (eisEnabled) {
                    CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON
                } else {
                    CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                },
            )
        }

        if (wbMode == ManualWhiteBalance.WIRE_VALUE) {
            // Manual white balance uses the nearest HAL preset as its colour
            // base: the sensor's own white point and CCM are not knowable from
            // the public metadata, and gains alone leave a strong cast. The
            // kelvin/tint residual is applied in the scene-linear shader.
            request.set(CaptureRequest.CONTROL_AWB_MODE, awbModeValue(manualPreset))
            val log = "manual ${wbKelvin}K tint=$wbTint preset=$manualPreset cct=$manualPresetCct"
            if (log != lastWbLog) {
                lastWbLog = log
                android.util.Log.i("SlateCam", "wb: $log")
            }
        } else {
            request.set(CaptureRequest.CONTROL_AWB_MODE, awbModeValue(wbMode))
        }

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
        shadingRequestedMode()?.let {
            request.set(CaptureRequest.SHADING_MODE, shadingModeValue(it))
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

    private fun cropRegion(array: Rect, ratio: Float): Rect {
        val safe = ratio.coerceAtLeast(1f)
        val width = (array.width() / safe).roundToInt().coerceAtLeast(1)
        val height = (array.height() / safe).roundToInt().coerceAtLeast(1)
        val centerX = array.centerX()
        val centerY = array.centerY()
        return Rect(
            centerX - width / 2,
            centerY - height / 2,
            centerX + width / 2,
            centerY + height / 2,
        )
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
        stopZoomRamp()
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
        builder = null
        previewSurface = null
        activePhysicalCameraId = null
        open = false
    }

    private fun emit() {
        val state = CameraUiState(
            isOpen = open,
            error = errorMessage,
            cameraId = cameraId,
            cameraIds = availableCameraIds,
            cameraLabels = availableCameraLabels,
            iso = iso,
            isoMin = isoMin,
            isoMax = isoMax,
            shutterNs = shutterNs,
            shutterMinNs = shutterMinNs,
            shutterMaxNs = shutterMaxNs,
            shutterStepNs = shutterStepNs(),
            focusDiopters = focus.diopters,
            focusMin = 0f,
            focusMax = focus.maxDiopters,
            focusShiftSpeed = focus.shiftSpeed,
            whiteBalanceMode = wbMode,
            // AWB "off" with no gains produces an unbalanced green cast; the
            // manual control owns that state, so it is not offered separately.
            whiteBalanceModes = wbModes.filter { it != "off" },
            whiteBalanceKelvin = wbKelvin,
            whiteBalanceTint = wbTint,
            manualWhiteBalanceSupported = manualWbSupported,
            autoExposure = autoExposure,
            autoFocus = focus.autoFocus,
            autoFocusSupported = focus.autoFocusSupported,
            focusMode = focus.mode,
            focusModes = focus.availableModes,
            focusScenario = focus.scenario.wireValue,
            focusScenarios = FocusScenario.wireValues,
            focusShiftSensitivity = focus.shiftSensitivity,
            focusTransitionSpeed = focus.transitionSpeed,
            previewWidth = previewSize.width,
            previewHeight = previewSize.height,
            previewRotationDegrees = rotationDegrees,
            glRotationDegrees = glRotationDegrees,
            encoderRotationDegrees = encoderRotationDegrees,
            recordingOrientationHint = recordingOrientationHint,
            previewQuarterTurns = previewQuarterTurns,
            zoomRatio = zoomRamp.value,
            zoomMin = zoomMin,
            zoomMax = zoomMax,
            activePhysicalCameraId = activePhysicalCameraId,
            isTenBit = tenBitActive,
            shutterAngle = ShutterAngle.degrees(shutterNs, frameRate),
            frameRate = frameRate,
            edgeMode = edgeMode,
            edgeModes = edgeModes,
            noiseReduction = noiseReduction,
            noiseReductionModes = noiseReductionModes,
            antibanding = antibanding,
            antibandingModes = antibandingModes,
            shadingCorrection = shadingCorrection,
            shadingSupported = shadingSupported,
            oisSupported = oisSupported,
            oisEnabled = oisEnabled,
            eisSupported = eisSupported,
            eisEnabled = eisEnabled,
            exposureMode = exposureMode.wireValue,
            exposureModes = ExposureMode.wireValues,
            meteringMode = meteringMode.wireValue,
            meteringModes = MeteringMode.wireValues,
            meteringSpeed = meteringSpeed,
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
            result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)?.let {
                if (it != activePhysicalCameraId) {
                    activePhysicalCameraId = it
                    android.util.Log.i(
                        "SlateCamLens",
                        "camera=$cameraId activePhysical=$it zoom=${zoomRamp.value}",
                    )
                    emit()
                }
            }

            // While the HAL owns exposure, keep a recent reading of what it
            // chose. Entering S then starts from the live exposure instead of
            // the last manual value (which may be minutes old).
            if (autoExposure) {
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let {
                    shutterNs = it.coerceIn(shutterMinNs, shutterMaxNs)
                }
                result.get(CaptureResult.SENSOR_SENSITIVITY)?.let {
                    iso = it.coerceIn(isoMin, isoMax)
                }
            }

            // Track the HAL's lens position so manual take-over starts from the
            // converged value and SAF can seed its search. Continuous AF keeps
            // running until the operator moves the focus control.
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let {
                focus.onCaptureResult(it, SystemClock.elapsedRealtime())
            }

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
            shadingRequestedMode()?.let { requested ->
                checkApplied(
                    "SHADING_MODE",
                    requested,
                    result.get(CaptureResult.SHADING_MODE),
                    shadingModeValue(requested),
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

    private fun oisModeName(mode: Int): String = when (mode) {
        CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "off"
        CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON -> "on"
        else -> "mode_$mode"
    }

    private fun videoStabilizationName(mode: Int): String = when (mode) {
        CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "off"
        CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "on"
        CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION -> "preview"
        else -> "mode_$mode"
    }

    private fun shadingName(value: Int): String = when (value) {
        CameraCharacteristics.SHADING_MODE_OFF -> ShadingCorrection.OFF
        CameraCharacteristics.SHADING_MODE_FAST -> ShadingCorrection.FAST
        CameraCharacteristics.SHADING_MODE_HIGH_QUALITY -> ShadingCorrection.HIGH_QUALITY
        else -> "mode_$value"
    }

    private fun shadingModeValue(mode: String): Int = when (mode) {
        ShadingCorrection.OFF -> CameraCharacteristics.SHADING_MODE_OFF
        ShadingCorrection.FAST -> CameraCharacteristics.SHADING_MODE_FAST
        ShadingCorrection.HIGH_QUALITY -> CameraCharacteristics.SHADING_MODE_HIGH_QUALITY
        else -> CameraCharacteristics.SHADING_MODE_OFF
    }

    private fun shadingRequestedMode(): String? =
        ShadingCorrection.requestedMode(shadingModes, shadingCorrection)

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
        private const val DEFAULT_METERING_SECONDS = 0.2f
        private const val P_DEAD_BAND_EV = 0.10f
        private const val P_UPDATE_SECONDS = 0.2f
        private const val AF_REGION_FRACTION = 0.20f
        private const val ZOOM_TICK_MS = 33L
        private const val FOCUS_RAMP_MS = 33L
    }
}
