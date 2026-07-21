package tech.capullo.audio.latency

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks which output sink this device's media is routed to, exposing it as a [StateFlow] of
 * [OutputRoute] for route-aware features and observability.
 *
 * Android doesn't hand you "the active output" directly before API 31, so we derive it from the set
 * of connected output devices ([AudioManager.getDevices]) by priority: a connected Bluetooth or
 * wired sink wins over the built-in speaker, matching how the framework auto-routes `USAGE_MEDIA`
 * (which is what the native snapclient's oboe output uses). This is a heuristic, not the literally
 * routed device - verify on-device that the native player follows the same route this reports.
 *
 * The route [key] for a Bluetooth sink is its MAC address when available, else its product name.
 * `productName` needs no permission; `address` returns "" without BLUETOOTH_CONNECT on API 31+, so
 * we fall back rather than require that runtime permission.
 */
public class AudioRouteMonitor(
    context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _route = MutableStateFlow(computeRoute())
    public val route: StateFlow<OutputRoute> = _route.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            _route.value = computeRoute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            _route.value = computeRoute()
        }
    }

    /** Begin observing route changes. Emits the current route immediately via the StateFlow. */
    public fun start() {
        audioManager.registerAudioDeviceCallback(callback, handler)
        _route.value = computeRoute()
    }

    public fun stop() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    private fun computeRoute(): OutputRoute {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        // Highest-priority connected output wins - the sink media actually plays through.
        val chosen = outputs.maxByOrNull { priority(it.type) } ?: return OutputRoute.BUILTIN
        return when {
            isBluetooth(chosen.type) -> {
                val name = chosen.productName?.toString()?.trim().orEmpty()
                val addr = runCatching { bluetoothAddress(chosen) }.getOrDefault("")
                val id = addr.ifBlank { name }.ifBlank { "unknown" }
                OutputRoute("bt:$id", OutputRoute.Kind.BLUETOOTH, name.ifBlank { "Bluetooth" })
            }
            isWired(chosen.type) ->
                OutputRoute("wired", OutputRoute.Kind.WIRED, "Wired")
            chosen.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                chosen.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ->
                OutputRoute.BUILTIN
            else ->
                OutputRoute("other:${chosen.type}", OutputRoute.Kind.OTHER, "Other")
        }
    }

    private fun bluetoothAddress(device: AudioDeviceInfo): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) device.address?.trim().orEmpty() else ""

    private fun priority(type: Int): Int = when {
        isBluetooth(type) -> 3
        isWired(type) -> 2
        type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
            type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 1
        else -> 0
    }

    private fun isBluetooth(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && when (type) {
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            -> true
            else -> false
        }
    }

    private fun isWired(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        -> true
        else -> false
    }
}
