package io.github.vishalsng112.slatecam.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
        val directory = File(context.filesDir, "recordings").apply { mkdirs() }
        val output = File(directory, "slatecam-${System.currentTimeMillis()}.mp4")

        try {
            var tenBitHlg = hlgEnabled && controller.isTenBitActive
            val encoderSurface = runCatching {
                recorder.start(
                    width = size.width,
                    height = size.height,
                    fps = 30,
                    bitrate = 12_000_000,
                    output = output,
                    tenBitHlg = tenBitHlg,
                )
            }.getOrElse {
                // Main10 configure can be refused even when HLG10 capture works.
                runCatching { recorder.stop() }
                tenBitHlg = false
                recorder.start(
                    width = size.width,
                    height = size.height,
                    fps = 30,
                    bitrate = 12_000_000,
                    output = output,
                    tenBitHlg = false,
                )
            }

            renderer.attachEncoderSurface(encoderSurface, tenBitHlg)
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
            val file = recorder.stop()
            clipPath = file?.absolutePath
            recorder.lastError?.let { recordingError = it }
            mainHandler.post { pushState() }
        }, "slatecam-finalize").start()

        pushState()
    }

    fun dispose() {
        if (recorder.isRecording) {
            renderer.detachEncoderSurface()
            recorder.stop()
        }
        thermalListener?.let { listener ->
            runCatching { powerManager?.removeThermalStatusListener(listener) }
        }
        controller.shutdown()
        renderer.close()
        producer.release()
        scope.cancel()
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
