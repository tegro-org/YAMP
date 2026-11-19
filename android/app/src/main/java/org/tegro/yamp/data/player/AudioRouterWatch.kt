package org.tegro.yamp.data.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether MonJaro Audio Router (`com.usbswitch`, a head-unit app for a USB amplifier / passenger headphones) is
 * likely routing media. While its USB routing is on it rewrites every routed player's volume every 2.5 s, which breaks
 * crossfades and fades (analysis 2026-11-18), so the player falls back to hard cuts then.
 *
 * What a normal app can see on Android 9: the router's dynamic AudioPolicy and its per-player volume writes are
 * invisible, and `AudioPlaybackConfiguration` has no device before API 31. So this is a heuristic: the router is
 * installed **and** a USB audio output is connected — the only case in which it can route anywhere. It can be wrong
 * when the amplifier is plugged in but its "ЗВУК НА USB" switch is off (then the fades are lost for nothing).
 */
interface AudioRouterWatch {
    /** The router app is on the device (the Settings hint shows). */
    val installed: Boolean

    /** The router is installed and a USB audio output is connected: transitions become hard cuts. */
    val active: StateFlow<Boolean>

    object None : AudioRouterWatch {
        override val installed = false
        override val active: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    }

    companion object {
        const val PACKAGE = "com.usbswitch"

        /** Output device types the router routes media to (an external USB amplifier). */
        val USB_OUTPUTS = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)

        /** Pure: the heuristic itself, unit-tested. */
        fun isActive(installed: Boolean, outputTypes: Collection<Int>): Boolean = installed && outputTypes.any { it in USB_OUTPUTS }
    }
}

@Singleton
class AudioRouterDetector @Inject constructor(
    @param:ApplicationContext private val context: Context
) : AudioRouterWatch {

    override val installed: Boolean =
        runCatching { context.packageManager.getPackageInfo(AudioRouterWatch.PACKAGE, 0) }.isSuccess

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val state = MutableStateFlow(compute())
    override val active: StateFlow<Boolean> = state.asStateFlow()

    init {
        if (installed) {
            audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { state.value = compute() }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { state.value = compute() }
            }, Handler(Looper.getMainLooper()))
        }
    }

    private fun compute(): Boolean = AudioRouterWatch.isActive(
        installed,
        runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type } }.getOrDefault(emptyList())
    )
}
