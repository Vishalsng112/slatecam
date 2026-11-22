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
import android.util.Log
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
    private var surfaceTexture: SurfaceTexture? = null
    private var program = 0
    private var uTexMatrix = 0
    private var uTexture = 0
    private var uHlgToSdr = 0
    private var uExposure = 0
    private var uContrast = 0
    private var uSaturation = 0
    private var uLog = 0
    private var uMono = 0
    private var uZebra = 0
    private var uZebraThreshold = 0
    private var uFalseColor = 0
    private var uInputMatrix = 0
    private var uInputExposure = 0
    private var uInputSaturation = 0
    private var uLut = 0
    private var uLutEnabled = 0
    private var uLutSize = 0

    private var lutTexture = 0
    private var lutSize = 0f
    private var lutEnabled = false
    private var pendingLutSize = 0
    private var pendingLutData: FloatArray? = null

    private var analysisFbo = 0
    private var analysisTexture = 0
    private var frameCounter = 0

    @Volatile
    private var analysisEnabled = false

    /** Called on the GL thread with (64-bin luma histogram, 64x16 waveform). */
    var onAnalysis: ((IntArray, IntArray) -> Unit)? = null
    private var aPosition = 0
    private var aTexCoord = 0
    private val texMatrix = FloatArray(16)
    private var width = 1920
    private var height = 1080
    private var started = false

    @Volatile
    private var tenBitSource = false
    private var encoderFrames = 0

    @Volatile private var exposure = 1f
    @Volatile private var contrast = 1f
    @Volatile private var saturation = 1f
    @Volatile private var logEnabled = false
    @Volatile private var monochrome = false
    @Volatile private var zebra = false
    @Volatile private var zebraThreshold = 0.9f
    @Volatile private var falseColor = false
    @Volatile private var displayRotation = 0
    @Volatile private var inputMatrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    @Volatile private var inputExposure = 1f
    @Volatile private var inputSaturation = 1f

    /**
     * Per-device normalisation, applied before the creative look. Identity by
     * default; calibration data arrives in Phase 7.
     */
    fun setInputProfile(matrix: FloatArray, exposure: Float, saturation: Float) {
        require(matrix.size == 9) { "colour matrix must be 3x3" }
        inputMatrix = matrix.copyOf()
        inputExposure = exposure
        inputSaturation = saturation
    }

    fun applyProfile(
        exposure: Float,
        contrast: Float,
        saturation: Float,
        logEnabled: Boolean,
        monochrome: Boolean,
    ) {
        this.exposure = exposure
        this.contrast = contrast
        this.saturation = saturation
        this.logEnabled = logEnabled
        this.monochrome = monochrome
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
        private set

    fun start(target: Surface, previewWidth: Int, previewHeight: Int) {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null

        handler.post {
            try {
                width = previewWidth
                height = previewHeight
                setupEgl(target)
                setupProgram()
                setupTexture()
                started = true
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
     * Re-targets rendering when Flutter recreates the producer surface
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

            val targetConfig = if (tenBitHlg) pickTenBitConfig() ?: config else config
            val attributes = if (tenBitHlg) {
                intArrayOf(
                    EGL_GL_COLORSPACE_KHR,
                    EGL_GL_COLORSPACE_BT2020_HLG_EXT,
                    EGL14.EGL_NONE,
                )
            } else {
                intArrayOf(EGL14.EGL_NONE)
            }

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
                Log.i(TAG, "encoder surface attached (tenBit=$tenBitHlg)")
            }
        }
    }

    private fun pickTenBitConfig(): EGLConfig? {
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 10,
            EGL14.EGL_GREEN_SIZE, 10,
            EGL14.EGL_BLUE_SIZE, 10,
            EGL14.EGL_ALPHA_SIZE, 2,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val chosen = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) &&
            count[0] > 0
        return if (chosen) configs[0] else null
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
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        uHlgToSdr = GLES20.glGetUniformLocation(program, "uHlgToSdr")
        uExposure = GLES20.glGetUniformLocation(program, "uExposure")
        uContrast = GLES20.glGetUniformLocation(program, "uContrast")
        uSaturation = GLES20.glGetUniformLocation(program, "uSaturation")
        uLog = GLES20.glGetUniformLocation(program, "uLog")
        uMono = GLES20.glGetUniformLocation(program, "uMono")
        uZebra = GLES20.glGetUniformLocation(program, "uZebra")
        uZebraThreshold = GLES20.glGetUniformLocation(program, "uZebraThreshold")
        uFalseColor = GLES20.glGetUniformLocation(program, "uFalseColor")
        uInputMatrix = GLES20.glGetUniformLocation(program, "uInputMatrix")
        uInputExposure = GLES20.glGetUniformLocation(program, "uInputExposure")
        uInputSaturation = GLES20.glGetUniformLocation(program, "uInputSaturation")
        uLut = GLES20.glGetUniformLocation(program, "uLut")
        uLutEnabled = GLES20.glGetUniformLocation(program, "uLutEnabled")
        uLutSize = GLES20.glGetUniformLocation(program, "uLutSize")

        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
    }

    private fun setupTexture() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        oesTextureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
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

        val texture = SurfaceTexture(oesTextureId)
        texture.setDefaultBufferSize(width, height)
        texture.setOnFrameAvailableListener { handler.post(::drawFrame) }
        surfaceTexture = texture
        cameraSurface = Surface(texture)
    }

    private fun drawFrame() {
        val texture = surfaceTexture ?: return

        runCatching {
            texture.updateTexImage()
            texture.getTransformMatrix(texMatrix)
        }.onFailure { return }

        val timestampNs = System.nanoTime()
        renderTo(outputEglSurface, timestampNs, tonemap = tenBitSource, overlay = true)
        renderTo(encoderEglSurface, timestampNs, tonemap = false, overlay = false)

        frameCounter++
        if (analysisEnabled && frameCounter % ANALYSIS_INTERVAL == 0) {
            runAnalysis()
        }

        onFrameRendered()
    }

    private fun drawQuad(tonemap: Boolean, overlay: Boolean) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
        GLES20.glUniform1i(uLut, 1)
        GLES20.glUniform1i(uLutEnabled, if (lutEnabled) 1 else 0)
        GLES20.glUniform1f(uLutSize, if (lutSize > 0f) lutSize else 2f)
        GLES20.glUniform1i(uHlgToSdr, if (tonemap) 1 else 0)
        GLES20.glUniform1f(uExposure, exposure)
        GLES20.glUniform1f(uContrast, contrast)
        GLES20.glUniform1f(uSaturation, saturation)
        GLES20.glUniform1i(uLog, if (logEnabled) 1 else 0)
        GLES20.glUniform1i(uMono, if (monochrome) 1 else 0)
        GLES20.glUniform1i(uZebra, if (overlay && zebra) 1 else 0)
        GLES20.glUniform1f(uZebraThreshold, zebraThreshold)
        GLES20.glUniform1i(uFalseColor, if (overlay && falseColor) 1 else 0)
        GLES20.glUniformMatrix3fv(uInputMatrix, 1, false, inputMatrix, 0)
        GLES20.glUniform1f(uInputExposure, inputExposure)
        GLES20.glUniform1f(uInputSaturation, inputSaturation)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

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
        drawQuad(tonemap = false, overlay = false)

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

        for (pixel in 0 until ANALYSIS_WIDTH * ANALYSIS_HEIGHT) {
            val offset = pixel * 4
            val r = pixels[offset].toInt() and 0xFF
            val g = pixels[offset + 1].toInt() and 0xFF
            val b = pixels[offset + 2].toInt() and 0xFF
            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f

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
        }

        callback(histogram, waveform)
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

    private fun renderTo(
        surface: EGLSurface,
        timestampNs: Long,
        tonemap: Boolean,
        overlay: Boolean,
    ) {
        if (surface == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(display, surface, surface, context)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        drawQuad(tonemap, overlay)

        if (surface == encoderEglSurface) {
            EGLExt.eglPresentationTimeANDROID(display, surface, timestampNs)
        }
        if (!EGL14.eglSwapBuffers(display, surface)) {
            Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        if (surface == encoderEglSurface) {
            encoderFrames++
            if (encoderFrames % 60 == 0) Log.i(TAG, "encoder frames=$encoderFrames")
        }
    }

    private fun teardown() {
        started = false
        detachEncoderInternal()
        cameraSurface?.release()
        cameraSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
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
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform int uHlgToSdr;
            uniform float uExposure;
            uniform float uContrast;
            uniform float uSaturation;
            uniform int uLog;
            uniform int uMono;
            uniform int uZebra;
            uniform float uZebraThreshold;
            uniform int uFalseColor;
            uniform mat3 uInputMatrix;
            uniform float uInputExposure;
            uniform float uInputSaturation;
            uniform sampler2D uLut;
            uniform int uLutEnabled;
            uniform float uLutSize;

            float luma(vec3 c) {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
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

            // Approximate HLG -> SDR for the preview only. The encoder receives
            // the untouched HLG signal.
            vec3 hlgToSdr(vec3 e) {
                vec3 low = (e * e) / 3.0;
                vec3 high = (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
                vec3 x = mix(low, high, step(vec3(0.5), e));
                return pow(clamp(x * 2.5, 0.0, 1.0), vec3(1.0 / 2.2));
            }

            void main() {
                vec4 src = texture2D(uTexture, vTexCoord);
                vec3 c = src.rgb;

                if (uHlgToSdr == 1) {
                    c = hlgToSdr(c);
                }

                // Device normalisation runs before any creative transform.
                c = uInputMatrix * c;
                c *= uInputExposure;
                float inputLuma = luma(c);
                c = mix(vec3(inputLuma), c, uInputSaturation);

                c *= uExposure;
                float y = luma(c);
                c = mix(vec3(y), c, uSaturation);
                c = (c - 0.5) * uContrast + 0.5;

                if (uMono == 1) {
                    c = vec3(luma(c));
                }

                if (uLog == 1) {
                    vec3 linear = pow(max(c, vec3(0.0)), vec3(2.2));
                    vec3 encoded = 0.738 + 0.169 * log2(linear + 0.07);
                    c = clamp(encoded, 0.0, 1.0);
                }

                c = clamp(c, 0.0, 1.0);

                if (uLutEnabled == 1) {
                    c = applyLut(c);
                }

                if (uZebra == 1 && luma(c) >= uZebraThreshold) {
                    float stripe = step(0.5, fract((gl_FragCoord.x + gl_FragCoord.y) * 0.06));
                    c = mix(c, vec3(1.0, 0.1, 0.1), stripe * 0.8);
                }

                if (uFalseColor == 1) {
                    float level = luma(c);
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

                gl_FragColor = vec4(c, src.a);
            }
        """
    }

    private companion object {
        const val START_TIMEOUT_SECONDS = 5L
        const val TAG = "SlateCamGl"

        const val ANALYSIS_WIDTH = 160
        const val ANALYSIS_HEIGHT = 90
        const val HISTOGRAM_BINS = 64
        const val WAVEFORM_COLUMNS = 64
        const val WAVEFORM_BINS = 16
        const val ANALYSIS_INTERVAL = 5

        // Android EGL extension: marks a config usable as a MediaCodec input.
        const val EGL_RECORDABLE_ANDROID = 0x3142

        // Not exposed by android.opengl.EGL14.
        const val EGL_GL_COLORSPACE_KHR = 0x309D
        const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540

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
