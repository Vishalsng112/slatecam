package io.github.vishalsng112.slatecam.core.codec

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One meter snapshot in dBFS. Values are clamped at [AudioMeter.FLOOR_DB] so
 * the UI never has to handle negative infinity.
 */
data class AudioLevel(
    val peakDbL: Float = AudioMeter.FLOOR_DB,
    val peakDbR: Float = AudioMeter.FLOOR_DB,
    val rmsDbL: Float = AudioMeter.FLOOR_DB,
    val rmsDbR: Float = AudioMeter.FLOOR_DB,
    val clipped: Boolean = false,
) {
    companion object {
        val SILENT = AudioLevel()
    }
}

/**
 * Pure PCM16 meter and gain math. Kept free of Android types so the audio floor,
 * gain and clip behaviour are unit-tested on the JVM.
 */
object AudioMeter {
    /** Lowest displayed level; also the value used for silence. */
    const val FLOOR_DB = -120f

    /**
     * Applies gain in place and returns true when the result clipped. A gain of
     * 0 dB still reports a pre-existing full-scale sample as clipped.
     */
    fun applyGain(pcm: ByteArray, length: Int, gainDb: Float): Boolean {
        val usable = (length / 2) * 2
        val linear = if (gainDb == 0f) 1f else 10f.pow(gainDb / 20f)
        var clipped = false
        var index = 0
        while (index < usable) {
            val sample = readSample(pcm, index)
            if (sample >= 32767 || sample <= -32768) clipped = true
            if (linear != 1f) {
                val scaled = (sample * linear).roundToInt()
                val clamped = scaled.coerceIn(-32768, 32767)
                if (clamped != scaled) clipped = true
                writeSample(pcm, index, clamped)
            }
            index += 2
        }
        return clipped
    }

    /**
     * Peak and RMS per channel. Mono input is shown on both meters so a single
     * microphone still produces a useful stereo-style readout.
     */
    fun measure(pcm: ByteArray, length: Int, channels: Int): AudioLevel {
        val channelCount = if (channels <= 1) 1 else 2
        val frames = length / (2 * channelCount)
        if (frames <= 0) return AudioLevel.SILENT

        var peak0 = 0
        var peak1 = 0
        var sumSq0 = 0.0
        var sumSq1 = 0.0
        var index = 0
        for (frame in 0 until frames) {
            val first = readSample(pcm, index)
            index += 2
            val second = if (channelCount == 2) {
                val value = readSample(pcm, index)
                index += 2
                value
            } else {
                first
            }
            val abs0 = kotlin.math.abs(first)
            val abs1 = kotlin.math.abs(second)
            if (abs0 > peak0) peak0 = abs0
            if (abs1 > peak1) peak1 = abs1
            sumSq0 += first.toDouble() * first
            sumSq1 += second.toDouble() * second
        }

        return AudioLevel(
            peakDbL = amplitudeToDb(peak0),
            peakDbR = amplitudeToDb(peak1),
            rmsDbL = powerToDb(sumSq0 / frames),
            rmsDbR = powerToDb(sumSq1 / frames),
            clipped = peak0 >= 32767 || peak1 >= 32767,
        )
    }

    /** Applies gain, then measures the gained signal. */
    fun process(
        pcm: ByteArray,
        length: Int,
        channels: Int,
        gainDb: Float,
    ): AudioLevel {
        val clipped = applyGain(pcm, length, gainDb)
        val level = measure(pcm, length, channels)
        return if (clipped && !level.clipped) level.copy(clipped = true) else level
    }

    fun amplitudeToDb(amplitude: Int): Float {
        if (amplitude <= 0) return FLOOR_DB
        val ratio = amplitude / 32768.0
        return max(FLOOR_DB, (20.0 * log10(ratio)).toFloat())
    }

    private fun powerToDb(meanSquare: Double): Float {
        if (meanSquare <= 0.0) return FLOOR_DB
        return max(FLOOR_DB, (10.0 * log10(meanSquare / (32768.0 * 32768.0))).toFloat())
    }

    private fun readSample(pcm: ByteArray, index: Int): Int {
        val value = (pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)
        return if (value >= 0x8000) value - 0x10000 else value
    }

    private fun writeSample(pcm: ByteArray, index: Int, sample: Int) {
        pcm[index] = (sample and 0xFF).toByte()
        pcm[index + 1] = ((sample shr 8) and 0xFF).toByte()
    }

    private fun Float.pow(exponent: Float): Float =
        Math.pow(toDouble(), exponent.toDouble()).toFloat()
}
