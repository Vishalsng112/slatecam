package io.github.vishalsng112.slatecam.core.codec

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import io.github.vishalsng112.slatecam.core.common.HevcSps
import java.nio.ByteBuffer

/**
 * Phase 3 recorder: HEVC video from a GL surface plus AAC audio, muxed to MP4.
 *
 * Video frames arrive through the encoder input surface, so the GPU pipeline
 * stays the only producer. Audio uses the selected input and software gain;
 * missing permission or a failed input is reported by the bridge instead of
 * silently pretending the track exists.
 */
class ClipRecorder(private val context: Context) {

    /** Input and gain for this take; selected by the console and persisted. */
    data class AudioConfig(
        val inputKey: String = AudioInputs.DEFAULT_KEY,
        val gainDb: Float = 0f,
    )

    @Volatile
    private var running = false

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var audioInputKey: String = AudioInputs.DEFAULT_KEY

    @Volatile
    private var audioGainDb = 0f

    /** Meter callback from the audio thread, already gain-corrected. */
    @Volatile
    var onAudioLevel: ((AudioLevel) -> Unit)? = null

    @Volatile
    var onAudioFailure: ((String) -> Unit)? = null

    @Volatile
    var audioStarted: Boolean = false
        private set

    /** Platform source used for this take, e.g. `unprocessed`. */
    @Volatile
    var audioSource: String? = null
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

    @Volatile
    private var hlg = false

    /** Video format waiting for csd-0, which some encoders emit with sample 1. */
    private var pendingVideoFormat: MediaFormat? = null
    private var pendingVps: ByteArray? = null
    private var pendingSps: ByteArray? = null
    private var pendingPps: ByteArray? = null

    /** Audio PTS is normalised to the first audio buffer of this clip. */
    @Volatile
    private var audioBaseUs = 0L

    /** Clockwise playback rotation written to the MP4 container. */
    private var orientationHint = 0

    val isRecording: Boolean get() = running

    /**
     * CBR is only requested when this encoder advertises it; otherwise the
     * take falls back to the system VBR mode at the same target so recording
     * never fails over a rate-control preference.
     */
    private fun rateControlMode(format: MediaFormat, constantBitrate: Boolean): Int {
        val vbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        if (!constantBitrate) return vbr
        val cbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        val supported = runCatching {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val name = codecs.findEncoderForFormat(format) ?: return@runCatching false
            val info = codecs.codecInfos.firstOrNull { it.name == name }
                ?: return@runCatching false
            info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                .encoderCapabilities?.isBitrateModeSupported(cbr) ?: false
        }.getOrDefault(false)
        if (!supported) {
            android.util.Log.w(
                "SlateCam",
                "encoder does not advertise CBR; falling back to VBR",
            )
        }
        return if (supported) cbr else vbr
    }

    /**
     * Starts recording and returns the surface the renderer must draw into.
     */
    fun start(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        tenBitHlg: Boolean,
        muxer: MediaMuxer,
        orientationHint: Int = 0,
        audio: AudioConfig = AudioConfig(),
        constantBitrate: Boolean = false,
    ): Surface {
        check(!running) { "already recording" }
        lastError = null
        audioStarted = false
        audioInputKey = audio.inputKey
        audioGainDb = audio.gainDb
        videoEos = false
        audioEos = false
        muxerStarted = false
        videoTrack = -1
        audioTrack = -1
        hlg = tenBitHlg
        pendingVideoFormat = null
        pendingVps = null
        pendingSps = null
        pendingPps = null
        audioBaseUs = 0L
        this.orientationHint = ((orientationHint % 360) + 360) % 360
        this.muxer = muxer

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
            // KEY_BIT_RATE and produces ~150 Mbps at 1080p. Omit the mode for the
            // HLG path: setting it appears to force an 8-bit Main stream.
            if (!tenBitHlg) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    rateControlMode(this, constantBitrate),
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
            } else {
                // Say BT.709 explicitly. Encoder defaults differ per vendor (QTI
                // tagged SDR clips as smpte170m/bt470bg while MediaTek used
                // BT.709), so relying on the default mislabels footage on some
                // devices. SDR is always BT.709 limited range.
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        }

        val video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        android.util.Log.i("SlateCam", "video encoder=${video.name}")
        video.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = video.createInputSurface()
        video.start()
        videoEncoder = video

        running = true

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { startAudio() }
                .onFailure { lastError = "audio start failed: ${it.message}" }
        }

        drainThread = Thread(::drainLoop, "slatecam-muxer").apply {
            start()
        }
        return inputSurface
    }

    /** Stops recording and finalizes the muxer. Returns true when a file was produced. */
    fun stop(): Boolean {
        if (!running && drainThread == null) return false
        running = false
        audioStarted = false
        audioSource = null

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
        val finalized = muxerStarted
        muxerStarted = false
        drainThread = null
        audioThread = null

        return finalized
    }

    private fun startAudio() {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AudioSpec.SAMPLE_RATE,
            AudioSpec.CHANNELS,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }

        audioBaseUs = System.nanoTime() / 1_000

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        audioEncoder = encoder

        val minBuffer = AudioRecord.getMinBufferSize(
            AudioSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val handle = AudioInputs.openRecord(
            context = context,
            key = audioInputKey,
            sampleRate = AudioSpec.SAMPLE_RATE,
            channelConfig = AudioFormat.CHANNEL_IN_STEREO,
            encoding = AudioFormat.ENCODING_PCM_16BIT,
            bufferSize = maxOf(minBuffer * 2, 8192),
        ) ?: error("no usable audio input")
        val record = handle.record
        record.startRecording()
        audioRecord = record
        audioSource = handle.source
        audioStarted = true

        audioThread = Thread(::audioLoop, "slatecam-audio").apply { start() }
    }

    /** Gain changes take effect on the next PCM buffer, including mid-take. */
    fun setAudioGain(gainDb: Float) {
        audioGainDb = gainDb
    }

    private fun audioLoop() {
        val encoder = audioEncoder ?: return
        val record = audioRecord ?: return
        val pcm = ByteArray(4096)
        var lastLevelMs = 0L

        try {
            while (running) {
                val read = record.read(pcm, 0, pcm.size)
                if (read < 0) {
                    onAudioFailure?.invoke("audio read failed ($read)")
                    break
                }
                if (read == 0) continue

                val level = AudioMeter.process(
                    pcm,
                    read,
                    AudioSpec.CHANNELS,
                    audioGainDb,
                )
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastLevelMs >= 80L) {
                    lastLevelMs = now
                    onAudioLevel?.invoke(level)
                }

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
                        audioPresentationUs(),
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
                    audioPresentationUs(),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
        } catch (t: Throwable) {
            lastError = "audio: ${t.message}"
            onAudioFailure?.invoke(lastError ?: "audio failed")
        }
    }

    private fun audioPresentationUs(): Long {
        val base = audioBaseUs
        if (base <= 0L) return 0L
        return (System.nanoTime() / 1_000 - base).coerceAtLeast(0L)
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
                            } csd=${trackFormat.containsKey("csd-0")}",
                        )
                        val sps = runCatching {
                            trackFormat.getByteBuffer("csd-0")?.let { original ->
                                // Read a duplicate: advancing the buffer the muxer
                                // will consume would strip the codec config (hvcC).
                                val copy = original.duplicate()
                                val bytes = ByteArray(copy.remaining())
                                copy.get(bytes)
                                HevcSps.parse(bytes)
                            }
                        }.getOrNull()
                        if (sps != null) {
                            logSps(sps, trackFormat)
                            applyColourTagging(trackFormat, sps)
                            videoTrack = runCatching {
                                muxer?.addTrack(trackFormat) ?: -1
                            }.getOrDefault(-1)
                            pendingVideoFormat = null
                            maybeStartMuxer()
                        } else {
                            // QTI emits the config with the first access unit,
                            // not in outputFormat. Defer addTrack until then.
                            pendingVideoFormat = trackFormat
                            android.util.Log.i(
                                "SlateCam",
                                "csd-0 missing; waiting for first sample to build hvcC",
                            )
                        }
                        progressed = true
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (index >= 0) {
                        val configBuffer =
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (videoTrack < 0 && pendingVideoFormat != null) {
                            if (configBuffer) {
                                captureConfigNals(encoder, index, info)
                            } else {
                                ensureVideoTrackFromSample(encoder, index, info)
                            }
                        }
                        if (!configBuffer) {
                            writeSample(encoder, index, info, videoTrack)
                        }
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

    private fun logSps(sps: HevcSps.SpsInfo, format: MediaFormat) {
        android.util.Log.i(
            "SlateCam",
            "bitstream profile_idc=${sps.profileIdc} " +
                "bit_depth=${sps.bitDepthLuma} chroma=${sps.chromaFormatIdc} " +
                "header_profile=${format.getInteger(MediaFormat.KEY_PROFILE, -1)}",
        )
    }

    /** Never tag HLG unless the encoded bytes are really Main10 at 10-bit. */
    private fun applyColourTagging(format: MediaFormat, sps: HevcSps.SpsInfo?) {
        val tagHlg = hlg && sps?.isMain10 == true && sps.isTenBit
        if (hlg && sps != null && !tagHlg) {
            android.util.Log.w(
                "SlateCam",
                "encoder claimed HLG but bitstream is " +
                    "profile_idc=${sps.profileIdc} bit_depth=${sps.bitDepthLuma}",
            )
        }
        if (tagHlg) {
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        } else {
            // SDR output is BT.709 limited range on every device, whatever the
            // encoder's default tagging was.
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
    }

    private fun readOutputBuffer(
        encoder: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
    ): ByteArray? {
        if (info.size <= 0) return null
        val buffer = encoder.getOutputBuffer(index) ?: return null
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val bytes = ByteArray(info.size)
        buffer.get(bytes)
        return bytes
    }

    private fun rememberNal(nal: ByteArray) {
        if (nal.size < 2) return
        when ((nal[0].toInt() shr 1) and 0x3F) {
            32 -> pendingVps = nal
            33 -> pendingSps = nal
            34 -> pendingPps = nal
        }
    }

    private fun captureConfigNals(
        encoder: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
    ) {
        val bytes = readOutputBuffer(encoder, index, info) ?: return
        HevcSps.nalUnits(bytes).forEach(::rememberNal)
        android.util.Log.i(
            "SlateCam",
            "captured codec config: vps=${pendingVps != null} " +
                "sps=${pendingSps != null} pps=${pendingPps != null}",
        )
    }

    /**
     * QTI emits VPS/SPS/PPS with the first IDR instead of in `outputFormat`.
     * Build the hvcC from those NAL units so MediaMuxer gets a real sample entry.
     */
    private fun ensureVideoTrackFromSample(
        encoder: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
    ) {
        val format = pendingVideoFormat ?: return
        val bytes = readOutputBuffer(encoder, index, info)
        if (bytes != null) {
            HevcSps.nalUnits(bytes).forEach(::rememberNal)
        }
        val spsNal = pendingSps
        if (spsNal == null) {
            android.util.Log.w("SlateCam", "no SPS in first access unit; track has no config")
            pendingVideoFormat = null
            videoTrack = runCatching { muxer?.addTrack(format) ?: -1 }.getOrDefault(-1)
            maybeStartMuxer()
            return
        }

        val csd = HevcSps.buildHvcC(pendingVps, spsNal, pendingPps)
        val sps = HevcSps.parse(spsNal)
        if (csd != null) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            android.util.Log.i("SlateCam", "built hvcC from first access unit: ${csd.size} bytes")
        }
        sps?.let {
            logSps(it, format)
            applyColourTagging(format, it)
        } ?: applyColourTagging(format, null)
        videoTrack = runCatching { muxer?.addTrack(format) ?: -1 }.getOrDefault(-1)
        pendingVideoFormat = null
        maybeStartMuxer()
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
            if (orientationHint != 0) {
                runCatching { muxer?.setOrientationHint(orientationHint) }
            }
            runCatching { muxer?.start() }
                .onSuccess { muxerStarted = true }
                .onFailure { lastError = "muxer start: ${it.message}" }
        }
    }

    private companion object {
        const val AUDIO_BITRATE = 256_000
    }
}
