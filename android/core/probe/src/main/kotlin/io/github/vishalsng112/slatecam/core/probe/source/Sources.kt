package io.github.vishalsng112.slatecam.core.probe.source

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioDeviceInfo
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import android.view.Display
import io.github.vishalsng112.slatecam.core.probe.model.AudioInfo
import io.github.vishalsng112.slatecam.core.probe.model.DeviceInfo
import io.github.vishalsng112.slatecam.core.probe.model.DisplayInfo
import io.github.vishalsng112.slatecam.core.probe.model.SystemInfo

object DeviceSource {

    fun read(context: Context): DeviceInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return DeviceInfo(
            deviceKey = "${Build.MANUFACTURER}/${Build.DEVICE}"
                .lowercase()
                .replace(" ", "_"),
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            device = Build.DEVICE,
            product = Build.PRODUCT,
            board = Build.BOARD,
            hardware = Build.HARDWARE,
            fingerprint = Build.FINGERPRINT,
            buildId = Build.ID,
            androidRelease = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH ?: "unknown",
            socManufacturer = Build.SOC_MANUFACTURER,
            socModel = Build.SOC_MODEL,
            abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
            isEmulator = Build.FINGERPRINT.contains("generic") ||
                Build.MODEL.contains("Emulator") ||
                Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu"),
            isLowRam = activityManager.isLowRamDevice,
        )
    }
}

object SystemSource {

    fun read(context: Context): SystemInfo {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val availableBytes = runCatching {
            StatFs(context.filesDir.absolutePath).availableBytes
        }.getOrDefault(0L)

        return SystemInfo(
            thermalStatus = thermalName(powerManager.currentThermalStatus),
            sustainedPerformanceSupported = null, // requires a Window; not probeable here
            totalRamMb = memoryInfo.totalMem / (1024L * 1024L),
            freeStorageMb = availableBytes / (1024L * 1024L),
            nativePageSizeKb = pageSizeKb(),
        )
    }

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }

    private fun pageSizeKb(): Long = runCatching {
        Os.sysconf(OsConstants._SC_PAGESIZE) / 1024L
    }.getOrDefault(0L)
}

object DisplaySource {

    fun read(context: Context): List<DisplayInfo> {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return displayManager.displays.map { display ->
            val hdr = runCatching { display.hdrCapabilities }.getOrNull()
            DisplayInfo(
                id = display.displayId,
                refreshHz = runCatching { display.mode.refreshRate.toInt() }.getOrDefault(0),
                maxRefreshHz = runCatching {
                    display.supportedModes.maxOfOrNull { it.refreshRate }?.toInt()
                }.getOrNull(),
                wideColorGamut = runCatching { display.isWideColorGamut }.getOrDefault(false),
                hdrTypes = hdr?.supportedHdrTypes?.map(::hdrName) ?: emptyList(),
                maxLuminanceNits = hdr?.desiredMaxLuminance ?: 0f,
            )
        }
    }

    private fun hdrName(type: Int): String = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "dolby_vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "hdr10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "hlg"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "hdr10_plus"
        else -> "type_$type"
    }
}

object AudioSource {

    fun read(context: Context): AudioInfo {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputFrames = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
            ?: 0
        return AudioInfo(
            sampleRates = listOf(48_000, 44_100),
            maxInputChannels = 2,
            pcm24Bit = supports24BitPcm(),
            lowLatency = outputFrames in 1..192,
            unprocessedSupported = audioManager
                .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
                ?.toBoolean() == true,
            inputDevices = runCatching {
                audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .mapNotNull { inputDeviceName(it.type) }
                    .distinct()
                    .sorted()
            }.getOrDefault(emptyList()),
            effectsAvailable = runCatching {
                buildList {
                    if (NoiseSuppressor.isAvailable()) add("noise_suppressor")
                    if (AcousticEchoCanceler.isAvailable()) add("acoustic_echo_canceler")
                    if (AutomaticGainControl.isAvailable()) add("automatic_gain_control")
                }
            }.getOrDefault(emptyList()),
        )
    }

    private fun inputDeviceName(type: Int): String? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin_mic"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
        else -> null
    }

    private fun supports24BitPcm(): Boolean = runCatching {
        AudioRecord.getMinBufferSize(
            48_000,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_24BIT_PACKED,
        ) > 0
    }.getOrDefault(false)
}
