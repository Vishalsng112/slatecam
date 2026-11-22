package io.github.vishalsng112.slatecam.bridge

import android.content.ContentValues
import android.content.Context
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
import io.github.vishalsng112.slatecam.bridge.pigeon.SlateCamFlutterApi
import io.github.vishalsng112.slatecam.bridge.pigeon.SlateCamHostApi
import io.github.vishalsng112.slatecam.core.camera.CameraController
import io.github.vishalsng112.slatecam.core.camera.CameraUiState
import io.github.vishalsng112.slatecam.core.codec.ClipRecorder
import io.github.vishalsng112.slatecam.core.gpu.GlPreviewRenderer
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

    /**
     * HLG recording is disabled until the encoder's bitstream is verified to be
     * Main10. On the POCO the encoder reports profile=2 but emits 8-bit Main,
     * so tagging HLG would be false advertising.
     */
    private val hlgEnabled = false

    private var flutterApi: SlateCamFlutterApi? = null
    private var opened = false
    private var resumeOnReturn = false
    private var lastState: CameraUiState? = null

    private var recordingStartMs = 0L
    private var clipPath: String? = null
    private var recordingError: String? = null

    private var activeDestination: Destination? = null
    private var pendingPfd: ParcelFileDescriptor? = null

    private var powerManager: PowerManager? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    private val tick = object : Runnable {
        override fun run() {
            if (recorder.isRecording) {
                pushState()
                mainHandler.postDelayed(this, 1_000)
            }
        }
    }

    init {
        controller.listener = CameraController.Listener { state ->
            lastState = state
            renderer.setTenBitSource(state.isTenBit)
            pushState()
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
            val size = CameraController.choosePreviewSize(context)
            producer.setSize(size.width, size.height)
            renderer.start(outputSurface(), size.width, size.height)
            val cameraSurface = renderer.cameraSurface
                ?: throw IllegalStateException("GL pipeline did not expose a camera surface")
            controller.open(cameraSurface, size.width, size.height, tenBit = hlgEnabled)
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

    override fun setFocusDiopters(diopters: Double) {
        controller.setFocusDiopters(diopters.toFloat())
    }

    override fun setWhiteBalanceMode(mode: String) {
        controller.setWhiteBalanceMode(mode)
    }

    override fun setAutoExposure() {
        controller.setAutoExposure()
    }

    override fun setAutoFocus() {
        controller.setAutoFocus()
    }

    override fun startRecording() {
        if (!opened) {
            recordingError = "camera is not open"
            pushState()
            return
        }
        if (recorder.isRecording) return

        val size = CameraController.choosePreviewSize(context)
        val name = "SlateCam_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"

        try {
            val started = startRecorder(name, size.width, size.height)
            activeDestination = started.destination
            renderer.attachEncoderSurface(started.surface, started.tenBitHlg)
            clipPath = null
            recordingError = null
            recordingStartMs = SystemClock.elapsedRealtime()
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
            commitDestination(activeDestination, finalized)
            activeDestination = null
            mainHandler.post { pushState() }
        }, "slatecam-finalize").start()

        pushState()
    }

    fun dispose() {
        if (recorder.isRecording) {
            renderer.detachEncoderSurface()
            val destination = activeDestination
            val finalized = recorder.stop()
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

    private fun startRecorder(name: String, width: Int, height: Int): StartedRecorder {
        var tenBitHlg = hlgEnabled && controller.isTenBitActive
        var destination = openDestination(name)

        val surface = try {
            recorder.start(width, height, FPS, VIDEO_BITRATE, tenBitHlg, destination.muxer)
        } catch (first: Throwable) {
            // Main10 configure can be refused; retry as 8-bit with a clean sink.
            abandon(destination)
            tenBitHlg = false
            destination = openDestination(name)
            try {
                recorder.start(width, height, FPS, VIDEO_BITRATE, false, destination.muxer)
            } catch (second: Throwable) {
                abandon(destination)
                throw second
            }
        }

        return StartedRecorder(surface, destination, tenBitHlg)
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
        if (recorder.isRecording) stopRecording()
        resumeOnReturn = opened
        controller.close()
        opened = false
    }

    /** Reopens the camera if it was open before backgrounding. */
    fun resume() {
        if (!resumeOnReturn || opened) return
        resumeOnReturn = false
        val surface = renderer.cameraSurface ?: return
        val size = CameraController.choosePreviewSize(context)
        controller.open(surface, size.width, size.height, tenBit = hlgEnabled)
        opened = true
    }

    private fun pushState() {
        val api = flutterApi ?: return
        val state = lastState ?: return
        val elapsedSeconds = if (recorder.isRecording) {
            ((SystemClock.elapsedRealtime() - recordingStartMs) / 1_000).toInt()
        } else {
            0
        }
        val pigeon = state.toPigeon(
            isRecording = recorder.isRecording,
            recordingSeconds = elapsedSeconds,
            clipPath = clipPath,
            recordingError = recordingError ?: recorder.lastError,
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
    ) = CameraState(
        isOpen = isOpen,
        error = error,
        iso = iso.toLong(),
        isoMin = isoMin.toLong(),
        isoMax = isoMax.toLong(),
        shutterNs = shutterNs,
        shutterMinNs = shutterMinNs,
        shutterMaxNs = shutterMaxNs,
        focusDiopters = focusDiopters.toDouble(),
        focusMin = focusMin.toDouble(),
        focusMax = focusMax.toDouble(),
        whiteBalanceMode = whiteBalanceMode,
        whiteBalanceModes = whiteBalanceModes,
        autoExposure = autoExposure,
        autoFocus = autoFocus,
        previewWidth = previewWidth.toLong(),
        previewHeight = previewHeight.toLong(),
        previewRotationDegrees = previewRotationDegrees.toLong(),
        isRecording = isRecording,
        recordingSeconds = recordingSeconds.toLong(),
        clipPath = clipPath,
        recordingError = recordingError,
    )

    companion object {
        private const val FPS = 30
        private const val VIDEO_BITRATE = 12_000_000

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
            return bridge
        }
    }
}
