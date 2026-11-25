package io.github.vishalsng112.slatecam.core.gpu

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 2 preview pipeline: camera OES texture -> simple shader -> the surface
 * owned by Flutter's SurfaceProducer.
 *
 * All GL work happens on one thread with one EGL context. The camera never
 * touches the output surface directly, which keeps the single-pixel-path rule
 * intact for Phase 3.
 */
class GlPreviewRenderer(private val onFrameRendered: () -> Unit) : AutoCloseable {

    private val thread = HandlerThread("slatecam-gl").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var outputEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesTextureId = 0
    private var secondaryOesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var secondarySurfaceTexture: SurfaceTexture? = null
    private var program = 0
    private var uTexMatrix = 0
    private var uRotation = 0
    private var uTexture = 0
    private var uInputHlg = 0
    private var uOutputHlg = 0
    private var uIsPreview = 0
    private var uBlackLevel = 0
    private var uGammaPower = 0
    private var uGammaContrast = 0
    private var uGammaToe = 0
    private var uGammaShoulder = 0
    private var uBlackGammaWidth = 0
    private var uBlackGammaLevel = 0
    private var uKneePoint = 0
    private var uKneeSlope = 0
    private var uColorMatrix = 0
    private var uColorOps = 0
    private var uColorPhase = 0
    private var uColorDepthA = 0
    private var uColorDepthB = 0
    private var uSaturation = 0
    private var uMono = 0
    private var uZebra = 0
    private var uZebraThreshold = 0
    private var uFalseColor = 0
    private var uInputMatrix = 0
    private var uInputExposure = 0
    private var uInputSaturation = 0
    private var uInputWb = 0
    private var uInputShadowLift = 0
    private var uPeaking = 0
    private var uPeakingThreshold = 0
    private var uPeakingStrength = 0
    private var uTexelSize = 0
    private var uLut = 0
    private var uLutEnabled = 0
    private var uLutSize = 0

    private var lutTexture = 0
    private var lutSize = 0f
    private var lutEnabled = false
    private var pendingLutSize = 0
    private var pendingLutData: FloatArray? = null

    private var intermediateFbo = 0
    private var intermediateTexture = 0
    private var intermediateWidth = 0
    private var intermediateHeight = 0
    private var intermediateEnabled = false
    private var intermediateAttempted = false
    private var copyProgram = 0
    private var copyPosition = 0
    private var copyTexCoord = 0
    private var copyTexture = 0

    private var analysisFbo = 0
    private var analysisTexture = 0
    private var frameCounter = 0
    private var firstFrameLogged = false
    private val lumaFrame = FloatArray(ANALYSIS_WIDTH * ANALYSIS_HEIGHT)

    @Volatile
    private var analysisEnabled = false

    /** Called on the GL thread with histogram, waveform and metering grid. */
    var onAnalysis: ((FrameAnalysis) -> Unit)? = null
    private var aPosition = 0
    private var aTexCoord = 0
    private var texMatrix = FloatArray(16)
    private val primaryTexMatrix = FloatArray(16)
    private val secondaryTexMatrix = FloatArray(16)

    /** Copy of the live SurfaceTexture transform for viewfinder tap mapping. */
    @Volatile
    private var texMatrixSnapshot: FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    /** Normalised centre of the metering focus ROI in viewfinder coordinates. */
    @Volatile private var focusRoiX = 0.5f
    @Volatile private var focusRoiY = 0.5f

    private var captureWidth = 1920
    private var captureHeight = 1080
    private var previewWidth = 1920
    private var previewHeight = 1080
    private var started = false

    @Volatile
    private var tenBitSource = false
    private var encoderFrames = 0

    @Volatile
    private var cameraFrames = 0L
    private var lastFrameTimestampNs = 0L

    /**
     * Preview presentation is paced to the display: the camera listener marks a
     * frame pending and exactly one draw runs on the next vsync. Drawing on
     * arrival instead let Flutter's texture path replace frames before they
     * were composited, which showed up as irregular holds in the viewfinder.
     */
    private var choreographer: Choreographer? = null
    private var vsyncScheduled = false
    @Volatile private var framePending = false
    /** While recording, every camera frame must reach the encoder. */
    @Volatile private var encoderActive = false
    private val vsyncCallback = Choreographer.FrameCallback {
        vsyncScheduled = false
        if (!started || !framePending) return@FrameCallback
        framePending = false
        drawFrame()
    }

    private val encoderTimestamps = ArrayList<Long>(512)

    @Volatile
    private var captureEncoderTimestamps = false

    /**
     * MediaCodec surface input treats the EGL presentation time as the sample
     * PTS. Camera timestamps are absolute (since boot), and passing them through
     * unchanged produces MP4 durations measured from the boot epoch. Normalise
     * the encoder PTS against the first submitted frame so every clip starts at
     * t=0. The original camera timestamp is still recorded for gyro alignment.
     */
    private var encoderBaseTimestampNs = -1L

    @Volatile private var blackLevel = 0f
    @Volatile private var gammaPower = 1f
    @Volatile private var gammaContrast = 1f
    @Volatile private var gammaToe = 0f
    @Volatile private var gammaShoulder = 0f
    @Volatile private var blackGammaWidth = 0.18f
    @Volatile private var blackGammaLevel = 0f
    @Volatile private var kneePoint = 1f
    @Volatile private var kneeSlope = 0f
    @Volatile private var colorMatrixLook = floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
    @Volatile private var colorOps = false
    @Volatile private var colorPhase = 0f
    @Volatile private var colorDepths = FloatArray(6)
    @Volatile private var saturation = 1f
    @Volatile private var monochrome = false
    @Volatile private var zebra = false
    @Volatile private var zebraThreshold = 0.9f
    @Volatile private var falseColor = false
    @Volatile private var displayRotation = 0
    @Volatile private var glRotation = 0
    @Volatile private var encoderRotation = 0
    @Volatile private var inputMatrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    @Volatile private var inputExposure = 1f
    @Volatile private var inputSaturation = 1f
    @Volatile private var inputWbR = 1f
    @Volatile private var inputWbB = 1f
    // Manual white-balance residual, multiplied with the per-lens calibration.
    @Volatile private var manualWbR = 1f
    @Volatile private var manualWbB = 1f
    @Volatile private var inputShadowLift = 0f
    @Volatile private var peaking = false
    @Volatile private var peakingThreshold = 0.12f
    @Volatile private var peakingStrength = 0.8f

    fun setPeaking(enabled: Boolean, sensitivity: Float, strength: Float) {
        peaking = enabled
        // Higher sensitivity lowers the edge threshold.
        peakingThreshold = 0.30f - 0.28f * sensitivity.coerceIn(0f, 1f)
        peakingStrength = strength.coerceIn(0.1f, 1f)
    }

    /**
     * Per-device normalisation, applied before the creative look. Identity by
     * default; calibration data arrives in Phase 7.
     */
    fun setInputProfile(
        matrix: FloatArray,
        exposure: Float,
        saturation: Float,
        wbROffset: Float = 1f,
        wbBOffset: Float = 1f,
        shadowLift: Float = 0f,
    ) {
        require(matrix.size == 9) { "colour matrix must be 3x3" }
        inputMatrix = matrix.copyOf()
        inputExposure = exposure
        inputSaturation = saturation
        inputWbR = wbROffset
        inputWbB = wbBOffset
        inputShadowLift = shadowLift
    }

    /**
     * Manual white-balance residual on top of the camera's preset balance.
     * Applied in the same scene-linear stage as the per-lens input calibration.
     */
    fun setManualWhiteBalance(red: Float, blue: Float) {
        manualWbR = red.coerceIn(0.1f, 10f)
        manualWbB = blue.coerceIn(0.1f, 10f)
    }

    /**
     * Applies the resolved picture-profile look. Values are already mapped from
     * the user-facing ranges by [io.github.vishalsng112.slatecam.core.profile].
     */
    @Suppress("LongParameterList")
    fun applyProfile(
        blackLevel: Float,
        gammaPower: Float,
        gammaContrast: Float,
        gammaToe: Float,
        gammaShoulder: Float,
        blackGammaWidth: Float,
        blackGammaLevel: Float,
        kneePoint: Float,
        kneeSlope: Float,
        colorMatrix: FloatArray,
        colorOps: Boolean,
        colorPhase: Float,
        colorDepths: FloatArray,
        saturation: Float,
        monochrome: Boolean,
    ) {
        require(colorMatrix.size == 9) { "colour matrix must be 3x3" }
        require(colorDepths.size == 6) { "colour depth must have six axes" }
        this.blackLevel = blackLevel
        this.gammaPower = gammaPower
        this.gammaContrast = gammaContrast
        this.gammaToe = gammaToe
        this.gammaShoulder = gammaShoulder
        this.blackGammaWidth = blackGammaWidth
        this.blackGammaLevel = blackGammaLevel
        this.kneePoint = kneePoint
        this.kneeSlope = kneeSlope
        this.colorMatrixLook = colorMatrix.copyOf()
        this.colorOps = colorOps
        this.colorPhase = colorPhase
        this.colorDepths = colorDepths.copyOf()
        this.saturation = saturation
        this.monochrome = monochrome
        Log.i(
            TAG,
            "profile black=$blackLevel gamma=${gammaPower}/${gammaContrast}/${gammaShoulder} " +
                "bg=$blackGammaLevel knee=$kneePoint/$kneeSlope " +
                "sat=$saturation mono=$monochrome colorOps=$colorOps",
        )
    }

    fun setZebra(enabled: Boolean, threshold: Float) {
        zebra = enabled
        zebraThreshold = threshold.coerceIn(0.3f, 1f)
    }

    fun setAnalysisEnabled(enabled: Boolean) {
        analysisEnabled = enabled
    }

    fun setFalseColor(enabled: Boolean) {
        falseColor = enabled
    }

    fun setDisplayRotation(degrees: Int) {
        displayRotation = ((degrees % 360) + 360) % 360
    }

    /** Rotation the shader applies so encoded and preview pixels are upright. */
    fun setGlRotation(degrees: Int) {
        glRotation = ((degrees % 360) + 360) % 360
    }

    /** Fixed landscape rotation used for the encoder frame. */
    fun setEncoderRotation(degrees: Int) {
        encoderRotation = ((degrees % 360) + 360) % 360
    }

    /** Records the timestamp submitted with each encoded frame. */
    fun startEncoderTimestampCapture() {
        synchronized(encoderTimestamps) {
            encoderTimestamps.clear()
            encoderBaseTimestampNs = -1L
            captureEncoderTimestamps = true
        }
    }

    fun stopEncoderTimestampCapture(): LongArray {
        captureEncoderTimestamps = false
        synchronized(encoderTimestamps) {
            return encoderTimestamps.toLongArray()
        }
    }

    fun setLut(size: Int, data: FloatArray?) {
        handler.post { uploadLut(size, data) }
    }

    private fun uploadLut(size: Int, data: FloatArray?) {
        if (!started) {
            // The EGL context does not exist yet; defer until start().
            pendingLutSize = size
            pendingLutData = data
            return
        }
        if (lutTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(lutTexture), 0)
            lutTexture = 0
        }
        lutEnabled = false
        if (data == null || size <= 1) return

        val n = size
        val pixels = IntArray(n * n * n)
        for (b in 0 until n) {
            for (g in 0 until n) {
                for (r in 0 until n) {
                    val source = ((b * n + g) * n + r) * 3
                    val red = (data[source] * 255f).toInt().coerceIn(0, 255)
                    val green = (data[source + 1] * 255f).toInt().coerceIn(0, 255)
                    val blue = (data[source + 2] * 255f).toInt().coerceIn(0, 255)
                    pixels[g * n * n + b * n + r] =
                        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, n * n, n, Bitmap.Config.ARGB_8888)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        lutTexture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()

        lutSize = n.toFloat()
        lutEnabled = true
        Log.i(TAG, "LUT uploaded: ${n}x${n}x$n")
    }

    /** Surface the camera must target. Available after [start]. */
    @Volatile
    var cameraSurface: Surface? = null
    var secondaryCameraSurface: Surface? = null

    @Volatile
    private var activeCameraIndex = 0

    val activeCameraSurface: Surface?
        get() = if (activeCameraIndex == 0) cameraSurface else secondaryCameraSurface

    /** Changes the camera stream size (the encoder follows this size). */
    fun setCaptureSize(width: Int, height: Int) {
        handler.post {
            captureWidth = width
            captureHeight = height
            surfaceTexture?.setDefaultBufferSize(width, height)
            secondarySurfaceTexture?.setDefaultBufferSize(width, height)
            releaseIntermediate()
            intermediateAttempted = false
        }
    }

    /**
     * Changes only the preview output size. The camera stream and encoder keep
     * the capture size; the preview is a GL downsample of the same frame.
     */
    fun setPreviewSize(width: Int, height: Int) {
        handler.post {
            previewWidth = width
            previewHeight = height
        }
    }

    /** Moves the software-focus ROI; coordinates are viewfinder 0..1. */
    fun setFocusRoi(x: Float, y: Float) {
        focusRoiX = x.coerceIn(0f, 1f)
        focusRoiY = y.coerceIn(0f, 1f)
    }

    /**
     * Maps a viewfinder tap (0..1, displayed image) to the camera-buffer
     * coordinate that the current frame samples. The bridge uses this for
     * `CONTROL_AF_REGIONS`; it removes any guesswork about sensor rotation.
     */
    fun viewfinderToBuffer(x: Float, y: Float): FloatArray {
        val matrix = texMatrixSnapshot
        val tx = matrix[0] * x + matrix[4] * y + matrix[12]
        val ty = matrix[1] * x + matrix[5] * y + matrix[13]
        val radians = Math.toRadians(glRotation.toDouble())
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()
        val px = tx - 0.5f
        val py = ty - 0.5f
        val rx = px * cos - py * sin
        val ry = px * sin + py * cos
        return floatArrayOf(
            (rx + 0.5f).coerceIn(0f, 1f),
            (ry + 0.5f).coerceIn(0f, 1f),
        )
    }

    fun start(
        target: Surface,
        captureWidth: Int,
        captureHeight: Int,
        previewWidth: Int,
        previewHeight: Int,
    ) {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null

        handler.post {
            try {
                // A retry after a failed open must start from a clean slate.
                if (started) teardown()
                this.captureWidth = captureWidth
                this.captureHeight = captureHeight
                this.previewWidth = previewWidth
                this.previewHeight = previewHeight
                setupEgl(target)
                setupProgram()
                setupCopyProgram()
                setupTexture()
                started = true
                Log.i(
                    TAG,
                    "started camera=${captureWidth}x$captureHeight " +
                        "preview=${previewWidth}x$previewHeight " +
                        "previewSurface=${eglSurfaceSize(outputEglSurface)}",
                )
                pendingLutData?.let { data ->
                    val size = pendingLutSize
                    pendingLutData = null
                    uploadLut(size, data)
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IllegalStateException("GL preview start timed out")
        }
        failure?.let { throw it }
    }

    /**
     * Drops every GL/EGL object but keeps the render thread alive, so a failed
     * open can be followed by a clean [start].
     */
    fun reset() {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                teardown()
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    /** Re-targets rendering when Flutter recreates the producer surface
     * (for example after the app returns to the foreground).
     */
    fun updateTarget(target: Surface) {
        handler.post {
            if (!started) return@post
            val previous = outputEglSurface
            if (previous != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                EGL14.eglDestroySurface(display, previous)
            }
            outputEglSurface = EGL14.eglCreateWindowSurface(
                display,
                config,
                target,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            EGL14.eglMakeCurrent(display, outputEglSurface, outputEglSurface, context)
            Log.i(TAG, "output retargeted ${eglSurfaceSize(outputEglSurface)}")
        }
    }

    /** True when the camera delivers an HLG10 stream that preview must tonemap. */
    fun setTenBitSource(value: Boolean) {
        tenBitSource = value
    }

    /** Adds an encoder input surface; every frame is then drawn to both targets. */
    fun attachEncoderSurface(surface: Surface, tenBitHlg: Boolean) {
        handler.post {
            if (!started) return@post
            detachEncoderInternal()

            val targetConfig = if (tenBitHlg) {
                pickHlgSurfaceConfig() ?: config
            } else {
                config
            }
            // The encoder surface does not need the EGL BT2020/HLG attribute on
            // this driver; attaching it makes eglSwapBuffers fail with
            // EGL_BAD_SURFACE. MediaFormat carries the colour signalling instead,
            // matching the prober's verified encode path.
            val attributes = intArrayOf(EGL14.EGL_NONE)

            encoderEglSurface = EGL14.eglCreateWindowSurface(
                display,
                targetConfig,
                surface,
                attributes,
                0,
            )
            if (encoderEglSurface == EGL14.EGL_NO_SURFACE) {
                Log.w(
                    TAG,
                    "encoder surface failed, error 0x${Integer.toHexString(EGL14.eglGetError())}",
                )
            } else {
                encoderFrames = 0
                encoderActive = true
                Log.i(
                    TAG,
                    "encoder surface attached (tenBit=$tenBitHlg) " +
                        "size=${eglSurfaceSize(encoderEglSurface)} camera=${captureWidth}x$captureHeight",
                )
            }
        }
    }

    private fun eglSurfaceSize(surface: EGLSurface): String {
        if (surface == EGL14.EGL_NO_SURFACE) return "none"
        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, h, 0)
        return "${w[0]}x${h[0]}"
    }

    /**
     * The prober's working HLG encode uses a plain, non-recordable RGBA8888
     * config. On this driver the encoder's 10-bit input surface rejects a
     * recordable config at swap time.
     */
    private fun pickHlgSurfaceConfig(): EGLConfig? {
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val chosen = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) &&
            count[0] > 0
        if (!chosen) {
            Log.w(TAG, "no HLG encoder EGL config")
            return null
        }
        Log.i(TAG, "HLG encoder EGL config RGBA=8/8/8/8")
        return configs[0]
    }

    private fun pickTenBitConfig(): EGLConfig? {
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 10,
            EGL14.EGL_GREEN_SIZE, 10,
            EGL14.EGL_BLUE_SIZE, 10,
            EGL14.EGL_ALPHA_SIZE, 2,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_COLOR_BUFFER_TYPE, EGL14.EGL_RGB_BUFFER,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val chosen = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) &&
            count[0] > 0
        if (!chosen) {
            Log.w(TAG, "no 10-bit EGL config")
            return null
        }
        val config = configs[0] ?: return null
        val sizes = IntArray(4)
        EGL14.eglGetConfigAttrib(display, config, EGL14.EGL_RED_SIZE, sizes, 0)
        EGL14.eglGetConfigAttrib(display, config, EGL14.EGL_GREEN_SIZE, sizes, 1)
        EGL14.eglGetConfigAttrib(display, config, EGL14.EGL_BLUE_SIZE, sizes, 2)
        EGL14.eglGetConfigAttrib(display, config, EGL14.EGL_ALPHA_SIZE, sizes, 3)
        Log.i(TAG, "10-bit EGL config RGBA=${sizes.joinToString("/")}")
        return config
    }

    fun detachEncoderSurface() {
        handler.post { detachEncoderInternal() }
    }

    private fun detachEncoderInternal() {
        if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, encoderEglSurface)
            encoderEglSurface = EGL14.EGL_NO_SURFACE
            encoderActive = false
            // Keep a valid drawable bound so the preview keeps rendering.
            EGL14.eglMakeCurrent(
                display,
                outputEglSurface,
                outputEglSurface,
                context,
            )
        }
    }

    override fun close() {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                teardown()
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun setupEgl(target: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            // MediaCodec input surfaces require a recordable config on some
            // drivers; without it the encoder receives uninitialised buffers.
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        var chosen = EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, count, 0) &&
            count[0] > 0
        if (!chosen) {
            val fallback = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE,
            )
            chosen = EGL14.eglChooseConfig(display, fallback, 0, configs, 0, 1, count, 0) &&
                count[0] > 0
            Log.w(TAG, "EGL_RECORDABLE_ANDROID unsupported; encoder output may be wrong")
        }
        check(chosen) { "no suitable EGL config" }
        config = configs[0]

        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        outputEglSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            target,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(outputEglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, outputEglSurface, outputEglSurface, context)) {
            "eglMakeCurrent failed"
        }
    }

    private fun setupProgram() {
        val vertex = compile(ShaderSource.VERTEX, GLES20.GL_VERTEX_SHADER)
        val fragment = compile(ShaderSource.FRAGMENT, GLES20.GL_FRAGMENT_SHADER)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "program link failed: ${GLES20.glGetProgramInfoLog(program)}"
        }

        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uRotation = GLES20.glGetUniformLocation(program, "uRotation")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        uInputHlg = GLES20.glGetUniformLocation(program, "uInputHlg")
        uOutputHlg = GLES20.glGetUniformLocation(program, "uOutputHlg")
        uIsPreview = GLES20.glGetUniformLocation(program, "uIsPreview")
        uBlackLevel = GLES20.glGetUniformLocation(program, "uBlackLevel")
        uGammaPower = GLES20.glGetUniformLocation(program, "uGammaPower")
        uGammaContrast = GLES20.glGetUniformLocation(program, "uGammaContrast")
        uGammaToe = GLES20.glGetUniformLocation(program, "uGammaToe")
        uGammaShoulder = GLES20.glGetUniformLocation(program, "uGammaShoulder")
        uBlackGammaWidth = GLES20.glGetUniformLocation(program, "uBlackGammaWidth")
        uBlackGammaLevel = GLES20.glGetUniformLocation(program, "uBlackGammaLevel")
        uKneePoint = GLES20.glGetUniformLocation(program, "uKneePoint")
        uKneeSlope = GLES20.glGetUniformLocation(program, "uKneeSlope")
        uColorMatrix = GLES20.glGetUniformLocation(program, "uColorMatrix")
        uColorOps = GLES20.glGetUniformLocation(program, "uColorOps")
        uColorPhase = GLES20.glGetUniformLocation(program, "uColorPhase")
        uColorDepthA = GLES20.glGetUniformLocation(program, "uColorDepthA")
        uColorDepthB = GLES20.glGetUniformLocation(program, "uColorDepthB")
        uSaturation = GLES20.glGetUniformLocation(program, "uSaturation")
        uMono = GLES20.glGetUniformLocation(program, "uMono")
        uZebra = GLES20.glGetUniformLocation(program, "uZebra")
        uZebraThreshold = GLES20.glGetUniformLocation(program, "uZebraThreshold")
        uFalseColor = GLES20.glGetUniformLocation(program, "uFalseColor")
        uInputMatrix = GLES20.glGetUniformLocation(program, "uInputMatrix")
        uInputExposure = GLES20.glGetUniformLocation(program, "uInputExposure")
        uInputSaturation = GLES20.glGetUniformLocation(program, "uInputSaturation")
        uInputWb = GLES20.glGetUniformLocation(program, "uInputWb")
        uInputShadowLift = GLES20.glGetUniformLocation(program, "uInputShadowLift")
        uPeaking = GLES20.glGetUniformLocation(program, "uPeaking")
        uPeakingThreshold = GLES20.glGetUniformLocation(program, "uPeakingThreshold")
        uPeakingStrength = GLES20.glGetUniformLocation(program, "uPeakingStrength")
        uTexelSize = GLES20.glGetUniformLocation(program, "uTexelSize")
        uLut = GLES20.glGetUniformLocation(program, "uLut")
        uLutEnabled = GLES20.glGetUniformLocation(program, "uLutEnabled")
        uLutSize = GLES20.glGetUniformLocation(program, "uLutSize")

        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
    }

    private fun setupTexture() {
        choreographer = Choreographer.getInstance()
        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        oesTextureId = ids[0]
        secondaryOesTextureId = ids[1]
        configureOesTexture(oesTextureId)
        configureOesTexture(secondaryOesTextureId)

        surfaceTexture = createCameraTexture(0, oesTextureId)
        secondarySurfaceTexture = createCameraTexture(1, secondaryOesTextureId)
        cameraSurface = Surface(surfaceTexture)
        secondaryCameraSurface = Surface(secondarySurfaceTexture)
    }

    private fun configureOesTexture(id: Int) {
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
    }

    private fun createCameraTexture(index: Int, textureId: Int): SurfaceTexture {
        val texture = SurfaceTexture(textureId)
        texture.setDefaultBufferSize(captureWidth, captureHeight)
        texture.setOnFrameAvailableListener {
            if (index != activeCameraIndex) {
                handler.post { updateStandbyTexture(index) }
                return@setOnFrameAvailableListener
            }
            if (encoderActive) {
                // No coalescing on the record path: the encoder needs every
                // camera frame, not just the newest one at the next vsync.
                handler.post { if (started) drawFrame() }
                return@setOnFrameAvailableListener
            }
            framePending = true
            handler.post {
                val vsync = choreographer ?: return@post
                if (vsyncScheduled || !started) return@post
                vsyncScheduled = true
                vsync.postFrameCallback(vsyncCallback)
            }
        }
        return texture
    }

    /** Consume standby frames without drawing them into preview, encoder or analysis. */
    private fun updateStandbyTexture(index: Int) {
        val texture = if (index == 0) surfaceTexture else secondarySurfaceTexture
        val matrix = if (index == 0) primaryTexMatrix else secondaryTexMatrix
        runCatching {
            texture?.updateTexImage()
            texture?.getTransformMatrix(matrix)
        }
    }

    /** Select which camera texture feeds preview, encoder and analysis. */
    fun setActiveCamera(index: Int) {
        handler.post {
            activeCameraIndex = if (index == 0) 0 else 1
            texMatrix = if (activeCameraIndex == 0) primaryTexMatrix else secondaryTexMatrix
            // Do not synthesize a frame here: the next standby camera frame
            // carries the real timestamp and keeps the encoder timeline honest.
        }
    }


    /**
     * Frame count from the camera only. A switch pump compares this between
     * ticks to tell a real frame from a repeated one.
     */
    fun cameraFramesRendered(): Long = cameraFrames

    /**
     * Re-renders the last camera frame without waiting for a new one. Used while
     * a lens switch has no camera session, so the encoder keeps a continuous
     * timeline (a frozen frame) instead of dropping samples.
     */
    fun repeatLastFrame() {
        handler.post { drawFrame(updateTexture = false) }
    }

    private fun drawFrame(updateTexture: Boolean = true) {
        val index = activeCameraIndex
        val texture = if (index == 0) surfaceTexture else secondarySurfaceTexture
        texture ?: return
        val matrix = if (index == 0) primaryTexMatrix else secondaryTexMatrix

        val timestampNs: Long
        if (updateTexture) {
            runCatching {
                texture.updateTexImage()
                texture.getTransformMatrix(matrix)
                texMatrix = matrix
                texMatrixSnapshot = texMatrix.copyOf()
            }.onFailure { return }
            timestampNs = texture.timestamp.takeIf { it > 0L }
                ?: SystemClock.elapsedRealtimeNanos()
            cameraFrames++
        } else {
            if (!started || cameraFrames == 0L) return
            texMatrix = matrix
            timestampNs = SystemClock.elapsedRealtimeNanos()
                .coerceAtLeast(lastFrameTimestampNs + 1_000_000L)
        }
        lastFrameTimestampNs = timestampNs

        if (!firstFrameLogged) {
            firstFrameLogged = true
            Log.i(
                TAG,
                "first frame previewSurface=${eglSurfaceSize(outputEglSurface)} " +
                    "rotation=$glRotation",
            )
        }

        val frameTimestampNs = timestampNs
        renderTo(
            outputEglSurface,
            frameTimestampNs,
            isPreview = true,
            overlay = true,
            frameTimestampNs = frameTimestampNs,
        )
        val encoderTimestampNs = synchronized(encoderTimestamps) {
            if (encoderBaseTimestampNs < 0L) {
                encoderBaseTimestampNs = frameTimestampNs
                0L
            } else {
                (frameTimestampNs - encoderBaseTimestampNs).coerceAtLeast(0L)
            }
        }
        renderEncoder(encoderTimestampNs, frameTimestampNs)

        frameCounter++
        if (analysisEnabled && frameCounter % ANALYSIS_INTERVAL == 0) {
            runAnalysis()
        }

        onFrameRendered()
    }

    private fun drawQuad(
        isPreview: Boolean,
        overlay: Boolean,
        rotationDegrees: Int = glRotation,
    ) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            if (activeCameraIndex == 0) oesTextureId else secondaryOesTextureId,
        )
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
        GLES20.glUniform1i(uLut, 1)
        GLES20.glUniform1i(uLutEnabled, if (lutEnabled) 1 else 0)
        GLES20.glUniform1f(uLutSize, if (lutSize > 0f) lutSize else 2f)
        GLES20.glUniform1i(uInputHlg, if (tenBitSource) 1 else 0)
        GLES20.glUniform1i(uOutputHlg, if (!isPreview && tenBitSource) 1 else 0)
        GLES20.glUniform1i(uIsPreview, if (isPreview) 1 else 0)
        GLES20.glUniform1f(uBlackLevel, blackLevel)
        GLES20.glUniform1f(uGammaPower, gammaPower)
        GLES20.glUniform1f(uGammaContrast, gammaContrast)
        GLES20.glUniform1f(uGammaToe, gammaToe)
        GLES20.glUniform1f(uGammaShoulder, gammaShoulder)
        GLES20.glUniform1f(uBlackGammaWidth, blackGammaWidth)
        GLES20.glUniform1f(uBlackGammaLevel, blackGammaLevel)
        GLES20.glUniform1f(uKneePoint, kneePoint)
        GLES20.glUniform1f(uKneeSlope, kneeSlope)
        GLES20.glUniformMatrix3fv(uColorMatrix, 1, false, colorMatrixLook, 0)
        GLES20.glUniform1i(uColorOps, if (colorOps) 1 else 0)
        GLES20.glUniform1f(uColorPhase, colorPhase)
        GLES20.glUniform3f(uColorDepthA, colorDepths[0], colorDepths[1], colorDepths[2])
        GLES20.glUniform3f(uColorDepthB, colorDepths[3], colorDepths[4], colorDepths[5])
        GLES20.glUniform1f(uSaturation, saturation)
        GLES20.glUniform1i(uMono, if (monochrome) 1 else 0)
        GLES20.glUniform1i(uZebra, if (overlay && zebra) 1 else 0)
        GLES20.glUniform1f(uZebraThreshold, zebraThreshold)
        GLES20.glUniform1i(uFalseColor, if (overlay && falseColor) 1 else 0)
        GLES20.glUniformMatrix3fv(uInputMatrix, 1, false, inputMatrix, 0)
        GLES20.glUniform1f(uInputExposure, inputExposure)
        GLES20.glUniform1f(uInputSaturation, inputSaturation)
        GLES20.glUniform2f(uInputWb, inputWbR * manualWbR, inputWbB * manualWbB)
        GLES20.glUniform1f(uInputShadowLift, inputShadowLift)
        GLES20.glUniform1i(uPeaking, if (overlay && peaking) 1 else 0)
        GLES20.glUniform1f(uPeakingThreshold, peakingThreshold)
        GLES20.glUniform1f(uPeakingStrength, peakingStrength)
        GLES20.glUniform2f(
            uTexelSize,
            1f / captureWidth.coerceAtLeast(1),
            1f / captureHeight.coerceAtLeast(1),
        )
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform1f(uRotation, rotationDegrees.toFloat())

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, QUAD_VERTICES)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, QUAD_TEX_COORDS)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun runAnalysis() {
        val callback = onAnalysis ?: return
        if (!ensureAnalysisTarget()) return

        EGL14.eglMakeCurrent(display, outputEglSurface, outputEglSurface, context)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, analysisFbo)
        GLES20.glViewport(0, 0, ANALYSIS_WIDTH, ANALYSIS_HEIGHT)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(isPreview = false, overlay = false)

        val byteCount = ANALYSIS_WIDTH * ANALYSIS_HEIGHT * 4
        val buffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(
            0,
            0,
            ANALYSIS_WIDTH,
            ANALYSIS_HEIGHT,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            buffer,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        val pixels = ByteArray(byteCount)
        buffer.position(0)
        buffer.get(pixels)

        val histogram = IntArray(HISTOGRAM_BINS)
        val waveform = IntArray(WAVEFORM_COLUMNS * WAVEFORM_BINS)
        val grid = FloatArray(LUMA_GRID_WIDTH * LUMA_GRID_HEIGHT)
        val gridCounts = IntArray(grid.size)

        for (pixel in 0 until ANALYSIS_WIDTH * ANALYSIS_HEIGHT) {
            val offset = pixel * 4
            val r = pixels[offset].toInt() and 0xFF
            val g = pixels[offset + 1].toInt() and 0xFF
            val b = pixels[offset + 2].toInt() and 0xFF
            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            lumaFrame[pixel] = luma

            histogram[(luma * (HISTOGRAM_BINS - 1)).toInt().coerceIn(0, HISTOGRAM_BINS - 1)]++

            val x = pixel % ANALYSIS_WIDTH
            val y = pixel / ANALYSIS_WIDTH
            // The preview is rotated in Dart; match the waveform columns to the
            // orientation the user actually sees.
            val column = when (displayRotation) {
                90 -> y * WAVEFORM_COLUMNS / ANALYSIS_HEIGHT
                180 -> (ANALYSIS_WIDTH - 1 - x) * WAVEFORM_COLUMNS / ANALYSIS_WIDTH
                270 -> (ANALYSIS_HEIGHT - 1 - y) * WAVEFORM_COLUMNS / ANALYSIS_HEIGHT
                else -> x * WAVEFORM_COLUMNS / ANALYSIS_WIDTH
            }.coerceIn(0, WAVEFORM_COLUMNS - 1)
            val bin = (luma * (WAVEFORM_BINS - 1)).toInt().coerceIn(0, WAVEFORM_BINS - 1)
            waveform[bin * WAVEFORM_COLUMNS + column]++

            val gx = (x * LUMA_GRID_WIDTH / ANALYSIS_WIDTH)
                .coerceIn(0, LUMA_GRID_WIDTH - 1)
            val gy = (y * LUMA_GRID_HEIGHT / ANALYSIS_HEIGHT)
                .coerceIn(0, LUMA_GRID_HEIGHT - 1)
            val cell = gy * LUMA_GRID_WIDTH + gx
            grid[cell] += luma
            gridCounts[cell]++
        }

        for (cell in grid.indices) {
            if (gridCounts[cell] > 0) grid[cell] /= gridCounts[cell]
        }

        callback(
            FrameAnalysis(
                histogram = histogram,
                waveform = waveform,
                lumaGrid = grid,
                gridWidth = LUMA_GRID_WIDTH,
                gridHeight = LUMA_GRID_HEIGHT,
                focusScore = focusScore(),
            ),
        )
    }

    /**
     * Laplacian energy in the central 50% of the analysis frame. This is the
     * software-AF sharpness signal: it peaks when the lens is focused and
     * disappears on a flat wall, which is exactly when contrast AF should hold.
     */
    private fun focusScore(): Float {
        val half = FOCUS_ROI_FRACTION / 2f
        val centreX = (focusRoiX * ANALYSIS_WIDTH).toInt()
        val centreY = (focusRoiY * ANALYSIS_HEIGHT).toInt()
        val halfX = (half * ANALYSIS_WIDTH).toInt().coerceAtLeast(1)
        val halfY = (half * ANALYSIS_HEIGHT).toInt().coerceAtLeast(1)
        val left = (centreX - halfX).coerceIn(1, ANALYSIS_WIDTH - 2)
        val right = (centreX + halfX).coerceIn(left + 1, ANALYSIS_WIDTH - 1)
        val top = (centreY - halfY).coerceIn(1, ANALYSIS_HEIGHT - 2)
        val bottom = (centreY + halfY).coerceIn(top + 1, ANALYSIS_HEIGHT - 1)
        var sum = 0f
        var lumaSum = 0f
        var count = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val index = y * ANALYSIS_WIDTH + x
                val center = lumaFrame[index]
                val laplacian = 4f * center -
                    lumaFrame[index - 1] -
                    lumaFrame[index + 1] -
                    lumaFrame[index - ANALYSIS_WIDTH] -
                    lumaFrame[index + ANALYSIS_WIDTH]
                sum += kotlin.math.abs(laplacian)
                lumaSum += center
                count++
            }
        }
        if (count <= 0) return 0f
        // Normalise by mean luma so an exposure change from metering does not
        // look like a focus change to the software AF loop.
        val meanLuma = lumaSum / count
        if (meanLuma < 0.01f) return 0f
        return (sum / count) / meanLuma
    }

    private fun ensureAnalysisTarget(): Boolean {
        if (analysisFbo != 0) return true

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        analysisTexture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, analysisTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            ANALYSIS_WIDTH,
            ANALYSIS_HEIGHT,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )

        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        analysisFbo = framebuffers[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, analysisFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            analysisTexture,
            0,
        )
        val complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
            GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (!complete) {
            Log.w(TAG, "analysis framebuffer incomplete; monitoring disabled")
        }
        return complete
    }

    private fun setupCopyProgram() {
        val vertex = compile(COPY_VERTEX, GLES20.GL_VERTEX_SHADER)
        val fragment = compile(COPY_FRAGMENT, GLES20.GL_FRAGMENT_SHADER)
        copyProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(copyProgram, vertex)
        GLES20.glAttachShader(copyProgram, fragment)
        GLES20.glLinkProgram(copyProgram)
        val status = IntArray(1)
        GLES20.glGetProgramiv(copyProgram, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "copy program link failed: ${GLES20.glGetProgramInfoLog(copyProgram)}"
        }
        copyPosition = GLES20.glGetAttribLocation(copyProgram, "aPosition")
        copyTexCoord = GLES20.glGetAttribLocation(copyProgram, "aTexCoord")
        copyTexture = GLES20.glGetUniformLocation(copyProgram, "uTexture")
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
    }

    /**
     * Renders the camera frame through the creative look into an FP16 FBO and
     * copies that to the encoder. The preview stays on the direct path so HLG
     * preview tone-mapping and overlays keep their existing semantics.
     *
     * The encoder input surface is still RGBA8888 on the test devices; this is a
     * processing-precision step, not true 10-bit delivery. The copy dithers the
     * final 8-bit write to reduce banding.
     */
    private fun renderEncoder(timestampNs: Long, frameTimestampNs: Long) {
        if (encoderEglSurface == EGL14.EGL_NO_SURFACE) return
        if (!ensureIntermediate()) {
            renderTo(
                encoderEglSurface,
                timestampNs,
                isPreview = false,
                overlay = false,
                frameTimestampNs = frameTimestampNs,
            )
            return
        }

        // Render the camera + look into FP16. The output surface is only the
        // context owner here; the FBO is the actual draw target.
        val contextSurface = if (outputEglSurface != EGL14.EGL_NO_SURFACE) {
            outputEglSurface
        } else {
            encoderEglSurface
        }
        EGL14.eglMakeCurrent(display, contextSurface, contextSurface, context)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFbo)
        GLES20.glViewport(0, 0, captureWidth, captureHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(isPreview = false, overlay = false, rotationDegrees = encoderRotation)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // Copy the FP16 result to the encoder surface at 1:1.
        EGL14.eglMakeCurrent(display, encoderEglSurface, encoderEglSurface, context)
        GLES20.glViewport(0, 0, captureWidth, captureHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawCopy()

        if (captureEncoderTimestamps) {
            synchronized(encoderTimestamps) { encoderTimestamps += frameTimestampNs }
        }
        EGLExt.eglPresentationTimeANDROID(display, encoderEglSurface, timestampNs)
        if (!EGL14.eglSwapBuffers(display, encoderEglSurface)) {
            Log.w(
                TAG,
                "eglSwapBuffers failed (encoder): 0x${Integer.toHexString(EGL14.eglGetError())}",
            )
        }
        encoderFrames++
        if (encoderFrames % 60 == 0) Log.i(TAG, "encoder frames=$encoderFrames")
    }

    private fun drawCopy() {
        GLES20.glUseProgram(copyProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, intermediateTexture)
        GLES20.glUniform1i(copyTexture, 0)
        GLES20.glEnableVertexAttribArray(copyPosition)
        GLES20.glVertexAttribPointer(copyPosition, 2, GLES20.GL_FLOAT, false, 0, QUAD_VERTICES)
        GLES20.glEnableVertexAttribArray(copyTexCoord)
        GLES20.glVertexAttribPointer(copyTexCoord, 2, GLES20.GL_FLOAT, false, 0, QUAD_TEX_COORDS)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun ensureIntermediate(): Boolean {
        if (intermediateEnabled &&
            intermediateWidth == captureWidth &&
            intermediateHeight == captureHeight
        ) {
            return true
        }
        if (intermediateAttempted &&
            intermediateWidth == captureWidth &&
            intermediateHeight == captureHeight
        ) {
            return intermediateEnabled
        }
        releaseIntermediate()
        intermediateAttempted = true
        intermediateWidth = captureWidth
        intermediateHeight = captureHeight

        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        if (!extensions.contains("GL_EXT_color_buffer_half_float") ||
            !extensions.contains("GL_OES_texture_half_float")
        ) {
            Log.i(TAG, "FP16 intermediate unavailable; using direct encoder path")
            return false
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        intermediateTexture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, intermediateTexture)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GL_RGBA16F_EXT,
            captureWidth,
            captureHeight,
            0,
            GLES20.GL_RGBA,
            GL_HALF_FLOAT_OES,
            null,
        )
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            Log.w(TAG, "FP16 texture allocation failed; using direct encoder path")
            releaseIntermediate()
            return false
        }

        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        intermediateFbo = framebuffers[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            intermediateTexture,
            0,
        )
        val complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
            GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (!complete) {
            Log.w(TAG, "FP16 framebuffer incomplete; using direct encoder path")
            releaseIntermediate()
            return false
        }
        intermediateEnabled = true
        Log.i(
            TAG,
            "FP16 encoder intermediate ${captureWidth}x$captureHeight (RGBA16F)",
        )
        return true
    }

    private fun releaseIntermediate() {
        if (intermediateFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(intermediateFbo), 0)
            intermediateFbo = 0
        }
        if (intermediateTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(intermediateTexture), 0)
            intermediateTexture = 0
        }
        intermediateEnabled = false
    }

    private fun renderTo(
        surface: EGLSurface,
        timestampNs: Long,
        isPreview: Boolean,
        overlay: Boolean,
        frameTimestampNs: Long = timestampNs,
    ) {
        if (surface == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(display, surface, surface, context)

        val targetWidth = if (surface == encoderEglSurface) captureWidth else previewWidth
        val targetHeight = if (surface == encoderEglSurface) captureHeight else previewHeight
        GLES20.glViewport(0, 0, targetWidth, targetHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        drawQuad(
            isPreview,
            overlay,
            rotationDegrees = if (surface == encoderEglSurface) encoderRotation else glRotation,
        )

        if (surface == encoderEglSurface) {
            if (captureEncoderTimestamps) {
                synchronized(encoderTimestamps) { encoderTimestamps += frameTimestampNs }
            }
            EGLExt.eglPresentationTimeANDROID(display, surface, timestampNs)
        }
        if (!EGL14.eglSwapBuffers(display, surface)) {
            val target = if (surface == encoderEglSurface) "encoder" else "preview"
            Log.w(
                TAG,
                "eglSwapBuffers failed ($target): 0x${Integer.toHexString(EGL14.eglGetError())}",
            )
        }
        if (surface == encoderEglSurface) {
            encoderFrames++
            if (encoderFrames % 60 == 0) Log.i(TAG, "encoder frames=$encoderFrames")
        }
    }

    private fun teardown() {
        started = false
        encoderActive = false
        framePending = false
        vsyncScheduled = false
        choreographer?.removeFrameCallback(vsyncCallback)
        detachEncoderInternal()
        cameraSurface?.release()
        cameraSurface = null
        secondaryCameraSurface?.release()
        secondaryCameraSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
        secondarySurfaceTexture?.release()
        secondarySurfaceTexture = null
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(
                1,
                intArrayOf(oesTextureId),
                0,
            )
            oesTextureId = 0
        }
        if (secondaryOesTextureId != 0) {
            GLES20.glDeleteTextures(
                1,
                intArrayOf(secondaryOesTextureId),
                0,
            )
            secondaryOesTextureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (copyProgram != 0) {
            GLES20.glDeleteProgram(copyProgram)
            copyProgram = 0
        }
        releaseIntermediate()
        if (lutTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(lutTexture), 0)
            lutTexture = 0
            lutEnabled = false
        }
        if (analysisFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(analysisFbo), 0)
            analysisFbo = 0
        }
        if (analysisTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(analysisTexture), 0)
            analysisTexture = 0
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (outputEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, outputEglSurface)
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
            }
            EGL14.eglTerminate(display)
        }
        outputEglSurface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    private fun compile(source: String, type: Int): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    private object ShaderSource {
        const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            uniform float uRotation;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vec2 uv = (uTexMatrix * aTexCoord).xy;
                float rad = radians(uRotation);
                vec2 p = uv - 0.5;
                uv = vec2(
                    p.x * cos(rad) - p.y * sin(rad),
                    p.x * sin(rad) + p.y * cos(rad)
                ) + 0.5;
                vTexCoord = uv;
            }
        """

        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;

            uniform int uInputHlg;
            uniform int uOutputHlg;
            uniform int uIsPreview;
            uniform float uBlackLevel;
            uniform float uGammaPower;
            uniform float uGammaContrast;
            uniform float uGammaToe;
            uniform float uGammaShoulder;
            uniform float uBlackGammaWidth;
            uniform float uBlackGammaLevel;
            uniform float uKneePoint;
            uniform float uKneeSlope;
            uniform mat3 uColorMatrix;
            uniform int uColorOps;
            uniform float uColorPhase;
            uniform vec3 uColorDepthA;
            uniform vec3 uColorDepthB;
            uniform float uSaturation;
            uniform int uMono;
            uniform int uZebra;
            uniform float uZebraThreshold;
            uniform int uFalseColor;
            uniform mat3 uInputMatrix;
            uniform float uInputExposure;
            uniform float uInputSaturation;
            uniform vec2 uInputWb;
            uniform float uInputShadowLift;
            uniform int uPeaking;
            uniform float uPeakingThreshold;
            uniform float uPeakingStrength;
            uniform vec2 uTexelSize;
            uniform sampler2D uLut;
            uniform int uLutEnabled;
            uniform float uLutSize;

            float luma2020(vec3 c) {
                return dot(c, vec3(0.2627, 0.6780, 0.0593));
            }

            float luma709(vec3 c) {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
            }

            vec3 bt709ToLinear(vec3 v) {
                vec3 x = max(v, vec3(0.0));
                vec3 low = x / 4.5;
                vec3 high = pow(max((x + 0.099) / 1.099, vec3(0.0)),
                                vec3(1.0 / 0.45));
                return mix(low, high, step(vec3(0.081), x));
            }

            vec3 linearToBt709(vec3 v) {
                vec3 x = max(v, vec3(0.0));
                vec3 low = 4.5 * x;
                vec3 high = 1.099 * pow(x, vec3(0.45)) - 0.099;
                return mix(low, high, step(vec3(0.018), x));
            }

            vec3 hlgToLinear(vec3 e) {
                vec3 x = clamp(e, 0.0, 1.0);
                vec3 low = (x * x) / 3.0;
                vec3 high = (exp((x - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
                return mix(low, high, step(vec3(0.5), x));
            }

            vec3 linearToHlg(vec3 v) {
                vec3 x = clamp(v, 0.0, 1.0);
                vec3 low = sqrt(3.0 * x);
                vec3 high = 0.17883277 * log(max(12.0 * x - 0.28466892, vec3(1e-5)))
                    + 0.55991073;
                return mix(low, high, step(vec3(1.0 / 12.0), x));
            }

            // Preview-only HLG display mapping. The HLG inverse yields
            // scene-linear relative to the 1000-nit reference; the OOTF and a
            // 100-nit SDR normalisation are required before BT.709 encoding.
            // Without this the HLG preview sits roughly 40% darker than SDR.
            vec3 hlgPreviewToSdr(vec3 c) {
                float y = max(luma2020(c), 1e-6);
                vec3 display = c * pow(y, 0.2) * 4.93;
                vec3 knee = vec3(0.8);
                vec3 over = max(display - knee, 0.0);
                vec3 shoulder = knee + (1.0 - knee) *
                    (1.0 - exp(-over / (1.0 - knee)));
                return clamp(mix(display, shoulder, step(knee, display)), 0.0, 1.0);
            }

            vec3 bt709To2020(vec3 c) {
                return vec3(
                    dot(c, vec3(0.6274, 0.3293, 0.0433)),
                    dot(c, vec3(0.0691, 0.9195, 0.0114)),
                    dot(c, vec3(0.0164, 0.0880, 0.8956))
                );
            }

            vec3 bt2020To709(vec3 c) {
                return vec3(
                    dot(c, vec3(1.6605, -0.5876, -0.0728)),
                    dot(c, vec3(-0.1246, 1.1329, -0.0083)),
                    dot(c, vec3(-0.0182, -0.1006, 1.1187))
                );
            }

            vec3 applyBlackLevel(vec3 c) {
                return max(c + vec3(uBlackLevel), 0.0);
            }

            vec3 applyBlackGamma(vec3 c) {
                if (abs(uBlackGammaLevel) < 0.001) return c;
                float l = luma2020(c);
                float mask = 1.0 - smoothstep(0.0, max(uBlackGammaWidth, 0.01), l);
                vec3 gamma = vec3(1.0 - clamp(uBlackGammaLevel, -0.5, 0.5));
                return mix(c, pow(max(c, 0.0), gamma), mask);
            }

            vec3 applyKnee(vec3 c) {
                float k = clamp(uKneePoint, 0.5, 1.0);
                if (k >= 0.999) return c;
                vec3 over = max(c - k, 0.0);
                float range = max(1.0 - k, 0.001);
                float strength = clamp(1.0 + uKneeSlope, 0.1, 2.0);
                vec3 compressed = k + range * (1.0 - exp(-over / range * strength));
                return mix(c, compressed, step(vec3(k), c));
            }

            vec3 rgb2hsv(vec3 c) {
                vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
                vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
                vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
                float d = q.x - min(q.w, q.y);
                float e = 1.0e-10;
                return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)),
                            d / (q.x + e), q.x);
            }

            vec3 hsv2rgb(vec3 c) {
                vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
                vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
                return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
            }

            float axisWeight(float h, float center) {
                float d = abs(fract(h - center + 0.5) - 0.5);
                return 1.0 - smoothstep(0.0, 0.18, d);
            }

            vec3 applyColorLook(vec3 c) {
                if (uColorOps == 0) return c;
                c = uColorMatrix * c;
                if (uColorPhase == 0.0 &&
                    uColorDepthA == vec3(0.0) &&
                    uColorDepthB == vec3(0.0)) {
                    return c;
                }
                vec3 hsv = rgb2hsv(max(c, 0.0));
                hsv.x = fract(hsv.x + uColorPhase);
                float depth =
                    uColorDepthA.x * axisWeight(hsv.x, 0.0) +
                    uColorDepthB.z * axisWeight(hsv.x, 1.0 / 6.0) +
                    uColorDepthA.y * axisWeight(hsv.x, 2.0 / 6.0) +
                    uColorDepthB.x * axisWeight(hsv.x, 3.0 / 6.0) +
                    uColorDepthA.z * axisWeight(hsv.x, 4.0 / 6.0) +
                    uColorDepthB.y * axisWeight(hsv.x, 5.0 / 6.0);
                hsv.y = clamp(hsv.y * (1.0 + depth), 0.0, 1.0);
                return hsv2rgb(hsv);
            }

            vec3 applyGammaLook(vec3 c) {
                vec3 g = pow(clamp(c, 0.0, 1.0), vec3(max(uGammaPower, 0.1)));
                g = clamp((g - 0.5) * uGammaContrast + 0.5, 0.0, 1.0);
                g += uGammaToe * (1.0 - g) * (1.0 - g);
                if (uGammaShoulder > 0.001) {
                    float k = 1.0 - clamp(uGammaShoulder, 0.0, 0.9);
                    vec3 over = max(g - k, 0.0);
                    float norm = 1.0 - exp(-1.0);
                    g = min(g, vec3(k)) +
                        (1.0 - k) * (1.0 - exp(-over / (1.0 - k))) / norm;
                }
                return clamp(g, 0.0, 1.0);
            }

            vec3 applyLut(vec3 c) {
                float n = uLutSize;
                float slice = clamp(c.b, 0.0, 1.0) * (n - 1.0);
                float b0 = floor(slice);
                float b1 = min(b0 + 1.0, n - 1.0);
                float blend = slice - b0;
                float red = clamp(c.r, 0.0, 1.0) * (n - 1.0);
                float y = (clamp(c.g, 0.0, 1.0) * (n - 1.0) + 0.5) / n;
                vec2 uv0 = vec2((b0 * n + red + 0.5) / (n * n), y);
                vec2 uv1 = vec2((b1 * n + red + 0.5) / (n * n), y);
                return mix(texture2D(uLut, uv0).rgb, texture2D(uLut, uv1).rgb, blend);
            }

            void main() {
                vec4 src = texture2D(uTexture, vTexCoord);

                // 1. Input EOTF to scene-linear. HLG is BT.2020; SDR is BT.709
                // and is converted into the BT.2020 working space.
                vec3 c;
                if (uInputHlg == 1) {
                    c = hlgToLinear(src.rgb);
                } else {
                    c = bt709To2020(bt709ToLinear(src.rgb));
                }

                // 2. Per-lens normalisation in linear working space.
                c.r *= uInputWb.x;
                c.b *= uInputWb.y;
                c = uInputMatrix * c;
                c *= uInputExposure;
                c += vec3(uInputShadowLift);
                float inputLuma = luma2020(c);
                c = mix(vec3(inputLuma), c, uInputSaturation);

                // 3. Picture-profile look in the linear working space.
                c = applyBlackLevel(c);
                c = applyBlackGamma(c);
                c = applyColorLook(c);
                float profileLuma = luma2020(c);
                c = mix(vec3(profileLuma), c, uSaturation);
                c = applyKnee(c);

                if (uMono == 1) {
                    c = vec3(luma2020(c));
                }

                // 4/5. Output routing and creative LUT in the delivery domain.
                if (uIsPreview == 1) {
                    if (uInputHlg == 1) {
                        c = hlgPreviewToSdr(c);
                    }
                    vec3 displayLinear = bt2020To709(c);
                    c = linearToBt709(clamp(displayLinear, 0.0, 1.0));
                } else if (uOutputHlg == 1) {
                    c = linearToHlg(clamp(c, 0.0, 1.0));
                } else {
                    c = linearToBt709(clamp(bt2020To709(c), 0.0, 1.0));
                }
                c = clamp(c, 0.0, 1.0);

                // 6. Look gamma then the display-referred creative LUT.
                c = applyGammaLook(c);
                if (uLutEnabled == 1) {
                    c = applyLut(c);
                }

                if (uZebra == 1 && luma709(c) >= uZebraThreshold) {
                    float stripe = step(0.5, fract((gl_FragCoord.x + gl_FragCoord.y) * 0.06));
                    c = mix(c, vec3(1.0, 0.1, 0.1), stripe * 0.8);
                }

                if (uFalseColor == 1) {
                    float level = luma709(c);
                    if (level < 0.02) {
                        c = vec3(0.35, 0.0, 0.5);
                    } else if (level < 0.15) {
                        c = vec3(0.0, 0.1, 0.9);
                    } else if (level < 0.40) {
                        c = vec3(0.0, 0.8, 0.0);
                    } else if (level < 0.55) {
                        c = vec3(0.9, 0.9, 0.0);
                    } else if (level < 0.75) {
                        c = vec3(1.0, 0.4, 0.0);
                    } else {
                        c = vec3(1.0, 0.0, 0.0);
                    }
                }

                if (uPeaking == 1) {
                    vec2 uvL = vTexCoord + vec2(-uTexelSize.x, 0.0);
                    vec2 uvR = vTexCoord + vec2(uTexelSize.x, 0.0);
                    vec2 uvT = vTexCoord + vec2(0.0, uTexelSize.y);
                    vec2 uvB = vTexCoord + vec2(0.0, -uTexelSize.y);
                    float l = luma709(texture2D(uTexture, uvL).rgb);
                    float r = luma709(texture2D(uTexture, uvR).rgb);
                    float t = luma709(texture2D(uTexture, uvT).rgb);
                    float b = luma709(texture2D(uTexture, uvB).rgb);
                    float edge = abs(l - r) + abs(t - b);
                    if (edge > uPeakingThreshold) {
                        c = mix(c, vec3(1.0, 0.15, 0.15), uPeakingStrength);
                    }
                }

                gl_FragColor = vec4(c, src.a);
            }
        """
    }

    private companion object {
        const val START_TIMEOUT_SECONDS = 5L
        const val TAG = "SlateCamGl"

        const val ANALYSIS_WIDTH = 320
        const val ANALYSIS_HEIGHT = 180
        const val HISTOGRAM_BINS = 64
        const val WAVEFORM_COLUMNS = 64
        const val WAVEFORM_BINS = 16
        const val LUMA_GRID_WIDTH = 32
        const val LUMA_GRID_HEIGHT = 18
        const val FOCUS_ROI_FRACTION = 0.40f
        // 10 Hz at 30 fps. Each analysis frame costs a synchronous glReadPixels
        // stall on the render thread; the bridge only publishes metering and
        // histogram at 5 Hz, so 15 Hz was spending that stall on frames nobody
        // consumed.
        const val ANALYSIS_INTERVAL = 3

        // Android EGL extension: marks a config usable as a MediaCodec input.
        const val EGL_RECORDABLE_ANDROID = 0x3142

        // Not exposed by android.opengl.EGL14.
        const val EGL_GL_COLORSPACE_KHR = 0x309D
        const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
        const val GL_RGBA16F_EXT = 0x881A
        const val GL_HALF_FLOAT_OES = 0x8D61

        const val COPY_VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord.xy;
            }
        """

        const val COPY_FRAGMENT = """
            precision highp float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                vec3 c = texture2D(uTexture, vTexCoord).rgb;
                // Ordered 2x2 dither before the 8-bit encoder surface.
                float d = mod(gl_FragCoord.x + gl_FragCoord.y, 2.0);
                c += (d - 0.5) * (1.0 / 255.0);
                gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
        """

        val QUAD_VERTICES: FloatBuffer = floatBuffer(
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
        )

        val QUAD_TEX_COORDS: FloatBuffer = floatBuffer(
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
        )

        fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
    }
}
