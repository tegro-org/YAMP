package org.tegro.yamp.data.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * A player's fade level applied to its samples (D98): the [OutputGain] of one player, as an audio processor in its
 * sink's chain (after the resampler, [FixedOutputRate.buildSink]). Ramps are sample-accurate — the level is computed
 * for every frame — and advance with the frames rendered. 16-bit and float PCM; any other encoding passes through
 * untouched (the processor is then inactive and [outputSilent] only says the level is 0).
 *
 * Commands come from the main thread (volatile, the latest wins) and are taken up by the playback thread at its next
 * buffer. [trackBufferUs] is the sink's AudioTrack buffer, reported by [GatedSink]: once that much silence (and the
 * largest buffer this processor hands on) has been rendered at level 0, nothing audible is left in the output.
 */
@OptIn(UnstableApi::class)
class SampleGain : BaseAudioProcessor(), OutputGain {

    /** An engine command: [nominal] is the level the engine reads until the playback thread takes it up. */
    private class Command(val from: Float?, val segments: List<OutputGain.Segment>, val nominal: Float, val hold: Boolean = false)

    // Main thread
    private var lastIssued: Command? = null

    // Shared
    @Volatile private var pending: Command? = null
    @Volatile private var afterFlush = Float.NaN
    @Volatile private var scaleTarget = 1f
    @Volatile private var published = 1f
    @Volatile private var silentFrames = 0L
    @Volatile private var largestChunk = 0
    @Volatile private var rateHz = 0

    /** The sink's AudioTrack buffer (µs), or [C.TIME_UNSET]; set on the playback thread by [GatedSink]. */
    @Volatile var trackBufferUs: Long = C.TIME_UNSET

    // Playback thread
    private var current = 1f
    private var scale = 1f
    private var segments: List<OutputGain.Segment> = emptyList()
    private var segIndex = 0
    private var segFrom = 1f
    private var segFrames = 0L
    private var segPos = 0L
    private var dbFrom = 0.0
    private var dbTo = 0.0

    override val level: Float get() = pending?.nominal ?: published

    override val outputSilent: Boolean
        get() {
            if (pending != null) return false
            val rate = rateHz
            if (rate <= 0) return published == 0f
            val bufferUs = trackBufferUs.takeIf { it != C.TIME_UNSET && it > 0L } ?: MAX_TRACK_BUFFER_US
            val needed = bufferUs * rate / 1_000_000L + largestChunk
            return silentFrames >= needed
        }

    override val latencyMs: Long get() = trackBufferUs.takeIf { it != C.TIME_UNSET && it > 0L }?.div(1000L) ?: 0L

    override fun setScale(scale: Float) {
        scaleTarget = scale.coerceIn(0f, 1f)
    }

    override fun ramp(to: Float, durationMs: Long, shape: OutputGain.Shape, from: Float?, then: OutputGain.Segment?): Any {
        val first = OutputGain.Segment(to.coerceIn(0f, 1f), durationMs.coerceAtLeast(0L), shape)
        val all = if (then == null) listOf(first) else listOf(first, then)
        // A set level counts at once; a ramp from where it is counts from there until it runs
        val nominal = if (durationMs <= OutputGain.DECLICK_MS && from == null) first.to else from ?: level
        return issue(Command(from?.coerceIn(0f, 1f), all, nominal))
    }

    override fun freeze(token: Any) {
        if (lastIssued !== token) return
        issue(Command(null, emptyList(), published, hold = true))
    }

    override fun setAfterFlush(level: Float) {
        afterFlush = level.coerceIn(0f, 1f)
    }

    private fun issue(command: Command): Any {
        afterFlush = Float.NaN
        lastIssued = command
        pending = command
        return command
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val handled = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!handled) {
            rateHz = 0
            return AudioProcessor.AudioFormat.NOT_SET
        }
        rateHz = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    override fun onFlush() {
        val next = afterFlush
        if (!next.isNaN()) {
            afterFlush = Float.NaN
            segments = emptyList()
            current = next
            published = next
        }
        // A flush empties the output: at level 0 nothing audible is left
        silentFrames = if (current == 0f && segments.isEmpty()) Long.MAX_VALUE / 2 else 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        takeCommand()
        val format = inputAudioFormat
        val channels = format.channelCount
        val float = format.encoding == C.ENCODING_PCM_FLOAT
        val frames = bytes / format.bytesPerFrame
        if (frames > largestChunk) largestChunk = frames
        val out = replaceOutputBuffer(bytes)
        if (segments.isEmpty() && scale == scaleTarget) {
            copySteady(inputBuffer, out, current * scale, frames.toLong())
        } else {
            repeat(frames) { rampFrame(inputBuffer, out, channels, float) }
        }
        // Whatever an odd byte count left over passes through
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
        published = current
    }

    private fun copySteady(input: ByteBuffer, out: ByteBuffer, gain: Float, frames: Long) {
        when (gain) {
            1f -> {
                silentFrames = 0L
                out.put(input)
            }
            0f -> {
                silentFrames += frames
                repeat(input.remaining()) { out.put(0) }
                input.position(input.limit())
            }
            else -> {
                silentFrames = 0L
                val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
                if (float) while (input.remaining() >= 4) out.putFloat(input.float * gain)
                else while (input.remaining() >= 2) out.putShort(scaled(input.short, gain))
            }
        }
    }

    /** One frame on a ramp: every channel gets the frame's gain. */
    private fun rampFrame(input: ByteBuffer, out: ByteBuffer, channels: Int, float: Boolean) {
        val g = nextGain()
        countSilence(g)
        repeat(channels) {
            if (float) out.putFloat(input.float * g) else out.putShort(scaled(input.short, g))
        }
    }

    private fun countSilence(gain: Float) {
        if (gain == 0f) silentFrames++ else silentFrames = 0L
    }

    private fun scaled(sample: Short, gain: Float): Short =
        (sample * gain).let { if (it >= 0f) it + 0.5f else it - 0.5f }.toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    /** Takes up the engine's latest command: the next frame is its first. */
    private fun takeCommand() {
        val command = pending ?: return
        pending = null
        if (command.hold) {
            segments = emptyList()
            return
        }
        command.from?.let { current = it }
        segments = command.segments
        segIndex = 0
        startSegment()
    }

    private fun startSegment() {
        val segment = segments.getOrNull(segIndex) ?: run {
            segments = emptyList()
            return
        }
        segFrom = current
        segFrames = (segment.durationMs * rateHz / 1000L).coerceAtLeast(1L)
        segPos = 0L
        dbFrom = FadeCurve.toDb(segFrom)
        dbTo = FadeCurve.toDb(segment.to)
    }

    /** The gain of the next frame: the ramp's level × the smoothed scale. */
    private fun nextGain(): Float {
        if (segments.isNotEmpty()) {
            val segment = segments[segIndex]
            segPos++
            current = if (segPos >= segFrames) {
                segment.to
            } else {
                val part = segPos.toDouble() / segFrames
                when (segment.shape) {
                    OutputGain.Shape.DB -> FadeCurve.fromDb(dbFrom + (dbTo - dbFrom) * part).coerceIn(0f, 1f)
                    OutputGain.Shape.EQUAL_POWER -> segment.shape.level(segFrom, segment.to, part)
                }
            }
            if (segPos >= segFrames) {
                segIndex++
                startSegment()
            }
        }
        if (scale != scaleTarget) {
            val step = 1f / (OutputGain.DECLICK_MS * rateHz / 1000L).coerceAtLeast(1L)
            val target = scaleTarget
            scale = if (scale < target) (scale + step).coerceAtMost(target) else (scale - step).coerceAtLeast(target)
        }
        return (current * scale).coerceIn(0f, 1f)
    }

    companion object {
        /** Media3's largest PCM AudioTrack buffer (`DefaultAudioTrackBufferSizeProvider`): used while the real one is unknown. */
        const val MAX_TRACK_BUFFER_US = 750_000L
    }
}
