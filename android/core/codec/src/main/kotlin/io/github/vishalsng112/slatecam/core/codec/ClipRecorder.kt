package io.github.vishalsng112.slatecam.core.codec

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.view.Surface
import io.github.vishalsng112.slatecam.core.common.HevcSps
import java.io.File

/**
 * Phase 3 recorder: HEVC video from a GL surface plus AAC audio, muxed to MP4.
 *
 * Video frames arrive through the encoder input surface, so the GPU pipeline
 * stays the only producer. Audio is optional and silently omitted when the
 * RECORD_AUDIO permission is missing.
 */
class ClipRecorder(private val context: Context) {

    @Volatile
    private var running = false

    @Volatile
    var lastError: String? = null
        private set

    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null

    private var drainThread: Thread? = null
    private var audioThread: Thread? = null

    @Volatile
    private var videoEos = false

    @Volatile
    private var audioEos = false

    private var outputFile: File? = null

    @Volatile
    private var hlg = false

    val isRecording: Boolean get() = running

    /**
     * Starts recording and returns the surface the renderer must draw into.
     */
    fun start(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        output: File,
        tenBitHlg: Boolean = false,
    ): Surface {
        check(!running) { "already recording" }
        lastError = null
        videoEos = false
        audioEos = false
        muxerStarted = false
        videoTrack = -1
        audioTrack = -1
        outputFile = output
        hlg = tenBitHlg

        val mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = mux

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            width,
            height,
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // MediaTek encoders otherwise default to a quality mode that ignores
            // KEY_BIT_RATE and produces ~150 Mbps at 1080p. Omit it for the HLG
            // path: it appears to force an 8-bit Main stream.
            if (!tenBitHlg) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                )
            }
            if (tenBitHlg) {
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                )
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        }

        val video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        video.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = video.createInputSurface()
        video.start()
        videoEncoder = video

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { startAudio() }
                .onFailure { lastError = "audio start failed: ${it.message}" }
        }

        running = true
        drainThread = Thread(::drainLoop, "slatecam-muxer").apply {
            start()
        }
        return inputSurface
    }

    /** Stops recording and finalizes the file. Blocking; call off the main thread. */
    fun stop(): File? {
        if (!running && drainThread == null) return outputFile
        running = false

        runCatching { videoEncoder?.signalEndOfInputStream() }
        runCatching { audioRecord?.stop() }
        runCatching { audioThread?.join(1_500) }
        runCatching { drainThread?.join(5_000) }

        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { audioEncoder?.stop() }
        runCatching { audioEncoder?.release() }
        audioEncoder = null
        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        videoEncoder = null

        if (muxerStarted) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
        drainThread = null
        audioThread = null

        return outputFile
    }

    private fun startAudio() {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            SAMPLE_RATE,
            CHANNELS,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        audioEncoder = encoder

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 2, 8192),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
        record.startRecording()
        audioRecord = record

        audioThread = Thread(::audioLoop, "slatecam-audio").apply { start() }
    }

    private fun audioLoop() {
        val encoder = audioEncoder ?: return
        val record = audioRecord ?: return
        val pcm = ByteArray(4096)

        try {
            while (running) {
                val read = record.read(pcm, 0, pcm.size)
                if (read <= 0) continue
                var offset = 0
                while (offset < read) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index < 0) break
                    val input = encoder.getInputBuffer(index) ?: break
                    input.clear()
                    val size = minOf(input.remaining(), read - offset)
                    input.put(pcm, offset, size)
                    encoder.queueInputBuffer(
                        index,
                        0,
                        size,
                        System.nanoTime() / 1_000,
                        0,
                    )
                    offset += size
                }
            }

            val index = encoder.dequeueInputBuffer(10_000)
            if (index >= 0) {
                encoder.queueInputBuffer(
                    index,
                    0,
                    0,
                    System.nanoTime() / 1_000,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
        } catch (t: Throwable) {
            lastError = "audio: ${t.message}"
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        var idleRounds = 0

        while (true) {
            var progressed = false

            videoEncoder?.let { encoder ->
                when (val index = encoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val trackFormat = encoder.outputFormat
                        android.util.Log.i(
                            "SlateCam",
                            "recorded video format: profile=${
                                trackFormat.getInteger(MediaFormat.KEY_PROFILE, -1)
                            } standard=${
                                trackFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD, -1)
                            } transfer=${
                                trackFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER, -1)
                            } range=${
                                trackFormat.getInteger(MediaFormat.KEY_COLOR_RANGE, -1)
                            }",
                        )
                        val csdProfile = runCatching {
                            trackFormat.getByteBuffer("csd-0")?.let { buffer ->
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                HevcSps.profileIdc(bytes)
                            }
                        }.getOrNull()
                        android.util.Log.i(
                            "SlateCam",
                            "bitstream profile_idc=$csdProfile header_profile=${
                                trackFormat.getInteger(MediaFormat.KEY_PROFILE, -1)
                            }",
                        )
                        // Never tag HLG unless the encoded bytes are really Main10.
                        val tagHlg = hlg && csdProfile == HevcSps.PROFILE_MAIN10
                        if (hlg && !tagHlg) {
                            android.util.Log.w(
                                "SlateCam",
                                "encoder claimed HLG but bitstream is profile_idc=$csdProfile",
                            )
                        }
                        if (tagHlg) {
                            // Make the colour signalling explicit on the track so
                            // players see HLG/BT.2020 rather than guessing.
                            trackFormat.setInteger(
                                MediaFormat.KEY_COLOR_STANDARD,
                                MediaFormat.COLOR_STANDARD_BT2020,
                            )
                            trackFormat.setInteger(
                                MediaFormat.KEY_COLOR_TRANSFER,
                                MediaFormat.COLOR_TRANSFER_HLG,
                            )
                            trackFormat.setInteger(
                                MediaFormat.KEY_COLOR_RANGE,
                                MediaFormat.COLOR_RANGE_LIMITED,
                            )
                        }
                        videoTrack = runCatching {
                            muxer?.addTrack(trackFormat) ?: -1
                        }.getOrDefault(-1)
                        maybeStartMuxer()
                        progressed = true
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (index >= 0) {
                        writeSample(encoder, index, info, videoTrack)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            videoEos = true
                        }
                        encoder.releaseOutputBuffer(index, false)
                        progressed = true
                    }
                }
            }

            audioEncoder?.let { encoder ->
                when (val index = encoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        audioTrack = runCatching {
                            muxer?.addTrack(encoder.outputFormat) ?: -1
                        }.getOrDefault(-1)
                        maybeStartMuxer()
                        progressed = true
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (index >= 0) {
                        writeSample(encoder, index, info, audioTrack)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            audioEos = true
                        }
                        encoder.releaseOutputBuffer(index, false)
                        progressed = true
                    }
                }
            }

            val audioDone = audioEncoder == null || audioEos
            if (videoEos && audioDone) break

            if (!progressed) {
                idleRounds++
                if (!running && idleRounds > 200) {
                    lastError = lastError ?: "encoder drain stalled"
                    break
                }
                if (idleRounds > 5) Thread.sleep(5)
            } else {
                idleRounds = 0
            }
        }
    }

    private fun writeSample(
        encoder: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
        track: Int,
    ) {
        val buffer = encoder.getOutputBuffer(index) ?: return
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        if (info.size > 0 && !isConfig && muxerStarted && track >= 0) {
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            runCatching { muxer?.writeSampleData(track, buffer, info) }
                .onFailure { lastError = "muxer: ${it.message}" }
        }
    }

    private fun maybeStartMuxer() {
        if (muxerStarted) return
        val audioReady = audioEncoder == null || audioTrack >= 0
        if (videoTrack >= 0 && audioReady) {
            runCatching { muxer?.start() }
                .onSuccess { muxerStarted = true }
                .onFailure { lastError = "muxer start: ${it.message}" }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val AUDIO_BITRATE = 192_000
    }
}
