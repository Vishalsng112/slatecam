package io.github.vishalsng112.slatecam.core.codec

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import kotlin.math.max

/**
 * Pre-roll metering without an encoder. Holds the selected input while the
 * camera is open so the console shows usable levels before a take; the recorder
 * stops this monitor before opening its own AudioRecord.
 */
class AudioMonitor(private val context: Context) {

    @Volatile
    private var running = false

    @Volatile
    private var gainDb = 0f

    @Volatile
    private var inputKey: String = AudioInputs.DEFAULT_KEY

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    /** Platform source used by the open record, e.g. `unprocessed`. */
    @Volatile
    var sourceLabel: String? = null
        private set

    /** Called on the monitor thread for every PCM buffer. */
    @Volatile
    var onLevel: ((AudioLevel) -> Unit)? = null

    /** Called on the monitor thread when the input cannot be opened or dies. */
    @Volatile
    var onFailure: ((String) -> Unit)? = null

    val isRunning: Boolean get() = running

    fun start(inputKey: String?, gainDb: Float) {
        stop()
        if (!AudioInputs.hasPermission(context)) {
            onFailure?.invoke("microphone permission is not granted")
            return
        }
        this.inputKey = inputKey ?: AudioInputs.DEFAULT_KEY
        this.gainDb = gainDb

        val minBuffer = AudioRecord.getMinBufferSize(
            AudioSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val handle = AudioInputs.openRecord(
            context = context,
            key = this.inputKey,
            sampleRate = AudioSpec.SAMPLE_RATE,
            channelConfig = AudioFormat.CHANNEL_IN_STEREO,
            encoding = AudioFormat.ENCODING_PCM_16BIT,
            bufferSize = max(minBuffer * 2, 8192),
        )
        if (handle == null) {
            onFailure?.invoke("no usable audio input")
            return
        }
        val record = handle.record
        runCatching { record.startRecording() }
            .onFailure {
                runCatching { record.release() }
                onFailure?.invoke("audio input failed to start: ${it.message}")
                return
            }

        sourceLabel = handle.source
        this.record = record
        running = true
        thread = Thread(::loop, "slatecam-audio-monitor").apply { start() }
    }

    fun setGain(gainDb: Float) {
        this.gainDb = gainDb
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        runCatching { thread?.join(750) }
        runCatching { record?.release() }
        record = null
        thread = null
        sourceLabel = null
    }

    private fun loop() {
        val input = record ?: return
        val buffer = ByteArray(4096)
        try {
            while (running) {
                val read = input.read(buffer, 0, buffer.size)
                if (read < 0) {
                    onFailure?.invoke("audio input read failed ($read)")
                    break
                }
                if (read == 0) continue
                val level = AudioMeter.process(
                    buffer,
                    read,
                    AudioSpec.CHANNELS,
                    gainDb,
                )
                onLevel?.invoke(level)
            }
        } catch (t: Throwable) {
            if (running) onFailure?.invoke("audio monitor: ${t.message}")
        }
    }
}
