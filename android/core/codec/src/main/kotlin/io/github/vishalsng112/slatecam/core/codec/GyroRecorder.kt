package io.github.vishalsng112.slatecam.core.codec

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.github.vishalsng112.slatecam.core.common.GyroAligner
import io.github.vishalsng112.slatecam.core.common.GyroSample
import java.io.File

/**
 * Captures gyroscope samples during a recording and writes frame-aligned
 * rotations to a sidecar.
 *
 * Alignment is only meaningful when `SENSOR_INFO_TIMESTAMP_SOURCE` is `realtime`,
 * which the caller checks. Embedding the data in the container is a later step.
 */
class GyroRecorder(private val context: Context) {

    private var manager: SensorManager? = null
    private var listener: SensorEventListener? = null
    private val samples = ArrayList<GyroSample>(8192)

    @Volatile
    private var running = false

    fun isAvailable(): Boolean {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    }

    fun start(): Boolean {
        if (running) return true
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return false

        samples.clear()
        val callback = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!running) return
                samples += GyroSample(
                    timestampNs = event.timestamp,
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        manager = sensors
        listener = callback
        running = true
        sensors.registerListener(callback, gyro, SensorManager.SENSOR_DELAY_GAME)
        return true
    }

    fun stop(): Int {
        if (!running) return samples.size
        running = false
        listener?.let { manager?.unregisterListener(it) }
        listener = null
        manager = null
        return samples.size
    }

    fun sampleCount(): Int = samples.size

    /**
     * Integrates samples into per-frame rotations and writes a versioned sidecar.
     * Returns the number of frames written, or -1 on failure.
     */
    fun writeAlignedSidecar(
        file: File,
        fps: Int,
        frameTimestampsNs: LongArray,
    ): Int {
        if (samples.isNotEmpty() && frameTimestampsNs.isNotEmpty()) {
            val delta = kotlin.math.abs(
                samples.first().timestampNs - frameTimestampsNs.first(),
            )
            if (delta > 1_000_000_000L) {
                android.util.Log.w(
                    "SlateCam",
                    "gyro clock mismatch: sensor vs frame differs by ${delta / 1_000_000} ms",
                )
            }
        }
        val rotations = GyroAligner.align(frameTimestampsNs, samples.toList())
        val written = runCatching {
            file.parentFile?.mkdirs()
            file.bufferedWriter().use { writer ->
                writer.write(
                    "{\"version\":2,\"fps\":$fps,\"raw_samples\":${samples.size},\"frames\":[",
                )
                rotations.forEachIndexed { index, rotation ->
                    if (index > 0) writer.write(",")
                    writer.write(
                        "{\"t\":${rotation.timestampNs}," +
                            "\"rx\":${rotation.rx}," +
                            "\"ry\":${rotation.ry}," +
                            "\"rz\":${rotation.rz}}",
                    )
                }
                writer.write("]}")
            }
            true
        }.getOrDefault(false)

        return if (written) rotations.size else -1
    }
}
