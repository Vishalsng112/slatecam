package io.github.vishalsng112.slatecam.bridge

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.view.TextureRegistry
import io.github.vishalsng112.slatecam.bridge.pigeon.CameraState
import io.github.vishalsng112.slatecam.bridge.pigeon.LensOption
import io.github.vishalsng112.slatecam.bridge.pigeon.RecordingMode
import io.github.vishalsng112.slatecam.bridge.pigeon.ShootLens
import io.github.vishalsng112.slatecam.bridge.pigeon.ShootProfile
import io.github.vishalsng112.slatecam.bridge.pigeon.SlateCamFlutterApi
import io.github.vishalsng112.slatecam.bridge.pigeon.SlateCamHostApi
import io.github.vishalsng112.slatecam.core.camera.CameraCapabilitiesReader
import io.github.vishalsng112.slatecam.core.camera.CameraController
import io.github.vishalsng112.slatecam.core.camera.CameraUiState
import io.github.vishalsng112.slatecam.core.camera.FocusMode
import io.github.vishalsng112.slatecam.core.camera.FocusScenario
import io.github.vishalsng112.slatecam.core.camera.MeteringMode
import io.github.vishalsng112.slatecam.core.camera.RecordingModeCatalog
import io.github.vishalsng112.slatecam.core.codec.ClipRecorder
import io.github.vishalsng112.slatecam.core.codec.GyroRecorder
import io.github.vishalsng112.slatecam.core.device.CapabilityGates
import io.github.vishalsng112.slatecam.core.device.DeviceDatabaseRepository
import io.github.vishalsng112.slatecam.core.device.DeviceDatabaseSnapshot
import io.github.vishalsng112.slatecam.core.device.FeatureGate
import io.github.vishalsng112.slatecam.core.device.QuirkActions
import io.github.vishalsng112.slatecam.core.gpu.GlPreviewRenderer
import io.github.vishalsng112.slatecam.core.profile.BuiltInLuts
import io.github.vishalsng112.slatecam.core.profile.ColorScienceTuning
import io.github.vishalsng112.slatecam.core.profile.InputProfile
import io.github.vishalsng112.slatecam.core.profile.LutLibrary
import io.github.vishalsng112.slatecam.core.profile.PictureProfile
import io.github.vishalsng112.slatecam.core.probe.model.CameraInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * The single place where Flutter meets Kotlin. Everything else stays native.
 *
 * Commands arrive through a Pigeon HostApi; state is pushed back through a
 * Pigeon FlutterApi. Dart never holds authoritative camera state and never sees
 * a pixel.
 */
class SlateCamBridge private constructor(
    private val context: Context,
    private val engine: FlutterEngine,
    private val producer: TextureRegistry.SurfaceProducer,
    private val renderer: GlPreviewRenderer,
    private val controller: CameraController,
) : SlateCamHostApi {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recorder = ClipRecorder(context)
    private val gyro = GyroRecorder(context)
    private val deviceDatabase = DeviceDatabaseRepository(context)
    private var deviceSnapshot: DeviceDatabaseSnapshot = deviceDatabase.snapshot()
    private var lastCameraId: String? = null
    private var switchPump: SwitchFramePump? = null
    private var switchPumpTarget: String? = null
    private var lastAppliedProfile: InputProfile = InputProfile.IDENTITY
    private var appliedQuirkIds: Set<String> = emptySet()
    private var deviceReportRunning = false
    private var deviceReportPath: String? = deviceSnapshot.lastReportPath
    private var deviceReportError: String? = null

    /** Selected recording mode id; resolved to a default when null or stale. */
    private var selectedModeId: String? = null

    /** single | movie. Movie uses the common mode across a set of lenses. */
    private var shootMode: String = SHOOT_SINGLE
    private val shootPrefs = context.getSharedPreferences(PREFS_SHOOT, Context.MODE_PRIVATE)
    private var previewQuality = shootPrefs.getString(KEY_PREVIEW_QUALITY, "full") ?: "full"
    private var previewOutputWidth = 0
    private var previewOutputHeight = 0
    private var colorTuning = ColorScienceTuning(
        exposureEv = shootPrefs.getFloat(KEY_COLOR_EV, 0f),
        contrast = shootPrefs.getFloat(KEY_COLOR_CONTRAST, 1f),
        saturation = shootPrefs.getFloat(KEY_COLOR_SATURATION, 1f),
        temperature = shootPrefs.getFloat(KEY_COLOR_TEMPERATURE, 0f),
        tint = shootPrefs.getFloat(KEY_COLOR_TINT, 0f),
    ).normalized()
    private var modesCacheKey: String? = null
    private var modesCache: List<RecordingMode> = emptyList()

    /** Main10/HLG was actually used for the clip in flight. */
    private var activeRecordingTenBit = false

    private var flutterApi: SlateCamFlutterApi? = null
    private var opened = false
    private var resumeOnReturn = false
    private var lastState: CameraUiState? = null

    private var recordingStartMs = 0L
    private var clipPath: String? = null
    private var recordingError: String? = null
    private var gyroSampleCount = 0

    private var activeDestination: Destination? = null
    private var pendingPfd: ParcelFileDescriptor? = null

    private var powerManager: PowerManager? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    private var profileId = PictureProfile.STANDARD.id
    private var zebraEnabled = false
    private var falseColorEnabled = false
    private var peakingEnabled = false
    private var peakingSensitivity = 0.5f
    private var peakingStrength = 0.8f
    private var lutEntries: List<LutLibrary.Entry> = emptyList()
    private var probeMode = false


    private var histogramBins: List<Int> = emptyList()
    private var waveformBins: List<Int> = emptyList()
    private var lastAnalysisPublishMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (recorder.isRecording) {
                pushState()
                mainHandler.postDelayed(this, 1_000)
            }
        }
    }

    init {
        controller.setMeteringMode(
            shootPrefs.getString(KEY_METERING_MODE, MeteringMode.MULTI.wireValue)
                ?: MeteringMode.MULTI.wireValue,
        )
        controller.setMeteringSpeed(shootPrefs.getFloat(KEY_METERING_SPEED, 0.5f))
        val savedScenario = FocusScenario.fromWire(
            shootPrefs.getString(KEY_FOCUS_SCENARIO, FocusScenario.WEDDING.wireValue)
                ?: FocusScenario.WEDDING.wireValue,
        )
        controller.restoreFocusSettings(
            savedScenario.wireValue,
            shootPrefs.getFloat(
                KEY_FOCUS_SENSITIVITY,
                savedScenario.shiftSensitivity,
            ),
            shootPrefs.getFloat(
                KEY_FOCUS_SPEED,
                savedScenario.transitionSpeed,
            ),
        )
        applyDeviceSnapshot(deviceSnapshot)
        controller.listener = CameraController.Listener { state ->
            lastState = state
            if (state.cameraId != lastCameraId) {
                lastCameraId = state.cameraId
                refreshDeviceSnapshot(state.cameraId)
                // In single mode the 10-bit gate follows the active camera.
                // In movie mode the selected common mode owns the decision, so
                // a lens switch must not silently change bit depth.
                if (shootMode == SHOOT_SINGLE) {
                    controller.setTenBit(tenBitGate(state.cameraId).available)
                }
                controller.setShadingCorrection(shadingEnabledFor(state.cameraId))
                controller.setOis(enabledFor(KEY_OIS_OFF, state.cameraId))
                controller.setEis(enabledFor(KEY_EIS_OFF, state.cameraId))
                renderer.setFocusRoi(0.5f, 0.5f)
            }
            // Restore the software focus choice once the lens reports support.
            if (state.focusMode != FocusMode.SOFTWARE &&
                FocusMode.SOFTWARE in state.focusModes &&
                // Software AF is the default on lenses with a focus range; the
                // operator can switch to the camera's CAF/AF/MF at any time.
                shootPrefs.getBoolean(KEY_FOCUS_SOFTWARE, true)
            ) {
                controller.setFocusMode(FocusMode.SOFTWARE)
            }
            renderer.setTenBitSource(state.isTenBit)
            renderer.setDisplayRotation(state.previewRotationDegrees)
            renderer.setGlRotation(state.glRotationDegrees)
            pushState()
        }
        applyProfile(PictureProfile.STANDARD)

        renderer.setAnalysisEnabled(true)
        renderer.onAnalysis = { analysis ->
            // Metering is camera state: every sample goes to the P/S loop,
            // while the UI copy is throttled below.
            controller.submitMetering(
                analysis.lumaGrid,
                analysis.gridWidth,
                analysis.gridHeight,
            )
            controller.submitFocus(analysis.focusScore)
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysisPublishMs >= 200L) {
                lastAnalysisPublishMs = now
                histogramBins = analysis.histogram.toList()
                waveformBins = analysis.waveform.toList()
                mainHandler.post { pushState() }
            }
        }
    }

    fun attach(api: SlateCamFlutterApi) {
        flutterApi = api
    }

    fun registerThermalGuard() {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            if (status >= PowerManager.THERMAL_STATUS_SEVERE && recorder.isRecording) {
                recordingError = "recording stopped: thermal status $status"
                stopRecording()
            }
        }
        power.addThermalStatusListener(Executor { mainHandler.post(it) }, listener)
        powerManager = power
        thermalListener = listener
    }

    override fun openCamera(): Long {
        if (!opened) {
            val mode = activeMode()
            controller.setFrameRate(mode.fps.toInt())
            val width = mode.width.toInt()
            val height = mode.height.toInt()
            val (previewWidth, previewHeight) = previewOutputFor(width, height)
            previewOutputWidth = previewWidth
            previewOutputHeight = previewHeight
            producer.setSize(previewWidth, previewHeight)
            renderer.start(
                freshSurface(),
                width,
                height,
                previewWidth,
                previewHeight,
            )
            val cameraSurface = renderer.cameraSurface
                ?: throw IllegalStateException("GL pipeline did not expose a camera surface")
            controller.open(
                cameraSurface,
                width,
                height,
                tenBit = mode.tenBit,
            )
            opened = true
        }
        return producer.id()
    }

    override fun closeCamera() {
        if (recorder.isRecording) stopRecording()
        stopSwitchPump()
        controller.close()
        opened = false
    }

    override fun setIso(iso: Long) {
        controller.setIso(iso.toInt())
    }

    override fun setShutterNs(nanoseconds: Long) {
        controller.setShutterNs(nanoseconds)
    }

    override fun setShutterAngle(degrees: Double) {
        controller.setShutterAngle(degrees.toFloat())
    }

    override fun setFocusDiopters(diopters: Double) {
        // Dragging the manual slider leaves SAF; remember that choice so a
        // restart/camera switch does not silently restore software focus.
        shootPrefs.edit().putBoolean(KEY_FOCUS_SOFTWARE, false).apply()
        controller.setFocusDiopters(diopters.toFloat())
    }

    override fun setFocusShiftSpeed(dioptersPerSecond: Double) {
        controller.setFocusShiftSpeed(dioptersPerSecond.toFloat())
    }

    override fun setWhiteBalanceMode(mode: String) {
        controller.setWhiteBalanceMode(mode)
    }

    override fun setAutoExposure() {
        controller.setAutoExposure()
    }

    override fun setMeteringMode(mode: String) {
        val selected = MeteringMode.fromWire(mode)
        shootPrefs.edit().putString(KEY_METERING_MODE, selected.wireValue).apply()
        controller.setMeteringMode(selected.wireValue)
    }

    override fun setMeteringSpeed(speed: Double) {
        val clamped = speed.toFloat().coerceIn(0f, 1f)
        shootPrefs.edit().putFloat(KEY_METERING_SPEED, clamped).apply()
        controller.setMeteringSpeed(clamped)
    }

    override fun setFocusScenario(scenario: String) {
        val selected = FocusScenario.fromWire(scenario)
        shootPrefs.edit()
            .putString(KEY_FOCUS_SCENARIO, selected.wireValue)
            .putFloat(KEY_FOCUS_SENSITIVITY, selected.shiftSensitivity)
            .putFloat(KEY_FOCUS_SPEED, selected.transitionSpeed)
            .apply()
        shootPrefs.edit().putBoolean(KEY_FOCUS_SOFTWARE, true).apply()
        controller.setFocusScenario(selected.wireValue)
    }

    override fun setFocusShiftSensitivity(value: Double) {
        val clamped = value.toFloat().coerceIn(0f, 1f)
        shootPrefs.edit().putFloat(KEY_FOCUS_SENSITIVITY, clamped).apply()
        controller.setFocusShiftSensitivity(clamped)
    }

    override fun setFocusTransitionSpeed(value: Double) {
        val clamped = value.toFloat().coerceIn(0f, 1f)
        shootPrefs.edit().putFloat(KEY_FOCUS_SPEED, clamped).apply()
        controller.setFocusTransitionSpeed(clamped)
    }

    override fun setExposureMode(mode: String) {
        controller.setExposureMode(mode)
    }

    override fun setCameraId(id: String) {
        requestLensSwitch(id, allowUnlisted = false)
    }

    /**
     * Lens selection for the zoom control. A hidden lens is allowed only when
     * the probe opened it; that keeps vendor gating evidence in the report
     * rather than in device code.
     */
    override fun selectLens(id: String) {
        val option = lensOptions().firstOrNull { it.id == id }
        if (option != null && (!option.exposed && !option.openable)) return
        requestLensSwitch(id, allowUnlisted = option != null && !option.exposed)
    }

    /**
     * In movie mode a lens switch during recording is allowed only when the
     * target camera supports the active common mode. Nothing closes the
     * recorder; in single mode a switch while recording is rejected instead of
     * silently ending the clip.
     */
    private fun requestLensSwitch(id: String, allowUnlisted: Boolean) {
        if (id == lastState?.cameraId) return
        val caps = CameraCapabilitiesReader.read(context, id)
        if (caps == null) {
            recordingError = "cannot read capabilities for camera $id"
            pushState()
            return
        }

        val current = activeMode()
        val targetModes = RecordingModeCatalog.modesFor(caps, tenBitVerifier(id))
        val compatible = targetModes.firstOrNull { it.id == current.id && it.enabled }

        if (recorder.isRecording) {
            if (shootMode != SHOOT_MOVIE) {
                recordingError = "stop recording or enable Movie mode to switch lenses"
                pushState()
                return
            }
            if (id !in shootCameraIds()) {
                recordingError = "lens $id is not part of the movie lens set"
                pushState()
                return
            }
            if (compatible == null) {
                recordingError = "lens $id cannot match ${current.label}"
                pushState()
                return
            }
            recordingError = null
            beginSwitchPump(id)
            controller.setCameraId(id, allowUnlisted = true)
            pushState()
            return
        }

        // Not recording: switch the mode with the lens when the current one is
        // not supported. This is the fragmentation fix: never ask a new camera
        // for a size, rate or bit depth it does not have.
        if (compatible == null) {
            val fallback = targetModes.firstOrNull {
                it.enabled && it.width == 1920 && it.height == 1080 &&
                    it.fps == 30 && it.tenBit
            } ?: targetModes.firstOrNull {
                it.enabled && it.width == 1920 && it.height == 1080 &&
                    it.fps == 30
            } ?: targetModes.firstOrNull {
                it.enabled && it.width == 1920 && it.height == 1080
            } ?: targetModes.firstOrNull { it.enabled }
            if (fallback == null) {
                recordingError = "camera $id has no usable recording mode"
                pushState()
                return
            }
            selectedModeId = fallback.id
            if (opened) {
                resizePreview(fallback.width, fallback.height)
            }
            controller.configure(
                fallback.width,
                fallback.height,
                fallback.fps,
                fallback.tenBit,
                cameraIdOverride = id,
            )
            recordingError = null
            pushState()
            return
        }
        recordingError = null
        controller.setCameraId(id, allowUnlisted)
        pushState()
    }

    override fun setZoom(ratio: Double) {
        controller.setZoomRatio(ratio.toFloat())
    }

    override fun setPreviewQuality(quality: String) {
        val normalized = when (quality) {
            "1080", "720" -> quality
            else -> "full"
        }
        if (previewQuality == normalized) return
        previewQuality = normalized
        shootPrefs.edit().putString(KEY_PREVIEW_QUALITY, normalized).apply()
        val state = lastState
        if (state != null && state.isOpen) {
            resizePreview(state.previewWidth, state.previewHeight)
        }
        pushState()
    }

    override fun setFocusPoint(x: Double, y: Double) {
        val nx = x.toFloat().coerceIn(0f, 1f)
        val ny = y.toFloat().coerceIn(0f, 1f)
        renderer.setFocusRoi(nx, ny)
        val buffer = renderer.viewfinderToBuffer(nx, ny)
        controller.setFocusPoint(buffer[0], buffer[1])
    }

    override fun setFocusMode(mode: String) {
        shootPrefs.edit()
            .putBoolean(KEY_FOCUS_SOFTWARE, mode == FocusMode.SOFTWARE)
            .apply()
        controller.setFocusMode(mode)
    }

    override fun setProfile(profileId: String) {
        applyProfileById(profileId)
        pushState()
    }

    override fun setColorExposure(ev: Double) {
        updateColorTuning(colorTuning.copy(exposureEv = ev.toFloat()))
    }

    override fun setColorContrast(value: Double) {
        updateColorTuning(colorTuning.copy(contrast = value.toFloat()))
    }

    override fun setColorSaturation(value: Double) {
        updateColorTuning(colorTuning.copy(saturation = value.toFloat()))
    }

    override fun setColorTemperature(value: Double) {
        updateColorTuning(colorTuning.copy(temperature = value.toFloat()))
    }

    override fun setColorTint(value: Double) {
        updateColorTuning(colorTuning.copy(tint = value.toFloat()))
    }

    override fun resetColorScience() {
        updateColorTuning(ColorScienceTuning.IDENTITY)
    }

    private fun updateColorTuning(value: ColorScienceTuning) {
        colorTuning = value.normalized()
        shootPrefs.edit()
            .putFloat(KEY_COLOR_EV, colorTuning.exposureEv)
            .putFloat(KEY_COLOR_CONTRAST, colorTuning.contrast)
            .putFloat(KEY_COLOR_SATURATION, colorTuning.saturation)
            .putFloat(KEY_COLOR_TEMPERATURE, colorTuning.temperature)
            .putFloat(KEY_COLOR_TINT, colorTuning.tint)
            .apply()
        applyProfileById(profileId)
        pushState()
    }

    override fun setZebra(enabled: Boolean) {
        zebraEnabled = enabled
        renderer.setZebra(enabled, 0.7f)
        pushState()
    }

    override fun setFalseColor(enabled: Boolean) {
        falseColorEnabled = enabled
        renderer.setFalseColor(enabled)
        pushState()
    }

    override fun setPeaking(enabled: Boolean, sensitivity: Double, strength: Double) {
        peakingEnabled = enabled
        peakingSensitivity = sensitivity.toFloat().coerceIn(0f, 1f)
        peakingStrength = strength.toFloat().coerceIn(0.1f, 1f)
        renderer.setPeaking(peakingEnabled, peakingSensitivity, peakingStrength)
        pushState()
    }

    override fun setEdgeMode(mode: String) {
        controller.setEdgeMode(mode)
    }

    override fun setNoiseReduction(mode: String) {
        controller.setNoiseReduction(mode)
    }

    override fun setAntibanding(mode: String) {
        controller.setAntibanding(mode)
    }

    override fun setShadingCorrection(enabled: Boolean) {
        val id = lastState?.cameraId ?: lastCameraId ?: return
        val disabled = (shootPrefs.getStringSet(KEY_SHADING_OFF, emptySet()) ?: emptySet())
            .toMutableSet()
        if (enabled) disabled.remove(id) else disabled.add(id)
        shootPrefs.edit().putStringSet(KEY_SHADING_OFF, disabled).apply()
        controller.setShadingCorrection(enabled)
        pushState()
    }

    override fun setOis(enabled: Boolean) {
        val id = lastState?.cameraId ?: lastCameraId ?: return
        persistDisabled(KEY_OIS_OFF, id, !enabled)
        controller.setOis(enabled)
        pushState()
    }

    override fun setEis(enabled: Boolean) {
        val id = lastState?.cameraId ?: lastCameraId ?: return
        persistDisabled(KEY_EIS_OFF, id, !enabled)
        controller.setEis(enabled)
        pushState()
    }

    /** Vignette correction is on unless this sensor was explicitly turned off. */
    private fun shadingEnabledFor(cameraId: String): Boolean =
        enabledFor(KEY_SHADING_OFF, cameraId)

    private fun enabledFor(key: String, cameraId: String): Boolean =
        cameraId !in (shootPrefs.getStringSet(key, emptySet()) ?: emptySet())

    private fun persistDisabled(key: String, cameraId: String, disabled: Boolean) {
        val ids = (shootPrefs.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
        if (disabled) ids.add(cameraId) else ids.remove(cameraId)
        shootPrefs.edit().putStringSet(key, ids).apply()
    }

    override fun setDeviceReportOptIn(enabled: Boolean) {
        deviceDatabase.setReportOptIn(enabled)
        deviceSnapshot = snapshotFor(lastCameraId)
        pushState()
    }

    override fun runDeviceReport() {
        if (deviceReportRunning) return
        if (!deviceSnapshot.reportOptIn) {
            deviceReportError = "device reports are opt-in"
            pushState()
            return
        }

        deviceReportRunning = true
        deviceReportError = null
        val reopen = opened
        if (recorder.isRecording) stopRecording()
        controller.close()
        opened = false
        pushState()

        Thread({
            val outcome = runCatching { deviceDatabase.runReport(requireOptIn = true) }
            mainHandler.post {
                outcome.onSuccess { result ->
                    deviceReportPath = result.archived?.absolutePath ?: result.latest.absolutePath
                    modesCacheKey = null
                    deviceSnapshot = snapshotFor(lastCameraId)
                }.onFailure { error ->
                    deviceReportError = error.message ?: "device report failed"
                }
                deviceReportRunning = false
                if (reopen) reopenCamera()
                pushState()
            }
        }, "slatecam-device-report").start()
    }

    override fun reloadDeviceDatabase() {
        if (recorder.isRecording) stopRecording()
        modesCacheKey = null
        deviceDatabase.reload()
        deviceSnapshot = snapshotFor(lastCameraId)
        deviceReportPath = deviceSnapshot.lastReportPath
        applyDeviceSnapshot(deviceSnapshot)
        if (opened) reopenCamera()
        pushState()
    }

    override fun reloadLuts() {
        refreshLuts()
        pushState()
    }

    override fun isProbeMode(): Boolean = probeMode

    fun setProbeMode(enabled: Boolean) {
        probeMode = enabled
    }

    private fun refreshLuts() {
        lutEntries = LutLibrary.scan(File(context.filesDir, "luts"))
        if (profileId.startsWith("lut:") && lutEntries.none { it.id == profileId }) {
            applyProfile(PictureProfile.STANDARD)
        }
    }

    private fun applyProfileById(id: String) {
        if (id.startsWith("lut:")) {
            val entry = lutEntries.firstOrNull { it.id == id }
            if (entry != null) {
                profileId = id
                val tuning = colorTuning
                renderer.applyProfile(
                    tuning.exposureMultiplier(),
                    tuning.contrast,
                    tuning.saturation,
                    false,
                    false,
                    0.18f,
                    1f,
                    0f,
                )
                renderer.setWhiteBalanceTuning(tuning.temperature, tuning.tint)
                renderer.setLut(entry.lut.size, entry.lut.copyData())
                return
            }
        }
        applyProfile(PictureProfile.byId(id))
    }

    /** Device-testing hook: apply profile/zebra from launch intent extras. */
    fun applyStartupOverrides(
        profile: String?,
        zebra: Boolean,
        zebraThreshold: Float = 0.7f,
        falseColor: Boolean = false,
        peaking: Boolean = false,
        peakingSensitivity: Float = 0.5f,
    ) {
        if (profile != null) applyProfileById(profile)
        if (falseColor) {
            falseColorEnabled = true
            renderer.setFalseColor(true)
        }
        if (peaking) {
            peakingEnabled = true
            this.peakingSensitivity = peakingSensitivity.coerceIn(0f, 1f)
            renderer.setPeaking(true, this.peakingSensitivity, peakingStrength)
        }
        if (zebra) {
            zebraEnabled = true
            renderer.setZebra(true, zebraThreshold)
            pushState()
        }
    }

    private fun applyProfile(profile: PictureProfile) {
        profileId = profile.id
        val tuning = colorTuning
        renderer.applyProfile(
            exposure = profile.exposure * tuning.exposureMultiplier(),
            contrast = profile.contrast * tuning.contrast,
            saturation = profile.saturation * tuning.saturation,
            logEnabled = profile.logEnabled,
            monochrome = profile.monochrome,
            pivot = profile.pivot,
            highlightRolloffKnee = profile.highlightRolloffKnee,
            shadowToe = profile.shadowToe,
        )
        renderer.setWhiteBalanceTuning(tuning.temperature, tuning.tint)
        val lut = profile.lutId?.let(BuiltInLuts::byId)
        if (lut != null) {
            renderer.setLut(lut.size, lut.copyData())
        } else {
            renderer.setLut(0, null)
        }
    }

    private fun refreshDeviceSnapshot(cameraId: String?) {
        deviceSnapshot = snapshotFor(cameraId)
        deviceReportPath = deviceSnapshot.lastReportPath
        applyDeviceSnapshot(deviceSnapshot)
    }

    /**
     * Calibration lookup prefers the probe-stable lens key over the Camera2 ID,
     * because vendor camera IDs can be renumbered between boots.
     */
    private fun snapshotFor(cameraId: String?) = deviceDatabase.snapshot(
        cameraId,
        deviceDatabase.latestReport()
            ?.cameras
            ?.firstOrNull { it.id == cameraId }
            ?.let(::lensKey),
    )

    private fun applyDeviceSnapshot(snapshot: DeviceDatabaseSnapshot) {
        val profile = snapshot.calibration?.toInputProfile() ?: InputProfile.IDENTITY
        if (profile != lastAppliedProfile) {
            lastAppliedProfile = profile
            renderer.setInputProfile(
                profile.matrixArray(),
                profile.exposureTrim,
                profile.saturationTrim,
                profile.wbROffset,
                profile.wbBOffset,
                profile.shadowLift,
            )
        }

        val quirkIds = snapshot.quirks.map { it.id }.toSet()
        if (quirkIds != appliedQuirkIds) {
            appliedQuirkIds = quirkIds
            snapshot.quirks.firstOrNull { it.action == QuirkActions.FORCE_ANTIBANDING }
                ?.value
                ?.let(controller::setAntibanding)
            if (snapshot.quirks.any { it.action == QuirkActions.DISABLE_NOISE_REDUCTION }) {
                controller.setNoiseReduction("off")
            }
        }
    }

    private fun reopenCamera() {
        val surface = renderer.cameraSurface ?: return
        val mode = activeMode()
        controller.setFrameRate(mode.fps.toInt())
        controller.open(
            surface,
            mode.width.toInt(),
            mode.height.toInt(),
            tenBit = mode.tenBit,
        )
        opened = true
    }

    /**
     * The live 10-bit decision for this device, camera and recording size. It is
     * derived from the probe report plus any device quirk, never from a model
     * name, so the same build behaves honestly on every device.
     */
    private fun tenBitGate(
        width: Int,
        height: Int,
        fps: Int,
        cameraId: String?,
    ): FeatureGate {
        val verified = CapabilityGates.tenBitRecording(
            identity = deviceSnapshot.identity,
            quirks = deviceSnapshot.quirks,
            report = deviceSnapshot.probeReport,
            cameraId = cameraId,
            width = width,
            height = height,
            fps = fps,
        )
        return verified
    }

    private fun tenBitGate(cameraId: String?): FeatureGate {
        val state = lastState
        if (state != null && state.previewWidth > 0 && state.previewHeight > 0) {
            return tenBitGate(
                state.previewWidth,
                state.previewHeight,
                state.frameRate,
                cameraId,
            )
        }
        val mode = activeMode()
        return tenBitGate(
            mode.width.toInt(),
            mode.height.toInt(),
            mode.fps.toInt(),
            cameraId,
        )
    }

    override fun startRecording() {
        if (!opened) {
            recordingError = "camera is not open"
            pushState()
            return
        }
        if (recorder.isRecording) return

        val mode = activeMode()
        val name = "SlateCam_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"

        try {
            val started = startRecorder(name, mode)
            activeDestination = started.destination
            activeRecordingTenBit = started.tenBitHlg
            renderer.attachEncoderSurface(started.surface, started.tenBitHlg)
            renderer.startEncoderTimestampCapture()
            clipPath = null
            recordingError = null
            recordingStartMs = SystemClock.elapsedRealtime()
            gyroSampleCount = 0
            if (controller.isTimestampRealtime && gyro.isAvailable()) {
                gyro.start()
            } else {
                android.util.Log.w(
                    "SlateCam",
                    "gyro metadata disabled (realtime sensor timestamp required)",
                )
            }
            mainHandler.post(tick)
            pushState()
        } catch (t: Throwable) {
            recordingError = "recording failed to start: ${t.message}"
            pushState()
        }
    }

    override fun stopRecording() {
        if (!recorder.isRecording) return
        stopSwitchPump()
        renderer.detachEncoderSurface()

        Thread({
            val finalized = recorder.stop()
            val frameTimestamps = renderer.stopEncoderTimestampCapture()
            val samples = gyro.stop()
            if (samples > 0) {
                val base = activeDestination?.label
                    ?.substringAfterLast('/')
                    ?.removeSuffix(".mp4")
                    ?: "clip-${System.currentTimeMillis()}"
                val file = File(File(context.filesDir, "gyro"), "$base.gyro.json")
                val frames = gyro.writeAlignedSidecar(
                    file = file,
                    fps = lastState?.frameRate ?: 30,
                    frameTimestampsNs = frameTimestamps,
                )
                android.util.Log.i(
                    "SlateCam",
                    "gyro sidecar: raw=$samples frames=$frames path=${file.absolutePath}",
                )
            }
            gyroSampleCount = samples
            commitDestination(activeDestination, finalized)
            activeDestination = null
            activeRecordingTenBit = false
            mainHandler.post { pushState() }
        }, "slatecam-finalize").start()

        pushState()
    }

    fun dispose() {
        stopSwitchPump()
        if (recorder.isRecording) {
            renderer.detachEncoderSurface()
            val destination = activeDestination
            val finalized = recorder.stop()
            gyro.stop()
            commitDestination(destination, finalized)
            activeDestination = null
        }
        thermalListener?.let { listener ->
            runCatching { powerManager?.removeThermalStatusListener(listener) }
        }
        controller.shutdown()
        renderer.close()
        producer.release()
        scope.cancel()
    }

    private data class Destination(
        val muxer: MediaMuxer,
        val uri: Uri?,
        val file: File?,
        val label: String,
    )

    private data class StartedRecorder(
        val surface: Surface,
        val destination: Destination,
        val tenBitHlg: Boolean,
    )

    private fun startRecorder(name: String, mode: RecordingMode): StartedRecorder {
        var tenBitHlg = mode.tenBit && controller.isTenBitActive
        val bitrate = bitrateFor(mode)
        var destination = openDestination(name)

        val surface = try {
            recorder.start(
                mode.width.toInt(),
                mode.height.toInt(),
                mode.fps.toInt(),
                bitrate,
                tenBitHlg,
                destination.muxer,
                orientationHint = 0,
            )
        } catch (first: Throwable) {
            // Main10 configure can be refused; retry as 8-bit with a clean sink.
            abandon(destination)
            tenBitHlg = false
            destination = openDestination(name)
            try {
                recorder.start(
                    mode.width.toInt(),
                    mode.height.toInt(),
                    mode.fps.toInt(),
                    bitrate,
                    false,
                    destination.muxer,
                    orientationHint = 0,
                )
            } catch (second: Throwable) {
                abandon(destination)
                throw second
            }
        }

        return StartedRecorder(surface, destination, tenBitHlg)
    }

    private fun bitrateFor(mode: RecordingMode): Int {
        val pixels = mode.width * mode.height
        val base = when {
            pixels >= 3840L * 2160 -> 40_000_000
            pixels >= 1920L * 1080 -> 12_000_000
            else -> 9_000_000
        }
        return (base * mode.fps.toInt() / 30).coerceAtLeast(4_000_000)
    }

    /** MediaStore first so clips appear in galleries; app storage as a fallback. */
    private fun openDestination(name: String): Destination =
        runCatching { openMediaStoreDestination(name) }.getOrNull()
            ?: openFileDestination(name)

    private fun openMediaStoreDestination(name: String): Destination? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/SlateCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: run {
            resolver.delete(uri, null, null)
            return null
        }
        val muxer = runCatching {
            MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }.getOrElse {
            runCatching { pfd.close() }
            resolver.delete(uri, null, null)
            return null
        }
        pendingPfd = pfd
        return Destination(
            muxer = muxer,
            uri = uri,
            file = null,
            label = "${Environment.DIRECTORY_MOVIES}/SlateCam/$name",
        )
    }

    private fun openFileDestination(name: String): Destination {
        val directory = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(directory, name)
        return Destination(
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
            uri = null,
            file = file,
            label = file.absolutePath,
        )
    }

    private fun abandon(destination: Destination?) {
        runCatching { destination?.muxer?.release() }
        runCatching { pendingPfd?.close() }
        pendingPfd = null
        destination?.uri?.let { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        destination?.file?.let { file -> runCatching { file.delete() } }
    }

    private fun commitDestination(destination: Destination?, finalized: Boolean) {
        runCatching { pendingPfd?.close() }
        pendingPfd = null
        if (destination == null) return

        if (!finalized) {
            recordingError = recorder.lastError ?: "recording failed"
            clipPath = null
            abandon(destination)
            return
        }

        val uri = destination.uri
        if (uri != null) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            runCatching { context.contentResolver.update(uri, values, null, null) }
            clipPath = destination.label
        } else {
            clipPath = destination.file?.absolutePath
        }
        recorder.lastError?.let { recordingError = it }
    }

    /** Releases the camera when the app leaves the foreground. */
    fun pause() {
        resumeOnReturn = opened
        if (recorder.isRecording) stopRecording()
        controller.close()
        opened = false
    }

    /** Reopens the camera if it was open before backgrounding. */
    fun resume() {
        if (!resumeOnReturn || opened) return
        resumeOnReturn = false
        reopenCamera()
    }

    /**
     * Back lenses offered by the zoom control. This reads the probe report, so
     * the list is capability evidence rather than a brand/model table. Logical
     * variants that resolve to the same role and 35 mm equivalent collapse into
     * one entry, preferring the exposed camera first.
     */
    private fun lensOptions(): List<LensOption> {
        val cameras = deviceSnapshot.probeReport?.cameras ?: return emptyList()
        val seen = mutableSetOf<String>()
        return cameras
            .asSequence()
            .filter { it.facing == "back" }
            .filter { it.role in LENS_ROLES }
            .filter { it.exposed || it.openable == true }
            .sortedWith(
                compareBy<CameraInfo> { if (it.role == "macro") 1 else 0 }
                    .thenBy { it.intrinsicZoomRatio ?: 1.0 },
            )
            .filter { camera ->
                val key = camera.role + ":" +
                    ((camera.focalLength35mmEquiv ?: 0.0).roundToInt())
                seen.add(key)
            }
            .map { camera ->
                LensOption(
                    id = camera.id,
                    label = lensLabel(camera),
                    role = camera.role,
                    facing = camera.facing,
                    zoomRatio = camera.intrinsicZoomRatio ?: 1.0,
                    focalLength35mm = camera.focalLength35mmEquiv ?: 0.0,
                    openable = camera.openable ?: camera.exposed,
                    exposed = camera.exposed,
                )
            }
            .toList()
    }

    private fun lensLabel(camera: CameraInfo): String {
        if (camera.role == "macro") return "MACRO"
        val ratio = camera.intrinsicZoomRatio ?: 1.0
        val ratioText = if (ratio >= 10.0) {
            "${ratio.roundToInt()}×"
        } else {
            String.format(Locale.US, "%.1f×", ratio)
        }
        val millimeters = camera.focalLength35mmEquiv?.roundToInt() ?: 0
        return if (millimeters > 0) "$ratioText · ${millimeters}mm" else ratioText
    }

    override fun selectRecordingMode(id: String) {
        val mode = recordingModes().firstOrNull { it.id == id } ?: return
        if (!mode.enabled) {
            recordingError = mode.reason
            pushState()
            return
        }
        if (recorder.isRecording) stopRecording()
        selectedModeId = mode.id
        applyMode(mode)
        pushState()
    }

    override fun setShootMode(mode: String) {
        val normalized = if (mode == SHOOT_MOVIE) SHOOT_MOVIE else SHOOT_SINGLE
        if (normalized == shootMode) return
        if (normalized == SHOOT_MOVIE && shootCameraIds().size < 2) {
            recordingError = "movie mode needs at least two selected lenses"
            pushState()
            return
        }
        if (recorder.isRecording) stopRecording()
        shootMode = normalized
        selectedModeId = null
        applyMode(activeMode())
        pushState()
    }

    override fun setShootLens(id: String, selected: Boolean) {
        if (recorder.isRecording) {
            recordingError = "stop recording to edit the shoot set"
            pushState()
            return
        }
        if (id !in eligibleShootCameras().map { it.id }) return

        val current = effectiveShootIds().toMutableSet()
        if (selected) current.add(id) else current.remove(id)
        shootPrefs.edit().putStringSet(KEY_SHOOT_LENSES, current).apply()
        updateActiveProfile(current)
        modesCacheKey = null

        if (shootMode == SHOOT_MOVIE) {
            if (current.size < 2) {
                shootMode = SHOOT_SINGLE
                recordingError = "movie mode needs at least two selected lenses"
            }
            selectedModeId = null
            applyMode(activeMode())
        }
        pushState()
    }

    /**
     * Movie-mode switches recreate the camera session. Keep the encoder alive
     * with the last frame until the new session streams.
     */
    private val switchPumpTick = object : Runnable {
        override fun run() {
            val pump = switchPump ?: return
            if (!recorder.isRecording) {
                stopSwitchPump()
                return
            }
            val decision = pump.tick(
                nowMs = SystemClock.elapsedRealtime(),
                targetReady = lastState?.cameraId == switchPumpTarget,
                frameCount = renderer.cameraFramesRendered(),
            )
            when (decision) {
                SwitchFramePump.Decision.REPEAT -> {
                    renderer.repeatLastFrame()
                    mainHandler.postDelayed(this, pump.frameIntervalMs)
                }
                SwitchFramePump.Decision.HOLD ->
                    mainHandler.postDelayed(this, pump.frameIntervalMs)
                SwitchFramePump.Decision.DONE -> stopSwitchPump()
            }
        }
    }

    private fun beginSwitchPump(targetId: String) {
        val fps = activeMode().fps.toInt().coerceAtLeast(1)
        val pump = SwitchFramePump(frameIntervalMs = (1000L / fps).coerceAtLeast(1L))
        pump.start(SystemClock.elapsedRealtime(), renderer.cameraFramesRendered())
        switchPump = pump
        switchPumpTarget = targetId
        mainHandler.removeCallbacks(switchPumpTick)
        mainHandler.postDelayed(switchPumpTick, pump.frameIntervalMs)
    }

    private fun stopSwitchPump() {
        mainHandler.removeCallbacks(switchPumpTick)
        switchPump?.cancel()
        switchPump = null
        switchPumpTarget = null
    }

    private fun applyMode(mode: RecordingMode) {
        val width = mode.width.toInt()
        val height = mode.height.toInt()
        if (opened) {
            resizePreview(width, height)
        }
        controller.configure(width, height, mode.fps.toInt(), mode.tenBit)
    }

    /**
     * Resizes the preview for a new recording mode.
     *
     * `SurfaceProducer.setSize` does not retarget the GL renderer. The existing
     * EGL window surface keeps the buffer size it was created with, so a 16:9
     * surface asked to show 4:3 would only fill the left 75% of the texture and
     * letterbox the rest. Force a fresh producer surface and recreate the EGL
     * window surface at the new size; the camera SurfaceTexture buffer and the
     * camera session are updated separately.
     */
    private fun resizePreview(width: Int, height: Int) {
        val (previewWidth, previewHeight) = previewOutputFor(width, height)
        previewOutputWidth = previewWidth
        previewOutputHeight = previewHeight
        renderer.setCaptureSize(width, height)
        renderer.setPreviewSize(previewWidth, previewHeight)
        producer.setSize(previewWidth, previewHeight)
        renderer.updateTarget(freshSurface())
    }

    /**
     * Viewfind resolution limit. The camera stream and encoder stay at the
     * recording size; only the preview surface is downsampled.
     */
    private fun previewOutputFor(width: Int, height: Int): Pair<Int, Int> {
        val limit = when (previewQuality) {
            "1080" -> 1080
            "720" -> 720
            else -> height
        }
        if (limit <= 0 || limit >= height) return width to height
        val scale = limit.toFloat() / height
        val rawWidth = (width * scale).roundToInt().coerceAtLeast(2)
        val rawHeight = limit.coerceAtLeast(2)
        return (rawWidth - rawWidth % 2) to (rawHeight - rawHeight % 2)
    }

    /**
     * A Surface positioned at the producer's current requested size. The
     * producer can hand back an older ImageReader-backed Surface when only
     * `setSize` was called, so ask for a fresh one instead of reusing it.
     */
    private fun freshSurface(): Surface =
        producer.forcedNewSurface ?: producer.surface
            ?: throw IllegalStateException("no preview surface")

    private fun recordingModes(): List<RecordingMode> {
        val cacheKey = "$shootMode|${lastCameraId}|" +
            "${deviceSnapshot.probeReport?.report?.id}|" +
            effectiveShootIds().joinToString(",")
        if (cacheKey == modesCacheKey) return modesCache
        val pairs = if (shootMode == SHOOT_MOVIE) {
            shootCameraIds().mapNotNull { id ->
                CameraCapabilitiesReader.read(context, id)?.let { it to tenBitVerifier(id) }
            }
        } else {
            val id = lastCameraId ?: "0"
            CameraCapabilitiesReader.read(context, id)?.let {
                listOf(it to tenBitVerifier(id))
            } ?: emptyList()
        }

        val modes = if (shootMode == SHOOT_MOVIE) {
            RecordingModeCatalog.commonModes(pairs)
        } else {
            pairs.firstOrNull()?.let { (caps, verifier) ->
                RecordingModeCatalog.modesFor(caps, verifier)
            } ?: emptyList()
        }

        val converted = modes.map { mode ->
            RecordingMode(
                id = mode.id,
                label = mode.label,
                width = mode.width.toLong(),
                height = mode.height.toLong(),
                fps = mode.fps.toLong(),
                tenBit = mode.tenBit,
                enabled = mode.enabled,
                reason = mode.reason,
            )
        }
        modesCacheKey = cacheKey
        modesCache = converted
        return converted
    }

    /**
     * Every lens that can join a shoot set: probe-visible or probe-openable,
     * non-macro, de-duplicated by facing/role/focal length.
     */
    private fun eligibleShootCameras(): List<CameraInfo> {
        val report = deviceSnapshot.probeReport ?: return emptyList()
        val seen = mutableSetOf<String>()
        return report.cameras
            .filter { (it.exposed || it.openable == true) && it.role != "macro" }
            .sortedWith(
                compareByDescending<CameraInfo> { it.facing == "back" }
                    .thenBy { it.intrinsicZoomRatio ?: 1.0 },
            )
            .filter { camera ->
                seen.add(lensKey(camera))
            }
    }

    private fun lensKey(camera: CameraInfo): String =
        shootLensKey(camera.facing, camera.role, camera.focalLength35mmEquiv)

    private fun eligibleLensRefs(): List<ShootLensRef> =
        eligibleShootCameras().map { ShootLensRef(it.id, lensKey(it)) }

    private val profileStore by lazy {
        ShootProfileStore(
            storage = object : ShootProfileStorage {
                override fun readProfiles(): String? =
                    shootPrefs.getString(KEY_SHOOT_PROFILES, null)

                override fun writeProfiles(raw: String) {
                    shootPrefs.edit().putString(KEY_SHOOT_PROFILES, raw).apply()
                }

                override fun readActive(): String? =
                    shootPrefs.getString(KEY_ACTIVE_PROFILE, null)

                override fun writeActive(id: String) {
                    shootPrefs.edit().putString(KEY_ACTIVE_PROFILE, id).apply()
                }
            },
        )
    }

    private fun ensureProfiles(): MutableList<ShootProfileEntry> {
        val eligible = eligibleLensRefs()
        if (eligible.isEmpty()) return profileStore.profiles()
        val selected = effectiveShootIds().toSet().ifEmpty { eligible.map { it.id }.toSet() }
        return profileStore.ensure(profileStore.keysOf(selected, eligible))
    }

    private fun profileInfos(): List<ShootProfile> {
        val eligible = eligibleLensRefs()
        return ensureProfiles().map { profile ->
            ShootProfile(
                id = profile.id,
                name = profile.name,
                lensCount = profileStore.keyCount(profile, eligible).toLong(),
            )
        }
    }

    private fun activeProfileId(): String {
        val profiles = ensureProfiles()
        val active = profileStore.activeId()
        return when {
            active != null && profiles.any { it.id == active } -> active
            else -> profiles.firstOrNull()?.id ?: ""
        }
    }

    private fun updateActiveProfile(selectedIds: Set<String>) {
        ensureProfiles()
        profileStore.updateActive(profileStore.keysOf(selectedIds, eligibleLensRefs()))
    }

    override fun saveShootProfile(name: String) {
        if (recorder.isRecording) {
            recordingError = "stop recording to edit shoot profiles"
            pushState()
            return
        }
        ensureProfiles()
        val keys = profileStore.keysOf(effectiveShootIds().toSet(), eligibleLensRefs())
        if (profileStore.save(name, keys) == null) return
        recordingError = null
        pushState()
    }

    override fun applyShootProfile(id: String) {
        if (recorder.isRecording) {
            recordingError = "stop recording to switch shoot profiles"
            pushState()
            return
        }
        val profiles = ensureProfiles()
        val profile = profiles.firstOrNull { it.id == id } ?: return
        val selectedIds = profileStore.selectionOf(profile, eligibleLensRefs())
        if (!profileStore.setActive(profile.id)) return
        shootPrefs.edit().putStringSet(KEY_SHOOT_LENSES, selectedIds).apply()
        modesCacheKey = null
        if (shootMode == SHOOT_MOVIE) {
            if (selectedIds.size < 2) {
                shootMode = SHOOT_SINGLE
                recordingError = "movie mode needs at least two selected lenses"
            }
            selectedModeId = null
            applyMode(activeMode())
        }
        pushState()
    }

    override fun renameShootProfile(id: String, name: String) {
        if (!profileStore.rename(id, name)) return
        pushState()
    }

    override fun deleteShootProfile(id: String) {
        if (recorder.isRecording) {
            recordingError = "stop recording to edit shoot profiles"
            pushState()
            return
        }
        ensureProfiles()
        when (val result = profileStore.delete(id)) {
            DeleteResult.LastProfile -> {
                recordingError = "keep at least one shoot profile"
                pushState()
            }
            DeleteResult.NotFound -> Unit
            is DeleteResult.Deleted -> {
                val profile = profileStore.profiles()
                    .firstOrNull { it.id == result.activeId } ?: return
                val selectedIds = profileStore.selectionOf(profile, eligibleLensRefs())
                shootPrefs.edit().putStringSet(KEY_SHOOT_LENSES, selectedIds).apply()
                modesCacheKey = null
                if (shootMode == SHOOT_MOVIE) {
                    if (selectedIds.size < 2) {
                        shootMode = SHOOT_SINGLE
                        recordingError = "movie mode needs at least two selected lenses"
                    }
                    selectedModeId = null
                    applyMode(activeMode())
                }
                pushState()
            }
        }
    }

    /** Effective set: persisted selection intersected with eligible lenses. */
    private fun effectiveShootIds(): List<String> {
        val eligible = eligibleShootCameras()
        if (!shootPrefs.contains(KEY_SHOOT_LENSES)) return eligible.map { it.id }
        val saved = shootPrefs.getStringSet(KEY_SHOOT_LENSES, emptySet()).orEmpty()
        return eligible.filter { it.id in saved }.map { it.id }
    }

    private fun shootCameraIds(): List<String> = effectiveShootIds()

    private fun shootLenses(): List<ShootLens> {
        val selected = effectiveShootIds().toSet()
        return eligibleShootCameras().map { camera ->
            val mm = camera.focalLength35mmEquiv?.roundToInt() ?: 0
            val label = if (mm > 0) {
                "${camera.facing} · ${mm}mm"
            } else {
                "${camera.facing} · ${camera.role}"
            }
            ShootLens(
                id = camera.id,
                label = label,
                facing = camera.facing,
                role = camera.role,
                selected = camera.id in selected,
            )
        }
    }

    private fun tenBitVerifier(cameraId: String): RecordingModeCatalog.TenBitVerifier =
        { width, height, fps ->
            val report = deviceSnapshot.probeReport
            when {
                deviceSnapshot.forceEightBit -> "10-bit withheld by a device quirk"
                report == null -> "run the device report to verify 10-bit"
                !report.device.deviceKey.equals(
                    deviceSnapshot.identity.deviceKey,
                    ignoreCase = true,
                ) -> "report is for another device"
                else -> {
                    val camera = report.cameras.firstOrNull { it.id == cameraId }
                    if (camera == null) {
                        "camera $cameraId is missing from the device report"
                    } else {
                        val presetId = when {
                            width == 3840 && height == 2160 -> "uhd${fps}_10bit"
                            width == 1920 && height == 1080 -> "fhd${fps}_10bit"
                            width == 1440 && height == 1080 -> "fhd43_${fps}_10bit"
                            width == 4096 && height == 3072 -> "dci43_${fps}_10bit"
                            width == 3840 && height == 2880 -> "uhd43_${fps}_10bit"
                            width == 2880 && height == 2160 -> "qhd43_${fps}_10bit"
                            else -> null
                        }
                        if (presetId == null) {
                            "10-bit ${width}×$height is not probe-verified"
                        } else {
                            val preset = camera.presets[presetId]
                            when {
                                preset == null -> "$presetId is missing from the report"
                                preset.supported != true ->
                                    preset.detail ?: preset.reason ?: "unsupported"
                                preset.verified != "pass" ->
                                    "not verified (probe: ${preset.verified})"
                                else -> null
                            }
                        }
                    }
                }
            }
        }

    private fun activeMode(): RecordingMode {
        val modes = recordingModes()
        return modes.firstOrNull { it.id == selectedModeId }
            ?: modes.firstOrNull {
                it.enabled && it.width == 1920L && it.height == 1080L &&
                    it.fps == 30L && it.tenBit
            }
            ?: modes.firstOrNull {
                it.enabled && it.width == 1920L && it.height == 1080L &&
                    it.fps == 30L
            }
            ?: modes.firstOrNull {
                it.enabled && it.width == 1920L && it.height == 1080L
            }
            ?: modes.firstOrNull { it.enabled }
            ?: RecordingMode(
                id = RecordingModeCatalog.id(1920, 1080, 30, false),
                label = "1920×1080 · 30p · 16:9 · 8-bit SDR",
                width = 1920L,
                height = 1080L,
                fps = 30L,
                tenBit = false,
                enabled = true,
                reason = "",
            )
    }

    private fun pushState() {
        val api = flutterApi ?: return
        val state = lastState ?: return
        val elapsedSeconds = if (recorder.isRecording) {
            ((SystemClock.elapsedRealtime() - recordingStartMs) / 1_000).toInt()
        } else {
            0
        }
        val grant = tenBitGate(state.cameraId)
        val mode = activeMode()
        val pigeon = state.toPigeon(
            isRecording = recorder.isRecording,
            recordingSeconds = elapsedSeconds,
            clipPath = clipPath,
            recordingError = recordingError ?: recorder.lastError,
            tenBit = state.isTenBit || (recorder.isRecording && activeRecordingTenBit),
            hlgAvailable = grant.available,
            hlgReason = grant.reason,
            lenses = lensOptions(),
            recordingMode = mode.id,
            recordingModes = recordingModes(),
            shootMode = shootMode,
            shootLenses = shootLenses(),
            shootProfiles = profileInfos(),
            activeShootProfile = activeProfileId(),
        )
        scope.launch {
            runCatching { api.onState(pigeon) }
        }
    }


    private fun CameraUiState.toPigeon(
        isRecording: Boolean,
        recordingSeconds: Int,
        clipPath: String?,
        recordingError: String?,
        tenBit: Boolean,
        hlgAvailable: Boolean,
        hlgReason: String,
        lenses: List<LensOption>,
        recordingMode: String,
        recordingModes: List<RecordingMode>,
        shootMode: String,
        shootLenses: List<ShootLens>,
        shootProfiles: List<ShootProfile>,
        activeShootProfile: String,
    ) = CameraState(
        isOpen = isOpen,
        error = error,
        cameraId = cameraId,
        cameraIds = cameraIds,
        cameraLabels = cameraLabels,
        iso = iso.toLong(),
        isoMin = isoMin.toLong(),
        isoMax = isoMax.toLong(),
        shutterNs = shutterNs,
        shutterMinNs = shutterMinNs,
        shutterMaxNs = shutterMaxNs,
        shutterStepNs = shutterStepNs,
        focusDiopters = focusDiopters.toDouble(),
        focusMin = focusMin.toDouble(),
        focusMax = focusMax.toDouble(),
        focusShiftSpeed = focusShiftSpeed.toDouble(),
        whiteBalanceMode = whiteBalanceMode,
        whiteBalanceModes = whiteBalanceModes,
        autoExposure = autoExposure,
        autoFocus = autoFocus,
        autoFocusSupported = autoFocusSupported,
        focusMode = focusMode,
        focusModes = focusModes,
        focusScenario = focusScenario,
        focusScenarios = focusScenarios,
        focusShiftSensitivity = focusShiftSensitivity.toDouble(),
        focusTransitionSpeed = focusTransitionSpeed.toDouble(),
        previewWidth = previewWidth.toLong(),
        previewHeight = previewHeight.toLong(),
        previewOutputWidth = this@SlateCamBridge.previewOutputWidth.toLong(),
        previewOutputHeight = this@SlateCamBridge.previewOutputHeight.toLong(),
        previewQuality = this@SlateCamBridge.previewQuality,
        previewRotationDegrees = previewRotationDegrees.toLong(),
        zoomRatio = zoomRatio.toDouble(),
        zoomMin = zoomMin.toDouble(),
        zoomMax = zoomMax.toDouble(),
        lenses = lenses,
        recordingMode = recordingMode,
        recordingModes = recordingModes,
        shootMode = shootMode,
        shootLenses = shootLenses,
        shootProfiles = shootProfiles,
        activeShootProfile = activeShootProfile,
        tenBit = tenBit,
        hlgAvailable = hlgAvailable,
        hlgReason = hlgReason,
        isRecording = isRecording,
        recordingSeconds = recordingSeconds.toLong(),
        clipPath = clipPath,
        recordingError = recordingError,
        shutterAngle = shutterAngle.toDouble(),
        frameRate = frameRate.toLong(),
        gyroSampleCount = gyroSampleCount.toLong(),
        profile = profileId,
        profileNames = PictureProfile.all.map { it.id } + lutEntries.map { it.id },
        colorExposureEv = colorTuning.exposureEv.toDouble(),
        colorContrast = colorTuning.contrast.toDouble(),
        colorSaturation = colorTuning.saturation.toDouble(),
        colorTemperature = colorTuning.temperature.toDouble(),
        colorTint = colorTuning.tint.toDouble(),
        zebra = zebraEnabled,
        falseColor = falseColorEnabled,
        peaking = peakingEnabled,
        peakingSensitivity = peakingSensitivity.toDouble(),
        peakingStrength = peakingStrength.toDouble(),
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
        exposureMode = exposureMode,
        exposureModes = exposureModes,
        meteringMode = meteringMode,
        meteringModes = meteringModes,
        meteringSpeed = meteringSpeed.toDouble(),
        deviceKey = deviceSnapshot.identity.deviceKey,
        deviceQuirks = deviceSnapshot.quirks.map { it.id },
        deviceCalibrationActive = deviceSnapshot.calibration != null,
        deviceCalibrationSource = deviceSnapshot.calibrationSource,
        deviceDatabaseVersion = deviceSnapshot.databaseVersion.toLong(),
        deviceReportOptIn = deviceSnapshot.reportOptIn,
        deviceReportRunning = deviceReportRunning,
        deviceReportPath = deviceReportPath,
        deviceReportError = deviceReportError,
        histogram = histogramBins.map { it.toLong() },
        waveform = waveformBins.map { it.toLong() },
        waveformColumns = WAVEFORM_COLUMNS.toLong(),
        waveformBins = WAVEFORM_BINS.toLong(),
    )

    companion object {
        private const val SHOOT_SINGLE = "single"
        private const val SHOOT_MOVIE = "movie"
        private const val PREFS_SHOOT = "shoot-profile"
        private const val KEY_SHOOT_LENSES = "shoot_lenses"
        private const val KEY_SHOOT_PROFILES = "shoot_profiles"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val KEY_SHADING_OFF = "shading_off_cameras"
        private const val KEY_OIS_OFF = "ois_off_cameras"
        private const val KEY_EIS_OFF = "eis_off_cameras"
        private const val KEY_METERING_MODE = "metering_mode"
        private const val KEY_METERING_SPEED = "metering_speed"
        private const val KEY_FOCUS_SCENARIO = "focus_scenario"
        private const val KEY_FOCUS_SENSITIVITY = "focus_shift_sensitivity"
        private const val KEY_FOCUS_SPEED = "focus_transition_speed"
        private const val KEY_FOCUS_SOFTWARE = "focus_software"
        private const val KEY_PREVIEW_QUALITY = "preview_quality"
        private const val KEY_COLOR_EV = "color_exposure_ev"
        private const val KEY_COLOR_CONTRAST = "color_contrast"
        private const val KEY_COLOR_SATURATION = "color_saturation"
        private const val KEY_COLOR_TEMPERATURE = "color_temperature"
        private const val KEY_COLOR_TINT = "color_tint"
        private const val WAVEFORM_COLUMNS = 64
        private const val WAVEFORM_BINS = 16
        private val LENS_ROLES = setOf("default", "ultrawide", "tele", "macro")

        fun install(engine: FlutterEngine, context: Context): SlateCamBridge {
            val producer = engine.renderer.createSurfaceProducer()
            val controller = CameraController(context)
            val mainHandler = Handler(Looper.getMainLooper())
            val renderer = GlPreviewRenderer {
                // SurfaceProducer frames are scheduled from the platform thread.
                mainHandler.post { producer.scheduleFrame() }
            }

            val bridge = SlateCamBridge(context, engine, producer, renderer, controller)

            producer.setCallback(object : TextureRegistry.SurfaceProducer.Callback {
                override fun onSurfaceAvailable() {
                    producer.surface?.let(renderer::updateTarget)
                }

                override fun onSurfaceCleanup() {
                    // The next onSurfaceAvailable re-targets rendering.
                }
            })

            SlateCamHostApi.setUp(engine.dartExecutor.binaryMessenger, bridge)
            bridge.attach(SlateCamFlutterApi(engine.dartExecutor.binaryMessenger))
            bridge.registerThermalGuard()
            bridge.refreshLuts()
            return bridge
        }
    }
}
