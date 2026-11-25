package io.github.vishalsng112.slatecam.bridge

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
import io.github.vishalsng112.slatecam.bridge.pigeon.CameraTelemetry
import io.github.vishalsng112.slatecam.bridge.pigeon.AudioInputOption
import io.github.vishalsng112.slatecam.bridge.pigeon.AudioTelemetry
import io.github.vishalsng112.slatecam.bridge.pigeon.LensOption
import io.github.vishalsng112.slatecam.bridge.pigeon.MediaClip
import io.github.vishalsng112.slatecam.bridge.pigeon.MediaStorageInfo
import io.github.vishalsng112.slatecam.bridge.pigeon.PictureProfileInfo
import io.github.vishalsng112.slatecam.bridge.pigeon.ProfileSettings as PigeonProfileSettings
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
import io.github.vishalsng112.slatecam.core.camera.ManualWhiteBalance
import io.github.vishalsng112.slatecam.core.camera.MeteringMode
import io.github.vishalsng112.slatecam.core.camera.RecordingModeCatalog
import io.github.vishalsng112.slatecam.core.codec.AudioInputs
import io.github.vishalsng112.slatecam.core.codec.AudioLevel
import io.github.vishalsng112.slatecam.core.codec.AudioMonitor
import io.github.vishalsng112.slatecam.core.codec.AudioSpec
import io.github.vishalsng112.slatecam.core.codec.ClipRecorder
import io.github.vishalsng112.slatecam.core.codec.GyroRecorder
import io.github.vishalsng112.slatecam.core.device.CapabilityGates
import io.github.vishalsng112.slatecam.core.device.DeviceDatabaseRepository
import io.github.vishalsng112.slatecam.core.device.DeviceDatabaseSnapshot
import io.github.vishalsng112.slatecam.core.device.FeatureGate
import io.github.vishalsng112.slatecam.core.device.QuirkActions
import io.github.vishalsng112.slatecam.core.gpu.GlPreviewRenderer
import io.github.vishalsng112.slatecam.core.profile.BlackGammaRange
import io.github.vishalsng112.slatecam.core.profile.ColorMode
import io.github.vishalsng112.slatecam.core.profile.GammaPreset
import io.github.vishalsng112.slatecam.core.profile.InputProfile
import io.github.vishalsng112.slatecam.core.profile.KneeMode
import io.github.vishalsng112.slatecam.core.profile.KneeSensitivity
import io.github.vishalsng112.slatecam.core.profile.LutLibrary
import io.github.vishalsng112.slatecam.core.profile.PictureProfile
import io.github.vishalsng112.slatecam.core.profile.PictureProfileLibrary
import io.github.vishalsng112.slatecam.core.profile.ProfileKind
import io.github.vishalsng112.slatecam.core.profile.ProfileSettings as CoreProfileSettings
import io.github.vishalsng112.slatecam.core.profile.UserPictureProfile
import io.github.vishalsng112.slatecam.core.storage.MediaLibrary
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
import java.util.UUID
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
    private val audioMonitor = AudioMonitor(context)
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioInputOptions: List<AudioInputOption> = emptyList()
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            onAudioDevicesChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            onAudioDevicesChanged()
        }
    }

    @Volatile
    private var audioLevel: AudioLevel = AudioLevel.SILENT

    @Volatile
    private var audioActive = false

    @Volatile
    private var audioError: String? = null

    @Volatile
    private var audioSource: String? = null

    private var audioTicker = false
    private val audioTick = object : Runnable {
        override fun run() {
            pushAudio()
            if (audioTicker) mainHandler.postDelayed(this, 100L)
        }
    }
    private val deviceDatabase = DeviceDatabaseRepository(context)
    private val mediaLibrary = MediaLibrary(context)
    private var deviceSnapshot: DeviceDatabaseSnapshot = deviceDatabase.snapshot()
    private var lastCameraId: String? = null
    private var switchPump: SwitchFramePump? = null
    private var switchPumpTarget: String? = null
    private var pendingZoom: Pair<String, Float>? = null
    private var failedLogicalId: String? = null
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
    private var audioInputKey: String =
        shootPrefs.getString(KEY_AUDIO_INPUT, AudioInputs.DEFAULT_KEY)
            ?: AudioInputs.DEFAULT_KEY
    private var audioGainDb: Float = shootPrefs.getFloat(KEY_AUDIO_GAIN, 0f)
    private var previewQuality = shootPrefs.getString(KEY_PREVIEW_QUALITY, "full") ?: "full"
    private var previewOutputWidth = 0
    private var previewOutputHeight = 0
    private val pictureProfiles = loadPictureProfiles()
    private var creativeLutId = shootPrefs.getString(KEY_CREATIVE_LUT, null)
        ?.takeIf { it.isNotBlank() && it != "none" }
    private var modesCacheKey: String? = null
    private var modesCache: List<RecordingMode> = emptyList()
    private var analysisOverlayVisible = false

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

    private var profileId = loadActiveProfileId()
    private var zebraEnabled = false
    private var falseColorEnabled = false
    private var peakingEnabled = false
    private var peakingSensitivity = 0.5f
    private var peakingStrength = 0.8f
    private var lutEntries: List<LutLibrary.Entry> = emptyList()
    private var probeMode = false

    /**
     * Set by the activity: runtime permissions can only be requested from an
     * activity, while the bridge only holds an application context.
     */
    var permissionRequester: (() -> Unit)? = null


    private var histogramBins: List<Int> = emptyList()
    private var waveformBins: List<Int> = emptyList()
    private var lastAnalysisPublishMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (recorder.isRecording) {
                pushTelemetry()
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
        controller.restoreWhiteBalance(
            shootPrefs.getString(KEY_WHITE_BALANCE, "auto") ?: "auto",
            shootPrefs.getInt(KEY_WHITE_BALANCE_KELVIN, ManualWhiteBalance.DEFAULT_KELVIN),
            shootPrefs.getInt(KEY_WHITE_BALANCE_TINT, ManualWhiteBalance.DEFAULT_TINT),
        )
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
        refreshAudioOptions()
        runCatching {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        }
        controller.listener = CameraController.Listener { state ->
            val previous = lastState
            val cameraChanged = state.cameraId != lastCameraId
            val openChanged = previous?.isOpen != state.isOpen
            val configChanged = previous != null && (
                previous.whiteBalanceMode != state.whiteBalanceMode ||
                    previous.focusMode != state.focusMode ||
                    previous.focusScenario != state.focusScenario ||
                    previous.exposureMode != state.exposureMode ||
                    previous.meteringMode != state.meteringMode ||
                    previous.edgeMode != state.edgeMode ||
                    previous.noiseReduction != state.noiseReduction ||
                    previous.antibanding != state.antibanding ||
                    previous.shadingCorrection != state.shadingCorrection ||
                    previous.oisEnabled != state.oisEnabled ||
                    previous.eisEnabled != state.eisEnabled ||
                    previous.frameRate != state.frameRate ||
                    previous.previewWidth != state.previewWidth ||
                    previous.previewHeight != state.previewHeight ||
                    previous.activePhysicalCameraId != state.activePhysicalCameraId
                )
            lastState = state
            if (!state.isOpen) {
                // A failed logical-camera switch must not leave `opened` stale:
                // the launch screen's Open camera action would otherwise be a no-op.
                opened = false
                pendingZoom?.let { (id, _) ->
                    failedLogicalId = id
                    pendingZoom = null
                }
            } else {
                pendingZoom?.let { (id, ratio) ->
                    if (state.cameraId == id) {
                        pendingZoom = null
                        controller.setZoomRatio(ratio)
                    }
                }
            }
            if (cameraChanged) {
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
            applyManualWhiteBalance(state)
            if (cameraChanged || openChanged || configChanged) {
                pushState()
            } else {
                pushTelemetry()
            }
        }
        applyProfileById(profileId)

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
            if (now - lastAnalysisPublishMs >= 500L) {
                lastAnalysisPublishMs = now
                histogramBins = analysis.histogram.toList()
                waveformBins = analysis.waveform.toList()
                mainHandler.post { pushTelemetry(analysis = true) }
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
            check(hasCameraPermission()) { "camera permission is not granted" }
            val mode = activeMode()
            controller.setFrameRate(mode.fps.toInt())
            val width = mode.width.toInt()
            val height = mode.height.toInt()
            val (previewWidth, previewHeight) = previewOutputFor(width, height)
            previewOutputWidth = previewWidth
            previewOutputHeight = previewHeight
            producer.setSize(previewWidth, previewHeight)
            try {
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
                    cameraIdOverride = openingCameraId(),
                )
            } catch (error: Throwable) {
                // A failed attempt (denied permission, busy camera, bad mode)
                // must not wedge the pipeline. Drop the half-open session and
                // GL state so the next open starts from a clean slate.
                opened = false
                resumeOnReturn = false
                runCatching { controller.close() }
                runCatching { renderer.reset() }
                throw error
            }
            opened = true
        }
        refreshAudioOptions()
        startAudioMonitor()
        return producer.id()
    }

    override fun cameraPermissionGranted(): Boolean = hasCameraPermission()

    override fun requestCameraPermissions() {
        permissionRequester?.invoke()
    }

    private fun hasCameraPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    override fun closeCamera() {
        if (recorder.isRecording) stopRecording()
        stopAudioMonitor()
        stopSwitchPump()
        controller.close()
        opened = false
        stopAudioTicker()
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
        if (mode != ManualWhiteBalance.WIRE_VALUE) {
            shootPrefs.edit().putString(KEY_WHITE_BALANCE, mode).apply()
        }
        controller.setWhiteBalanceMode(mode)
    }

    override fun setManualWhiteBalance(kelvin: Long, tint: Long) {
        val temperature = ManualWhiteBalance.clampKelvin(kelvin.toInt())
        val trim = ManualWhiteBalance.clampTint(tint.toInt())
        shootPrefs.edit()
            .putString(KEY_WHITE_BALANCE, ManualWhiteBalance.WIRE_VALUE)
            .putInt(KEY_WHITE_BALANCE_KELVIN, temperature)
            .putInt(KEY_WHITE_BALANCE_TINT, trim)
            .apply()
        controller.setManualWhiteBalance(temperature, trim)
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

    override fun setAnalysisOverlayVisible(visible: Boolean) {
        if (analysisOverlayVisible == visible) return
        analysisOverlayVisible = visible
        pushTelemetry(analysis = visible)
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
        val option = lensOptions().firstOrNull { it.id == id && !it.logical }
        if (option != null && (!option.exposed && !option.openable)) return
        requestLensSwitch(id, allowUnlisted = option != null && !option.exposed)
    }

    /**
     * A logical-camera stop switches the session only when the logical camera
     * is not active, then drives CONTROL_ZOOM_RATIO. Once the logical camera is
     * active the HAL hands off between physical lenses with no session work.
     */
    override fun selectLensStop(id: String, ratio: Double) {
        val option = lensOptions().firstOrNull {
            it.logical && it.id == id && kotlin.math.abs(it.stopRatio - ratio) < 0.01
        } ?: return
        if (lastState?.cameraId == id) {
            android.util.Log.i(
                "SlateCam",
                "lens stop camera=$id ratio=${option.stopRatio} active=${lastState?.cameraId}",
            )
            controller.setZoomRatio(option.stopRatio.toFloat())
            return
        }
        android.util.Log.i(
            "SlateCam",
            "lens stop switch camera=$id ratio=${option.stopRatio}",
        )
        if (requestLensSwitch(id, allowUnlisted = true)) {
            pendingZoom = id to option.stopRatio.toFloat()
        }
    }

    /**
     * In movie mode a lens switch during recording is allowed only when the
     * target camera supports the active common mode. Nothing closes the
     * recorder; in single mode a switch while recording is rejected instead of
     * silently ending the clip.
     */
    private fun requestLensSwitch(id: String, allowUnlisted: Boolean): Boolean {
        if (id == lastState?.cameraId) return false
        val caps = CameraCapabilitiesReader.read(context, id)
        if (caps == null) {
            recordingError = "cannot read capabilities for camera $id"
            pushState()
            return false
        }

        val current = activeMode()
        val targetModes = RecordingModeCatalog.modesFor(caps, tenBitVerifier(id))
        val compatible = targetModes.firstOrNull { it.id == current.id && it.enabled }

        if (recorder.isRecording) {
            if (shootMode != SHOOT_MOVIE) {
                recordingError = "stop recording or enable Movie mode to switch lenses"
                pushState()
                return false
            }
            if (id !in shootCameraIds()) {
                recordingError = "lens $id is not part of the movie lens set"
                pushState()
                return false
            }
            if (compatible == null) {
                recordingError = "lens $id cannot match ${current.label}"
                pushState()
                return false
            }
            recordingError = null
            beginSwitchPump(id)
            controller.setCameraId(id, allowUnlisted = true)
            pushState()
            return true
        }

        // Not recording: switch the mode with the lens when the current one is
        // not supported. This is the fragmentation fix: never ask a new camera
        // for a size, rate or bit depth it does not have.
        if (compatible == null) {
            val fallback = targetModes.firstOrNull {
                it.enabled && it.width == 1440 && it.height == 1080 &&
                    it.fps == 30 && it.tenBit
            } ?: targetModes.firstOrNull {
                it.enabled && it.width == 1440 && it.height == 1080 &&
                    it.fps == 30
            } ?: targetModes.firstOrNull {
                it.enabled && it.width == 1440 && it.height == 1080
            } ?: targetModes.firstOrNull {
                it.enabled && it.width == 1920 && it.height == 1080 &&
                    it.fps == 30 && it.tenBit
            } ?: targetModes.firstOrNull {
                it.enabled && it.width == 1920 && it.height == 1080
            } ?: targetModes.firstOrNull { it.enabled }
            if (fallback == null) {
                recordingError = "camera $id has no usable recording mode"
                pushState()
                return false
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
            return true
        }
        recordingError = null
        controller.setCameraId(id, allowUnlisted)
        pushState()
        return true
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
        if (!isKnownProfile(profileId)) return
        applyProfileById(profileId)
        pushState()
    }

    override fun setProfileSettings(settings: PigeonProfileSettings) {
        val core = settings.toCore().normalized()
        if (!pictureProfiles.updateSettings(profileId, core)) {
            // Built-in profiles are read-only.
            pushState()
            return
        }
        persistPictureProfiles()
        applyProfileById(profileId)
        pushState()
    }

    override fun resetProfile() {
        if (!pictureProfiles.resetSettings(profileId)) return
        persistPictureProfiles()
        applyProfileById(profileId)
        pushState()
    }

    override fun resetProfileParameter(key: String) {
        if (!pictureProfiles.resetParameter(profileId, key)) return
        persistPictureProfiles()
        applyProfileById(profileId)
        pushState()
    }

    override fun createPictureProfile(name: String): String {
        var id: String
        do {
            id = "look-${UUID.randomUUID().toString().take(8)}"
        } while (pictureProfiles.get(id) != null)
        pictureProfiles.add(id, name, CoreProfileSettings.IDENTITY)
        persistPictureProfiles()
        applyProfileById(id)
        pushState()
        return id
    }

    override fun renamePictureProfile(id: String, name: String) {
        if (!pictureProfiles.rename(id, name)) return
        persistPictureProfiles()
        if (profileId == id) applyProfileById(id)
        pushState()
    }

    override fun deletePictureProfile(id: String) {
        if (!pictureProfiles.delete(id)) return
        shootPrefs.edit()
            .remove(profileNameKey(id))
            .remove(profileSettingsKey(id))
            .apply()
        persistPictureProfiles()
        if (profileId == id) applyProfileById(PictureProfile.STANDARD.id)
        pushState()
    }

    override fun setCreativeLut(lutId: String) {
        creativeLutId = lutId.takeIf { id ->
            id != "none" && lutEntries.any { it.id == id }
        }
        shootPrefs.edit().apply {
            if (creativeLutId == null) remove(KEY_CREATIVE_LUT)
            else putString(KEY_CREATIVE_LUT, creativeLutId)
        }.apply()
        applyCreativeLut()
        pushState()
    }

    private fun persistPictureProfiles() {
        persistPictureProfiles(pictureProfiles)
    }

    private fun persistPictureProfiles(library: PictureProfileLibrary) {
        val editor = shootPrefs.edit()
        val profiles = library.all()
        editor.putString(KEY_PICTURE_PROFILE_IDS, profiles.joinToString(",") { it.id })
        profiles.forEach { profile ->
            editor.putString(profileNameKey(profile.id), profile.name)
            editor.putString(profileSettingsKey(profile.id), encodeSettings(profile.settings))
        }
        editor.apply()
    }

    private fun loadPictureProfiles(): PictureProfileLibrary {
        val library = PictureProfileLibrary()
        val rawIds = shootPrefs.getString(KEY_PICTURE_PROFILE_IDS, null)
        if (rawIds == null) {
            // One-time seed: keep the pre-library custom look as "Custom".
            library.add("custom", "Custom", decodeSettings(shootPrefs.getString(KEY_CUSTOM_PROFILE, null)))
            persistPictureProfiles(library)
            shootPrefs.edit().remove(KEY_CUSTOM_PROFILE).apply()
            return library
        }
        val restored = rawIds.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { id ->
                val name = shootPrefs.getString(profileNameKey(id), null) ?: return@mapNotNull null
                val settings = decodeSettings(shootPrefs.getString(profileSettingsKey(id), null))
                UserPictureProfile(id, name, settings)
            }
        library.restore(restored)
        if (library.all().isEmpty()) {
            library.add("custom", "Custom", CoreProfileSettings.IDENTITY)
            persistPictureProfiles(library)
        }
        return library
    }

    private fun loadActiveProfileId(): String {
        val saved = shootPrefs.getString(KEY_PROFILE, null) ?: return PictureProfile.STANDARD.id
        return if (isKnownProfile(saved)) saved else PictureProfile.STANDARD.id
    }

    private fun isKnownProfile(id: String): Boolean =
        id == PictureProfile.STANDARD.id ||
            id == PictureProfile.SLATE_LOOK.id ||
            pictureProfiles.get(id) != null

    private fun profileNameKey(id: String): String = "picture_profile_name_$id"

    private fun profileSettingsKey(id: String): String = "picture_profile_settings_$id"

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
        stopAudioMonitor()
        stopAudioTicker()
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

    override fun listMediaClips(): List<MediaClip> = mediaLibrary.list().map { clip ->
        MediaClip(
            id = clip.id,
            name = clip.name,
            uri = clip.uri,
            durationMs = clip.durationMs,
            sizeBytes = clip.sizeBytes,
            width = clip.width.toLong(),
            height = clip.height.toLong(),
            dateAddedMs = clip.dateAddedMs,
        )
    }

    override fun mediaStorageInfo(): MediaStorageInfo {
        val info = mediaLibrary.storage()
        return MediaStorageInfo(
            freeBytes = info.freeBytes,
            totalBytes = info.totalBytes,
        )
    }

    override fun mediaClipThumbnail(id: String): String? = mediaLibrary.thumbnail(id)

    override fun deleteMediaClip(id: String): Boolean = mediaLibrary.delete(id)

    override fun shareMediaClip(id: String): Boolean = mediaLibrary.share(id)

    override fun listAudioInputs(): List<AudioInputOption> {
        refreshAudioOptions()
        return audioInputOptions
    }

    override fun setAudioInput(key: String) {
        if (recorder.isRecording) {
            audioError = "Stop recording to change the audio input."
            pushState()
            return
        }
        audioInputKey = key.ifBlank { AudioInputs.DEFAULT_KEY }
        shootPrefs.edit().putString(KEY_AUDIO_INPUT, audioInputKey).apply()
        audioError = null
        restartAudioMonitor()
        pushState()
    }

    override fun setAudioGain(db: Double) {
        audioGainDb = db.toFloat().coerceIn(MIN_AUDIO_GAIN_DB, MAX_AUDIO_GAIN_DB)
        shootPrefs.edit().putFloat(KEY_AUDIO_GAIN, audioGainDb).apply()
        audioMonitor.setGain(audioGainDb)
        if (recorder.isRecording) recorder.setAudioGain(audioGainDb)
        pushState()
    }

    override fun refreshAudioInputs() {
        refreshAudioOptions()
        if (opened && !recorder.isRecording) restartAudioMonitor()
        pushState()
    }

    private fun refreshAudioOptions() {
        val options = mutableListOf(
            AudioInputOption(
                key = AudioInputs.DEFAULT_KEY,
                name = "System default",
                type = "Automatic",
                detail = "platform route",
            ),
        )
        AudioInputs.list(context).forEach { input ->
            options += AudioInputOption(
                key = input.key,
                name = input.name,
                type = input.type,
                detail = input.detail,
            )
        }
        audioInputOptions = options
    }

    private fun restartAudioMonitor() {
        stopAudioMonitor()
        startAudioMonitor()
    }

    /**
     * Hotplug: refresh the choices, fall back to the platform route when the
     * selected device disappeared, and restart metering. Never interrupts a
     * take; the recorder keeps its route until stop.
     */
    private fun onAudioDevicesChanged() {
        refreshAudioOptions()
        if (!recorder.isRecording &&
            audioInputKey != AudioInputs.DEFAULT_KEY &&
            audioInputOptions.none { it.key == audioInputKey }
        ) {
            audioInputKey = AudioInputs.DEFAULT_KEY
            shootPrefs.edit().putString(KEY_AUDIO_INPUT, audioInputKey).apply()
        }
        if (opened && !recorder.isRecording) restartAudioMonitor()
        pushState()
    }

    private fun startAudioMonitor() {
        if (!opened || recorder.isRecording) return
        if (!AudioInputs.hasPermission(context)) {
            audioActive = false
            audioError = "Microphone permission is not granted."
            return
        }
        audioMonitor.onLevel = { level -> audioLevel = level }
        audioMonitor.onFailure = { message ->
            mainHandler.post {
                audioActive = false
                audioLevel = AudioLevel.SILENT
                audioError = message
                pushState()
            }
        }
        audioMonitor.start(audioInputKey, audioGainDb)
        audioActive = audioMonitor.isRunning
        audioSource = audioMonitor.sourceLabel
        audioError = if (audioActive) null else "Audio input is unavailable."
        startAudioTicker()
    }

    private fun stopAudioMonitor() {
        audioMonitor.onLevel = null
        audioMonitor.onFailure = null
        audioMonitor.stop()
        audioActive = false
        audioSource = null
        audioLevel = AudioLevel.SILENT
    }

    private fun startAudioTicker() {
        if (audioTicker) return
        audioTicker = true
        mainHandler.post(audioTick)
    }

    private fun stopAudioTicker() {
        audioTicker = false
        mainHandler.removeCallbacks(audioTick)
    }

    private fun pushAudio() {
        val api = flutterApi ?: return
        val level = audioLevel
        val telemetry = AudioTelemetry(
            peakDbL = level.peakDbL.toDouble(),
            peakDbR = level.peakDbR.toDouble(),
            rmsDbL = level.rmsDbL.toDouble(),
            rmsDbR = level.rmsDbR.toDouble(),
            clipped = level.clipped,
            active = audioActive,
        )
        scope.launch { runCatching { api.onAudio(telemetry) } }
    }

    fun setProbeMode(enabled: Boolean) {
        probeMode = enabled
    }

    private fun refreshLuts() {
        lutEntries = LutLibrary.scan(File(context.filesDir, "luts"))
        if (creativeLutId != null && lutEntries.none { it.id == creativeLutId }) {
            creativeLutId = null
            shootPrefs.edit().remove(KEY_CREATIVE_LUT).apply()
        }
        applyCreativeLut()
    }

    private fun applyProfileById(id: String) {
        val user = pictureProfiles.get(id)
        val profile = if (user != null) {
            PictureProfile(user.id, ProfileKind.CUSTOM, user.settings)
        } else {
            PictureProfile.byId(id)
        }
        applyProfile(profile)
    }

    private fun activeSettings(): CoreProfileSettings =
        pictureProfiles.get(profileId)?.settings ?: PictureProfile.byId(profileId).settings

    private fun pictureProfileInfos(): List<PictureProfileInfo> = buildList {
        add(PictureProfileInfo(PictureProfile.STANDARD.id, "Standard", true))
        add(PictureProfileInfo(PictureProfile.SLATE_LOOK.id, "SlateLook", true))
        pictureProfiles.all().forEach { profile ->
            add(PictureProfileInfo(profile.id, profile.name, false))
        }
    }

    private fun applyCreativeLut() {
        val entry = creativeLutId?.let { id -> lutEntries.firstOrNull { it.id == id } }
        if (entry != null) {
            renderer.setLut(entry.lut.size, entry.lut.copyData())
        } else {
            renderer.setLut(0, null)
        }
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
        shootPrefs.edit().putString(KEY_PROFILE, profile.id).apply()
        val look = profile.render()
        renderer.applyProfile(
            blackLevel = look.blackLevel,
            gammaPower = look.gammaPower,
            gammaContrast = look.gammaContrast,
            gammaToe = look.gammaToe,
            gammaShoulder = look.gammaShoulder,
            blackGammaWidth = look.blackGammaWidth,
            blackGammaLevel = look.blackGammaLevel,
            kneePoint = look.kneePoint,
            kneeSlope = look.kneeSlope,
            colorMatrix = look.colorMatrix,
            colorOps = look.colorOps,
            colorPhase = look.colorPhase,
            colorDepths = look.colorDepths,
            saturation = look.saturation,
            monochrome = look.monochrome,
        )
        applyCreativeLut()
    }

    /**
     * Manual white balance keeps a HAL preset as the colour base and applies
     * only the kelvin/tint residual in the scene-linear shader. Computing the
     * residual here keeps the camera module free of renderer concerns and the
     * Flutter UI free of colour maths.
     */
    private fun applyManualWhiteBalance(state: CameraUiState) {
        if (state.whiteBalanceMode != ManualWhiteBalance.WIRE_VALUE) {
            renderer.setManualWhiteBalance(1f, 1f)
            return
        }
        val preset = ManualWhiteBalance.presetFor(
            state.whiteBalanceKelvin,
            state.whiteBalanceModes,
        ) ?: run {
            renderer.setManualWhiteBalance(1f, 1f)
            return
        }
        val gains = ManualWhiteBalance.residualGains(
            state.whiteBalanceKelvin,
            state.whiteBalanceTint,
            preset.cct,
        )
        renderer.setManualWhiteBalance(gains.red, gains.blue)
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
        refreshAudioOptions()
        startAudioMonitor()
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
            stopAudioMonitor()
            recorder.onAudioLevel = { level -> audioLevel = level }
            recorder.onAudioFailure = { message ->
                audioActive = false
                audioError = message
                mainHandler.post { pushState() }
            }
            recorder.setAudioGain(audioGainDb)
            val started = startRecorder(name, mode)
            audioError = if (recorder.audioStarted) {
                null
            } else {
                "Recording without audio: microphone is unavailable or permission was denied."
            }
            audioActive = recorder.audioStarted
            audioSource = recorder.audioSource
            startAudioTicker()
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
            recorder.onAudioLevel = null
            recorder.onAudioFailure = null
            audioLevel = AudioLevel.SILENT
            audioActive = false
            audioSource = null
            mainHandler.post {
                if (opened) startAudioMonitor()
                pushState()
            }
        }, "slatecam-finalize").start()

        pushState()
    }

    fun dispose() {
        stopSwitchPump()
        stopAudioMonitor()
        stopAudioTicker()
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
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
                audio = ClipRecorder.AudioConfig(audioInputKey, audioGainDb),
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
                    audio = ClipRecorder.AudioConfig(audioInputKey, audioGainDb),
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
        stopAudioMonitor()
        stopAudioTicker()
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
     * One selectable logical camera. The primary is the eligible back logical
     * camera with the widest range; a physical camera duplicate is never
     * preferred over its logical parent.
     */
    private fun primaryLogicalCamera(): CameraInfo? {
        val cameras = deviceSnapshot.probeReport?.cameras ?: return null
        return cameras
            .asSequence()
            // Hidden logical IDs may open once but disappear after another
            // session starts; only an exposed logical camera is safe to drive
            // the no-reopen path. A hidden candidate needs the verdict first.
            .filter { it.facing == "back" && it.exposed }
            .filter { it.id != failedLogicalId }
            .filter { it.logicalZoom?.handoffEligible == true }
            .maxWithOrNull(
                compareBy<CameraInfo> { if (it.role == "default") 1 else 0 }
                    .thenBy {
                        (it.logicalZoom?.ratioRange?.max ?: 0f) -
                            (it.logicalZoom?.ratioRange?.min ?: 0f)
                    }
                    .thenBy { it.id },
            )
    }

    /**
     * On first open prefer the logical camera so the lens chips and recording
     * start on the session that can hand off without reopening. A physical
     * lens already covered by that session migrates to it; macro and other
     * uncovered lenses keep their own session.
     */
    private fun openingCameraId(): String? {
        val logical = primaryLogicalCamera()
        val current = lastCameraId
        if (logical == null) {
            return if (current != null && current == failedLogicalId) {
                fallbackBackCameraId()
            } else {
                null
            }
        }
        if (current == null) return logical.id
        if (current == logical.id) return null
        val covered = logical.logicalZoom?.stops.orEmpty().any {
            it.physicalId == current
        }
        return if (covered) logical.id else null
    }

    /**
     * Back lenses offered by the zoom control. A logical multi-camera becomes
     * one option per reachable zoom stop; physical lenses it covers are not
     * duplicated. Macro and uncovered lenses stay as session-switch fallbacks.
     */
    private fun lensOptions(): List<LensOption> {
        val cameras = deviceSnapshot.probeReport?.cameras ?: return emptyList()
        val logical = primaryLogicalCamera()
        val covered = mutableSetOf<String>()
        val options = mutableListOf<LensOption>()

        if (logical != null) {
            logical.logicalZoom?.stops.orEmpty()
                .filter { it.role != "macro" }
                .sortedBy { it.ratio }
                .forEach { stop ->
                    if (stop.physicalId.isNotBlank()) covered += stop.physicalId
                    val physical = logical.physicalCameras.firstOrNull {
                        it.id == stop.physicalId
                    }
                    val focal = physical?.focalLength35mmEquiv
                        ?: if (stop.role == "default") logical.focalLength35mmEquiv else null
                    options += LensOption(
                        id = logical.id,
                        label = lensStopLabel(stop.ratio, focal),
                        role = stop.role,
                        facing = logical.facing,
                        zoomRatio = stop.ratio,
                        focalLength35mm = focal ?: 0.0,
                        openable = logical.openable ?: logical.exposed,
                        exposed = logical.exposed,
                        logical = true,
                        stopRatio = stop.ratio,
                        physicalCameraId = stop.physicalId,
                    )
                }
        }

        val seen = mutableSetOf<String>()
        cameras
            .asSequence()
            .filter { it.facing == "back" }
            .filter { it.role in LENS_ROLES }
            // A hidden macro is a secondary fixed-focus sensor. Opening it
            // invalidates the vendor camera list for the process (POCO X6 Pro
            // camera 3); keep it out of the session-switch UI until a stability
            // verdict exists. Hidden ultrawide/tele remain eligible.
            .filter { it.exposed || (it.openable == true && it.role != "macro") }
            .filter { it.id !in covered }
            .filter { it.id != logical?.id }
            .filter { !it.logicalMultiCamera }
            .sortedWith(
                compareBy<CameraInfo> { if (it.role == "macro") 1 else 0 }
                    .thenBy { it.intrinsicZoomRatio ?: 1.0 },
            )
            .filter { camera ->
                val key = camera.role + ":" +
                    ((camera.focalLength35mmEquiv ?: 0.0).roundToInt())
                seen.add(key)
            }
            .forEach { camera ->
                options += LensOption(
                    id = camera.id,
                    label = lensLabel(camera),
                    role = camera.role,
                    facing = camera.facing,
                    zoomRatio = camera.intrinsicZoomRatio ?: 1.0,
                    focalLength35mm = camera.focalLength35mmEquiv ?: 0.0,
                    openable = camera.openable ?: camera.exposed,
                    exposed = camera.exposed,
                    logical = false,
                    stopRatio = 0.0,
                    physicalCameraId = camera.id,
                )
            }

        return options.sortedWith(
            compareBy<LensOption> { if (it.role == "macro") 1 else 0 }
                .thenBy { it.zoomRatio },
        )
    }

    private fun fallbackBackCameraId(): String? {
        val cameras = deviceSnapshot.probeReport?.cameras ?: return null
        return cameras
            .asSequence()
            .filter { it.facing == "back" && it.exposed }
            .filter { it.role != "macro" }
            .sortedWith(
                compareBy<CameraInfo> { if (it.role == "default") 0 else 1 }
                    .thenBy { kotlin.math.abs((it.intrinsicZoomRatio ?: 1.0) - 1.0) }
                    .thenBy { it.id },
            )
            .firstOrNull()
            ?.id
    }

    private fun lensStopLabel(ratio: Double, focalMm: Double?): String {
        val ratioText = if (ratio >= 10.0) {
            "${ratio.roundToInt()}×"
        } else {
            String.format(Locale.US, "%.1f×", ratio)
        }
        val millimeters = focalMm?.roundToInt() ?: 0
        return if (millimeters > 0) "$ratioText · ${millimeters}mm" else ratioText
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

    /**
     * The default shoot mode is 4:3 1080p at 30 fps, preferring a probe-verified
     * 10-bit path. Every step below is capability-gated, so a camera that does
     * not offer 1440×1080 still lands on an enabled mode instead of a guess.
     */
    private fun preferredDefault(modes: List<RecordingMode>): RecordingMode? {
        fun pick(width: Long, height: Long, tenBit: Boolean? = null) =
            modes.firstOrNull {
                it.enabled && it.width == width && it.height == height &&
                    it.fps == 30L && (tenBit == null || it.tenBit == tenBit)
            }

        return pick(1440, 1080, tenBit = true)
            ?: pick(1440, 1080)
            ?: pick(1920, 1080, tenBit = true)
            ?: pick(1920, 1080)
            ?: pick(1920, 1440)
            ?: modes.firstOrNull { it.enabled }
    }

    private fun activeMode(): RecordingMode {
        val modes = recordingModes()
        return modes.firstOrNull { it.id == selectedModeId }
            ?: preferredDefault(modes)
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

    /**
     * Small, high-frequency snapshot for the viewfinder readouts. The full
     * [CameraState] is too heavy to send at analysis/exposure rates: its
     * serialisation blocked the platform thread long enough for the preview to
     * miss presentation slots. Configuration changes still use [pushState].
     */
    private fun pushTelemetry(analysis: Boolean = false) {
        val api = flutterApi ?: return
        val state = lastState ?: return
        val elapsedSeconds = if (recorder.isRecording) {
            ((SystemClock.elapsedRealtime() - recordingStartMs) / 1_000).toInt()
        } else {
            0
        }
        val grant = tenBitGate(state.cameraId)
        val telemetry = CameraTelemetry(
            iso = state.iso.toLong(),
            shutterNs = state.shutterNs,
            shutterAngle = state.shutterAngle.toDouble(),
            focusDiopters = state.focusDiopters.toDouble(),
            whiteBalanceMode = state.whiteBalanceMode,
            whiteBalanceKelvin = state.whiteBalanceKelvin.toLong(),
            whiteBalanceTint = state.whiteBalanceTint.toLong(),
            zoomRatio = state.zoomRatio.toDouble(),
            isRecording = recorder.isRecording,
            recordingSeconds = elapsedSeconds.toLong(),
            clipPath = clipPath,
            recordingError = recordingError ?: recorder.lastError,
            frameRate = state.frameRate.toLong(),
            gyroSampleCount = gyroSampleCount.toLong(),
            exposureMode = state.exposureMode,
            meteringSpeed = state.meteringSpeed.toDouble(),
            zebra = zebraEnabled,
            falseColor = falseColorEnabled,
            peaking = peakingEnabled,
            peakingSensitivity = peakingSensitivity.toDouble(),
            peakingStrength = peakingStrength.toDouble(),
            tenBit = state.isTenBit || (recorder.isRecording && activeRecordingTenBit),
            hlgAvailable = grant.available,
            hlgReason = grant.reason,
            histogram = if (analysisOverlayVisible && analysis) {
                histogramBins.map { it.toLong() }
            } else {
                emptyList()
            },
            waveform = if (analysisOverlayVisible && analysis) {
                waveformBins.map { it.toLong() }
            } else {
                emptyList()
            },
            waveformColumns = WAVEFORM_COLUMNS.toLong(),
            waveformBins = WAVEFORM_BINS.toLong(),
        )
        scope.launch { runCatching { api.onTelemetry(telemetry) } }
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
        activePhysicalCameraId = activePhysicalCameraId,
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
        whiteBalanceKelvin = whiteBalanceKelvin.toLong(),
        whiteBalanceTint = whiteBalanceTint.toLong(),
        manualWhiteBalanceSupported = manualWhiteBalanceSupported,
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
        pictureProfiles = pictureProfileInfos(),
        profileSettings = activeSettings().toPigeon(),
        creativeLut = creativeLutId ?: "none",
        creativeLuts = listOf("none") + lutEntries.map { it.id },
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
        audioInputs = audioInputOptions,
        audioInputKey = audioInputKey,
        audioGainDb = audioGainDb.toDouble(),
        audioPermissionGranted = AudioInputs.hasPermission(context),
        audioAvailable = audioActive,
        audioError = audioError,
        audioSource = audioSource,
        audioSampleRate = AudioSpec.SAMPLE_RATE.toLong(),
        audioChannels = AudioSpec.CHANNELS.toLong(),
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
        private const val KEY_WHITE_BALANCE = "white_balance_mode"
        private const val KEY_WHITE_BALANCE_KELVIN = "white_balance_kelvin"
        private const val KEY_WHITE_BALANCE_TINT = "white_balance_tint"
        private const val KEY_FOCUS_SCENARIO = "focus_scenario"
        private const val KEY_FOCUS_SENSITIVITY = "focus_shift_sensitivity"
        private const val KEY_FOCUS_SPEED = "focus_transition_speed"
        private const val KEY_FOCUS_SOFTWARE = "focus_software"
        private const val KEY_PREVIEW_QUALITY = "preview_quality"
        private const val KEY_CUSTOM_PROFILE = "custom_picture_profile"
        private const val KEY_PICTURE_PROFILE_IDS = "picture_profile_ids"
        private const val KEY_CREATIVE_LUT = "creative_lut"
        private const val KEY_PROFILE = "picture_profile"
        private const val KEY_AUDIO_INPUT = "audio_input"
        private const val KEY_AUDIO_GAIN = "audio_gain_db"
        private const val MIN_AUDIO_GAIN_DB = -12f
        private const val MAX_AUDIO_GAIN_DB = 24f
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

private fun CoreProfileSettings.toPigeon(): PigeonProfileSettings = PigeonProfileSettings(
    blackLevel = blackLevel.toLong(),
    gamma = gamma.wire,
    blackGammaRange = blackGammaRange.wire,
    blackGammaLevel = blackGammaLevel.toLong(),
    kneeMode = kneeMode.wire,
    kneeAutoMaxPoint = kneeAutoMaxPoint.toLong(),
    kneeAutoSensitivity = kneeAutoSensitivity.wire,
    kneePoint = kneePoint.toLong(),
    kneeSlope = kneeSlope.toLong(),
    colorMode = colorMode.wire,
    saturation = saturation.toLong(),
    colorPhase = colorPhase.toLong(),
    colorDepthR = colorDepthR.toLong(),
    colorDepthG = colorDepthG.toLong(),
    colorDepthB = colorDepthB.toLong(),
    colorDepthC = colorDepthC.toLong(),
    colorDepthM = colorDepthM.toLong(),
    colorDepthY = colorDepthY.toLong(),
)

private fun PigeonProfileSettings.toCore(): CoreProfileSettings = CoreProfileSettings(
    blackLevel = blackLevel.toInt(),
    gamma = GammaPreset.fromWire(gamma),
    blackGammaRange = BlackGammaRange.fromWire(blackGammaRange),
    blackGammaLevel = blackGammaLevel.toInt(),
    kneeMode = KneeMode.fromWire(kneeMode),
    kneeAutoMaxPoint = kneeAutoMaxPoint.toInt(),
    kneeAutoSensitivity = KneeSensitivity.fromWire(kneeAutoSensitivity),
    kneePoint = kneePoint.toInt(),
    kneeSlope = kneeSlope.toInt(),
    colorMode = ColorMode.fromWire(colorMode),
    saturation = saturation.toInt(),
    colorPhase = colorPhase.toInt(),
    colorDepthR = colorDepthR.toInt(),
    colorDepthG = colorDepthG.toInt(),
    colorDepthB = colorDepthB.toInt(),
    colorDepthC = colorDepthC.toInt(),
    colorDepthM = colorDepthM.toInt(),
    colorDepthY = colorDepthY.toInt(),
)

private fun encodeSettings(settings: CoreProfileSettings): String = listOf(
    settings.blackLevel,
    settings.gamma.wire,
    settings.blackGammaRange.wire,
    settings.blackGammaLevel,
    settings.kneeMode.wire,
    settings.kneeAutoMaxPoint,
    settings.kneeAutoSensitivity.wire,
    settings.kneePoint,
    settings.kneeSlope,
    settings.colorMode.wire,
    settings.saturation,
    settings.colorPhase,
    settings.colorDepthR,
    settings.colorDepthG,
    settings.colorDepthB,
    settings.colorDepthC,
    settings.colorDepthM,
    settings.colorDepthY,
).joinToString("|")

private fun decodeSettings(raw: String?): CoreProfileSettings {
    if (raw.isNullOrBlank()) return CoreProfileSettings.IDENTITY
    val parts = raw.split("|")
    if (parts.size != 18) return CoreProfileSettings.IDENTITY
    return runCatching {
        CoreProfileSettings(
            blackLevel = parts[0].toInt(),
            gamma = GammaPreset.fromWire(parts[1]),
            blackGammaRange = BlackGammaRange.fromWire(parts[2]),
            blackGammaLevel = parts[3].toInt(),
            kneeMode = KneeMode.fromWire(parts[4]),
            kneeAutoMaxPoint = parts[5].toInt(),
            kneeAutoSensitivity = KneeSensitivity.fromWire(parts[6]),
            kneePoint = parts[7].toInt(),
            kneeSlope = parts[8].toInt(),
            colorMode = ColorMode.fromWire(parts[9]),
            saturation = parts[10].toInt(),
            colorPhase = parts[11].toInt(),
            colorDepthR = parts[12].toInt(),
            colorDepthG = parts[13].toInt(),
            colorDepthB = parts[14].toInt(),
            colorDepthC = parts[15].toInt(),
            colorDepthM = parts[16].toInt(),
            colorDepthY = parts[17].toInt(),
        ).normalized()
    }.getOrDefault(CoreProfileSettings.IDENTITY)
}
