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
import io.github.vishalsng112.slatecam.bridge.pigeon.SlateCamFlutterApi
import io.github.vishalsng112.slatecam.bridge.pigeon.SlateCamHostApi
import io.github.vishalsng112.slatecam.core.camera.CameraCapabilitiesReader
import io.github.vishalsng112.slatecam.core.camera.CameraController
import io.github.vishalsng112.slatecam.core.camera.CameraUiState
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
    private var lastAppliedProfile: InputProfile = InputProfile.IDENTITY
    private var appliedQuirkIds: Set<String> = emptySet()
    private var deviceReportRunning = false
    private var deviceReportPath: String? = deviceSnapshot.lastReportPath
    private var deviceReportError: String? = null

    /** Selected recording mode id; resolved to a default when null or stale. */
    private var selectedModeId: String? = null

    /** single | movie. Movie uses the common mode across a set of lenses. */
    private var shootMode: String = SHOOT_SINGLE
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
            }
            renderer.setTenBitSource(state.isTenBit)
            renderer.setDisplayRotation(state.previewRotationDegrees)
            renderer.setGlRotation(state.glRotationDegrees)
            pushState()
        }
        applyProfile(PictureProfile.STANDARD)

        renderer.setAnalysisEnabled(true)
        renderer.onAnalysis = { histogram, waveform ->
            // Metering is camera state: every sample goes to the S-mode loop,
            // while the UI copy is throttled below.
            controller.submitMetering(histogram)
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysisPublishMs >= 200L) {
                lastAnalysisPublishMs = now
                histogramBins = histogram.toList()
                waveformBins = waveform.toList()
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
            producer.setSize(width, height)
            renderer.start(outputSurface(), width, height)
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
                producer.setSize(fallback.width, fallback.height)
                renderer.setPreviewSize(fallback.width, fallback.height)
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

    override fun setAutoFocus() {
        controller.setAutoFocus()
    }

    override fun setProfile(profileId: String) {
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

    override fun setDeviceReportOptIn(enabled: Boolean) {
        deviceDatabase.setReportOptIn(enabled)
        deviceSnapshot = deviceDatabase.snapshot(lastCameraId)
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
                    deviceSnapshot = deviceDatabase.snapshot(lastCameraId)
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
        deviceSnapshot = deviceDatabase.snapshot(lastCameraId)
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
                renderer.applyProfile(1f, 1f, 1f, false, false)
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
        renderer.applyProfile(
            exposure = profile.exposure,
            contrast = profile.contrast,
            saturation = profile.saturation,
            logEnabled = profile.logEnabled,
            monochrome = profile.monochrome,
        )
        val lut = profile.lutId?.let(BuiltInLuts::byId)
        if (lut != null) {
            renderer.setLut(lut.size, lut.copyData())
        } else {
            renderer.setLut(0, null)
        }
    }

    private fun refreshDeviceSnapshot(cameraId: String?) {
        deviceSnapshot = deviceDatabase.snapshot(cameraId)
        deviceReportPath = deviceSnapshot.lastReportPath
        applyDeviceSnapshot(deviceSnapshot)
    }

    private fun applyDeviceSnapshot(snapshot: DeviceDatabaseSnapshot) {
        val profile = snapshot.calibration?.toInputProfile() ?: InputProfile.IDENTITY
        if (profile != lastAppliedProfile) {
            lastAppliedProfile = profile
            renderer.setInputProfile(profile.matrixArray(), profile.exposureTrim, profile.saturationTrim)
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
            recordingError = "movie mode needs at least two probe-visible lenses"
            pushState()
            return
        }
        if (recorder.isRecording) stopRecording()
        shootMode = normalized
        selectedModeId = null
        applyMode(activeMode())
        pushState()
    }

    private fun applyMode(mode: RecordingMode) {
        val width = mode.width.toInt()
        val height = mode.height.toInt()
        if (opened) {
            producer.setSize(width, height)
            renderer.setPreviewSize(width, height)
        }
        controller.configure(width, height, mode.fps.toInt(), mode.tenBit)
    }

    private fun recordingModes(): List<RecordingMode> {
        val cacheKey = "$shootMode|${lastCameraId}|${deviceSnapshot.probeReport?.report?.id}"
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
     * Lenses that participate in movie mode: probe-visible, openable, not macro.
     * Logical variants that resolve to the same role/focal length collapse.
     */
    private fun shootCameraIds(): List<String> {
        val report = deviceSnapshot.probeReport ?: return emptyList()
        val seen = mutableSetOf<String>()
        return report.cameras
            .filter { (it.exposed || it.openable == true) && it.role != "macro" }
            .sortedWith(
                compareByDescending<CameraInfo> { it.facing == "back" }
                    .thenBy { it.intrinsicZoomRatio ?: 1.0 },
            )
            .filter { camera ->
                val key = "${camera.facing}:${camera.role}:" +
                    ((camera.focalLength35mmEquiv ?: 0.0).roundToInt())
                seen.add(key)
            }
            .map { it.id }
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
        )
        scope.launch {
            runCatching { api.onState(pigeon) }
        }
    }

    private fun outputSurface(): Surface =
        producer.surface ?: producer.forcedNewSurface

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
        focusDiopters = focusDiopters.toDouble(),
        focusMin = focusMin.toDouble(),
        focusMax = focusMax.toDouble(),
        focusShiftSpeed = focusShiftSpeed.toDouble(),
        whiteBalanceMode = whiteBalanceMode,
        whiteBalanceModes = whiteBalanceModes,
        autoExposure = autoExposure,
        autoFocus = autoFocus,
        autoFocusSupported = autoFocusSupported,
        previewWidth = previewWidth.toLong(),
        previewHeight = previewHeight.toLong(),
        previewRotationDegrees = previewRotationDegrees.toLong(),
        zoomRatio = zoomRatio.toDouble(),
        zoomMin = zoomMin.toDouble(),
        zoomMax = zoomMax.toDouble(),
        lenses = lenses,
        recordingMode = recordingMode,
        recordingModes = recordingModes,
        shootMode = shootMode,
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
        exposureMode = exposureMode,
        exposureModes = exposureModes,
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
