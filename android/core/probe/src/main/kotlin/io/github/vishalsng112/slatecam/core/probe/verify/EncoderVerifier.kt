package io.github.vishalsng112.slatecam.core.probe.verify

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import io.github.vishalsng112.slatecam.core.common.HevcSps
import io.github.vishalsng112.slatecam.core.probe.model.VerificationCheck

/**
 * Attempts a real HEVC Main10 encode instead of trusting `CodecCapabilities`.
 *
 * Vendor HALs are unreliable in both directions: they advertise Main10 and then
 * reject the surface, or omit Main10 while the encoder accepts it. Only an
 * actual encode settles the question. Every hardware HEVC encoder is tried.
 *
 * This harness is deliberately small: the production pipeline replaces it in
 * Phase 3, but the "claimed vs verified" contract needs it now.
 */
class EncoderVerifier {

    data class Outcome(
        val check: VerificationCheck,
        /** Codec name that produced output, or null on failure. */
        val passingCodec: String?,
        /** Size that actually encoded; 0 when nothing succeeded. */
        val width: Int = 0,
        val height: Int = 0,
    )

    fun verifyHevcMain10(): Outcome {
        val started = System.currentTimeMillis()

        val candidates = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { it.isEncoder && "video/hevc" in it.supportedTypes && it.isHardwareAccelerated }
                .map { it.name }
        }.getOrElse { return Outcome(failure("encoder enumeration failed: ${it.message}", started), null) }

        if (candidates.isEmpty()) return Outcome(failure("no hardware HEVC encoder", started), null)

        val errors = mutableListOf<String>()
        val deadline = started + OVERALL_TIMEOUT_MS
        // Largest first: verifying UHD is the answer that matters. Falling back
        // to FHD still proves the Main10 path, just at a smaller preset.
        for ((width, height) in TEST_SIZES) {
            for (name in candidates) {
                if (System.currentTimeMillis() > deadline) {
                    errors += "verification time limit reached"
                    return Outcome(failure(errors.joinToString(" | "), started), null)
                }
                val attempt = runCatching { encode(name, width, height) }
                attempt.onSuccess { detail ->
                    return Outcome(
                        check = VerificationCheck(
                            id = CHECK_ID,
                            status = "pass",
                            detail = detail,
                            durationMs = System.currentTimeMillis() - started,
                            width = width,
                            height = height,
                        ),
                        passingCodec = name,
                        width = width,
                        height = height,
                    )
                }
                attempt.onFailure { error ->
                    errors += "${width}x$height/$name: " +
                        (error.message ?: error.javaClass.simpleName)
                }
            }
        }
        return Outcome(failure(errors.joinToString(" | "), started), null)
    }

    private fun encode(codecName: String, width: Int, height: Int): String {
        val codec = MediaCodec.createByCodecName(codecName)
        var window: EglWindow? = null
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                width,
                height,
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                )
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()

            val eglWindow = EglWindow(inputSurface)
            window = eglWindow
            repeat(FRAME_COUNT) { index ->
                GLES20.glViewport(0, 0, width, height)
                GLES20.glClearColor(0.1f * (index % 4), 0.4f, 0.6f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                eglWindow.swap()
            }

            codec.signalEndOfInputStream()

            val info = MediaCodec.BufferInfo()
            var bytes = 0L
            var frames = 0
            var profile = -1
            var transfer = -1
            var realProfile: Int? = null
            val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS

            while (System.currentTimeMillis() < deadline) {
                val index = codec.dequeueOutputBuffer(info, 500_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        profile = output.getInteger(MediaFormat.KEY_PROFILE, -1)
                        transfer = output.getInteger(MediaFormat.KEY_COLOR_TRANSFER, -1)
                        realProfile = runCatching {
                            output.getByteBuffer("csd-0")?.let { buffer ->
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                HevcSps.profileIdc(bytes)
                            }
                        }.getOrNull()
                    }
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    index >= 0 -> {
                        if (info.size > 0) {
                            bytes += info.size
                            frames++
                        }
                        val endOfStream =
                            info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                        if (endOfStream) break
                    }
                }
            }

            check(frames > 0 && bytes > 0L) { "no output frames (bytes=$bytes)" }
            check(realProfile == HevcSps.PROFILE_MAIN10) {
                "encoded bitstream is profile_idc=$realProfile, not Main10 " +
                    "(codec header claimed profile=$profile)"
            }

            return buildString {
                append("$codecName: $frames frames, $bytes bytes")
                if (profile >= 0) append(", profile=$profile")
                if (transfer >= 0) append(", transfer=$transfer")
                append(", sps_profile=$realProfile")
            }
        } finally {
            runCatching { window?.close() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun failure(detail: String, started: Long) = VerificationCheck(
        id = CHECK_ID,
        status = "fail",
        detail = detail,
        durationMs = System.currentTimeMillis() - started,
    )

    private class EglWindow(surface: Surface) : AutoCloseable {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(
                EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, count, 0) &&
                    count[0] > 0,
            ) { "no EGL config" }

            val config = configs[0]!!
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
            eglSurface = createWindowSurface(config, surface)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                "eglMakeCurrent failed"
            }
        }

        private fun createWindowSurface(config: EGLConfig, surface: Surface): EGLSurface {
            // Try HLG colour signalling first; fall back when the driver rejects it.
            val hlg = intArrayOf(
                EGL_GL_COLORSPACE_KHR,
                EGL_GL_COLORSPACE_BT2020_HLG_EXT,
                EGL14.EGL_NONE,
            )
            val withHlg = EGL14.eglCreateWindowSurface(display, config, surface, hlg, 0)
            if (withHlg != EGL14.EGL_NO_SURFACE) return withHlg

            val plain = intArrayOf(EGL14.EGL_NONE)
            return EGL14.eglCreateWindowSurface(display, config, surface, plain, 0)
        }

        fun swap() {
            check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers failed" }
        }

        override fun close() {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private companion object {
        const val CHECK_ID = "hevc_main10_encode"
        const val FRAME_COUNT = 10
        const val DRAIN_TIMEOUT_MS = 15_000L
        const val OVERALL_TIMEOUT_MS = 25_000L

        val TEST_SIZES = listOf(3840 to 2160, 1920 to 1080, 1280 to 720)

        // Not exposed by android.opengl.EGL14.
        const val EGL_GL_COLORSPACE_KHR = 0x309D
        const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
    }
}
