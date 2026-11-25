package io.github.vishalsng112.slatecam.core.codec

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder

/** An open record plus the platform source actually used. */
data class AudioInputHandle(
    val record: AudioRecord,
    val source: String,
)

/** One selectable audio input, derived from the live AudioManager list. */
data class AudioInputDevice(
    /** Persisted identity: type/address/product name, not the transient id. */
    val key: String,
    val name: String,
    /** Human-readable class, e.g. built-in mic or USB headset. */
    val type: String,
    /** Best supported format, e.g. `48 kHz · 2 ch`. */
    val detail: String,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
)

/** Recording format used by the monitor, recorder and UI. */
object AudioSpec {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
}

/**
 * Enumerates and opens audio inputs.
 *
 * The selection is data, not a device model: the recorder asks for a preferred
 * `AudioDeviceInfo` and falls back to the cleanest platform source when it is
 * missing. Keys deliberately exclude the transient device id so a persisted
 * choice survives a reconnect or reboot.
 */
object AudioInputs {
    const val DEFAULT_KEY = "default"
    fun list(context: Context): List<AudioInputDevice> {
        val manager = audioManager(context)
        if (!hasPermission(context)) return emptyList()
        val devices = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }.getOrDefault(emptyList())

        return devices
            .asSequence()
            .filter { it.isSource }
            .mapNotNull { device ->
                val label = typeLabel(device.type) ?: return@mapNotNull null
                val rates = runCatching { device.sampleRates.toList().sorted() }
                    .getOrDefault(emptyList())
                val channels = runCatching { device.channelCounts.toList().sorted() }
                    .getOrDefault(emptyList())
                val address = device.address ?: ""
                val name = when {
                    device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC &&
                        address.isNotBlank() && address != "0" -> "$label · $address"
                    device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> label
                    device.productName?.toString()?.isNotBlank() == true ->
                        device.productName.toString()
                    else -> label
                }
                android.util.Log.i(
                    "SlateCam",
                    "audio input id=${device.id} type=${device.type}($label) " +
                        "address=$address name=$name rates=$rates channels=$channels",
                )
                AudioInputDevice(
                    key = keyOf(device),
                    name = name,
                    type = label,
                    detail = formatDetail(rates, channels),
                    sampleRates = rates,
                    channelCounts = channels,
                )
            }
            .distinctBy { it.key }
            .sortedWith(compareBy<AudioInputDevice> { typeRank(it.type) }.thenBy { it.name })
            .toList()
    }

    /** Resolves a persisted key against the currently connected devices. */
    fun find(context: Context, key: String?): AudioDeviceInfo? {
        if (key.isNullOrBlank() || key == DEFAULT_KEY) return null
        val manager = audioManager(context)
        if (!hasPermission(context)) return null
        return runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.isSource && keyOf(it) == key }
        }.getOrNull()
    }

    /**
     * Opens a record with the cleanest source the platform supports, then asks
     * for [key] when it is present. Returns null when no source initializes.
     */
    fun openRecord(
        context: Context,
        key: String?,
        sampleRate: Int,
        channelConfig: Int,
        encoding: Int,
        bufferSize: Int,
    ): AudioInputHandle? {
        if (!hasPermission(context)) return null
        val manager = audioManager(context)
        val unprocessed = manager
            .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            ?.toBoolean() == true
        val sources = buildList {
            if (unprocessed) add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.CAMCORDER)
            add(MediaRecorder.AudioSource.MIC)
        }
        val preferred = find(context, key)

        for (source in sources) {
            val record = runCatching {
                AudioRecord(
                    source,
                    sampleRate,
                    channelConfig,
                    encoding,
                    bufferSize,
                )
            }.getOrNull() ?: continue
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                continue
            }
            val routed = if (preferred != null) {
                runCatching { record.setPreferredDevice(preferred) }.getOrDefault(false)
            } else {
                false
            }
            android.util.Log.i(
                "SlateCam",
                "audio source=${sourceName(source)} input=${preferred?.productName ?: "default"} " +
                    "preferred=$routed rate=$sampleRate channels=${channelConfig}",
            )
            return AudioInputHandle(record, sourceName(source))
        }
        return null
    }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun keyOf(device: AudioDeviceInfo): String =
        "${device.type}:${device.address}:${device.productName}"

    private fun audioManager(context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Only real capture routes are offered. Vendor builds also expose telephony,
     * dock and remote-submix inputs that cannot record the scene; hiding them
     * keeps the control honest without a device-specific list.
     */
    private fun typeLabel(type: Int): String? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> null
    }

    private fun typeRank(label: String): Int = when (label) {
        "Built-in mic" -> 0
        "USB audio", "USB headset" -> 1
        "Wired headset" -> 2
        "Bluetooth headset", "Bluetooth LE headset" -> 3
        else -> 4
    }

    private fun formatDetail(rates: List<Int>, channels: List<Int>): String {
        val rate = when {
            48_000 in rates -> 48_000
            rates.isNotEmpty() -> rates.max()
            else -> 48_000
        }
        val channelCount = channels.maxOrNull()?.coerceAtLeast(1) ?: 1
        val units = if (rate % 1_000 == 0) "${rate / 1_000} kHz" else "$rate Hz"
        return "$units · $channelCount ch"
    }

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "unprocessed"
        MediaRecorder.AudioSource.CAMCORDER -> "camcorder"
        MediaRecorder.AudioSource.MIC -> "mic"
        else -> "source_$source"
    }
}
