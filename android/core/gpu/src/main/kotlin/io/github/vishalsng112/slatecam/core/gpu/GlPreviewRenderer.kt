package io.github.vishalsng112.slatecam.core.gpu

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
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
    private var aPosition = 0
    private var aTexCoord = 0
    private val texMatrix = FloatArray(16)
    private var width = 1920
    private var height = 1080
    private var started = false

    @Volatile
    private var tenBitSource = false
    private var encoderFrames = 0

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
        renderTo(outputEglSurface, timestampNs, tonemap = tenBitSource)
        renderTo(encoderEglSurface, timestampNs, tonemap = false)
        onFrameRendered()
    }

    private fun renderTo(surface: EGLSurface, timestampNs: Long, tonemap: Boolean) {
        if (surface == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(display, surface, surface, context)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniform1i(uHlgToSdr, if (tonemap) 1 else 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, QUAD_VERTICES)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, QUAD_TEX_COORDS)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

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

            // Approximate HLG -> SDR for the preview only. The encoder receives
            // the untouched HLG signal.
            vec3 hlgToSdr(vec3 e) {
                vec3 low = (e * e) / 3.0;
                vec3 high = (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
                vec3 x = mix(low, high, step(vec3(0.5), e));
                return pow(clamp(x * 2.5, 0.0, 1.0), vec3(1.0 / 2.2));
            }

            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                if (uHlgToSdr == 1) {
                    c.rgb = hlgToSdr(c.rgb);
                }
                gl_FragColor = c;
            }
        """
    }

    private companion object {
        const val START_TIMEOUT_SECONDS = 5L
        const val TAG = "SlateCamGl"

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
