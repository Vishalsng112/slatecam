package io.github.vishalsng112.slatecam.core.codec

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.io.File

/**
 * Records gyroscope samples alongside a clip and writes a sidecar file.
 *
 * This is the Phase 5 starting point: raw samples with sensor timestamps. Frame
 * alignment is only meaningful when `SENSOR_INFO_TIMESTAMP_SOURCE` is `realtime`,
 * which the caller checks. Embedding the data in the container is a later step.
 */
class GyroRecorder(private val context: Context) {

    data class Sample(
        val timestampNs: Long,
        val x: Float,
        val y: Float,
        val z: Float,
    )

    private var manager: SensorManager? = null
    private var listener: SensorEventListener? = null
    private val samples = ArrayList<Sample>(8192)

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
                samples += Sample(
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

    /** Writes `{version, samples:[{t,x,y,z}]}` as a sidecar next to the clip name. */
    fun writeSidecar(file: File): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { writer ->
            writer.write("{\"version\":1,\"samples\":[")
            samples.forEachIndexed { index, sample ->
                if (index > 0) writer.write(",")
                writer.write(
                    "{\"t\":${sample.timestampNs},\"x\":${sample.x}," +
                        "\"y\":${sample.y},\"z\":${sample.z}}",
                )
            }
            writer.write("]}")
        }
        true
    }.getOrDefault(false)
}
