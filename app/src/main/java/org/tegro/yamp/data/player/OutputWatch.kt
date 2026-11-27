package org.tegro.yamp.data.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Watches the playing track for what the journal could not see (upstream 1.14.8: with Bluetooth headphones the music
 * fell silent in every track, the timer counted on and jumped back 3–5 s, the journal was empty):
 * - the output stopped taking sound while the track plays ([Finding.OutputStall]);
 * - the position went back without a seek ([Finding.PositionBack]).
 *
 * Fed by the engine's own poll with where the track stands ([tick]): a position that moves forward is the output
 * taking sound, so no equalizer or audio-flow hook is needed. Pure; unit-tested.
 */
internal class OutputWatch {
    sealed interface Finding {
        /** The output took nothing for [durationMs] while the track played. */
        data class OutputStall(val startedAtMs: Long, val durationMs: Long) : Finding

        /** The position went back by [byMs] with no seek. */
        data class PositionBack(val byMs: Long) : Finding
    }

    /** When the position last moved forward: the output took the sound then. Null until the track first sounds. */
    private var tookAt: Long? = null
    private var stallFrom: Long? = null
    private var lastPosition: Long? = null

    /** A seek, a new track, another player: the position may jump and the output starts afresh. */
    fun reset() {
        tookAt = null
        stallFrom = null
        lastPosition = null
    }

    fun tick(nowMs: Long, playing: Boolean, positionMs: Long): Finding? {
        if (!playing) {
            reset()
            return null
        }
        val before = lastPosition
        lastPosition = positionMs
        // The first sight of a playing track says nothing: a start that has not sounded yet is not a stall
        if (before == null) return null
        var finding: Finding? = null
        if (positionMs > before) {
            tookAt = nowMs
            // The sound is back: how long it was out
            val from = stallFrom
            if (from != null) {
                stallFrom = null
                val duration = nowMs - from
                if (duration >= STALL_MS) finding = Finding.OutputStall(from, duration)
            }
        } else {
            val took = tookAt
            if (took != null && stallFrom == null && nowMs - took >= STALL_MS) stallFrom = took
        }
        // A jump this big back with no seek is a broken stream, smaller ones are the clock settling
        if (finding == null && positionMs < before - BACK_MS) finding = Finding.PositionBack(before - positionMs)
        return finding
    }

    companion object {
        /** How often the engine's poll feeds the watch while playing; only the timestamps matter to it. */
        const val TICK_MS = 100L

        /** Nothing taken for this long while playing is the output standing still, not the sink's rhythm. */
        const val STALL_MS = 1_000L

        /** Smaller jumps back are the clock settling, not a jump. */
        const val BACK_MS = 1_500L
    }
}

/**
 * Where the sound goes now, for the journal's output findings: the route a person would name, so a silent car can be
 * told from a silent phone. [describe] is pure; [current] reads the device. The device types are plain numbers where a
 * type is newer than the phone (Android 8's USB headset before Android 8): comparing with it is harmless.
 */
@SuppressLint("InlinedApi")
internal object AudioOutputs {
    private val EXTERNAL = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER
    )

    /** An output a person plugs in or pairs (not the phone's own speaker). */
    fun isExternal(device: AudioDeviceInfo): Boolean = device.isSink && device.type in EXTERNAL

    /** "Bluetooth «WH-1000XM4»", "wired headphones", "built-in speaker": the most likely route of the music now. */
    fun current(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "?"
        val outputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
        val external = outputs.filter(::isExternal)
        // A paired Bluetooth output is where the music goes when it is there; otherwise whatever is plugged in
        val picked = external.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } ?: external.firstOrNull()
        return picked?.let { describe(it.type, it.productName?.toString()) }
            ?: describe(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null)
    }

    /** Pure: the route's kind and the device's own name, when it has one. */
    fun describe(type: Int, name: String?): String {
        val kind = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (headset, call)"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> "line out"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built-in speaker"
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE"
            else -> "type $type"
        }
        val own = name?.takeIf { it.isNotBlank() && type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        return if (own != null) "$kind «$own»" else kind
    }
}
