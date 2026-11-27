package org.tegro.yamp.data.player

import android.content.Context
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer

/**
 * Fixed output format (D69, F5; always on since 2026-11-18). Every player's audio sink gets a [Resampler] in front
 * of Media3's own processors; it resamples to the device mixer's native rate. The system then no longer resamples, and
 * the audio output has the same format for every track and both players (44.1, 48 or 96 kHz sources alike). Output
 * stays 16-bit PCM; offload is never used.
 *
 * The target is read each time the sink configures its processors — Media3 resets processors when a player stops,
 * so the rate is re-applied on every configure rather than set once.
 *
 * The player's [SampleGain] follows the resampler (D98): fades are applied to the samples at the output rate. It sits
 * before Media3's silence skipping and speed processors, which are idle in YAMP (no skip-silence, speed 1).
 */
@OptIn(UnstableApi::class)
class FixedOutputRate(val mixerRateHz: Int) {

    /** The rate the sinks produce: the mixer's own. */
    val targetRateHz: Int get() = mixerRateHz

    /** A sink for one player, with that player's [gain]; call once per player. */
    fun buildSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean, gain: SampleGain): AudioSink =
        GatedSink(
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioTrackBufferSizeProvider(
                    DefaultAudioTrackBufferSizeProvider.Builder().setTargetPcmBufferDurationUs(OUTPUT_BUFFER_US).build()
                )
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        arrayOf<AudioProcessor>(newResampler(), gain), SilenceSkippingAudioProcessor(), SonicAudioProcessor()
                    )
                )
                .build(),
            gain
        )

    fun newResampler(): AudioProcessor = Resampler(this)

    /** Media3's Sonic used only to change the sample rate; the target is applied at every [configure]. */
    private class Resampler(private val owner: FixedOutputRate) : AudioProcessor {
        private val sonic = SonicAudioProcessor()

        override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            sonic.setOutputSampleRateHz(owner.targetRateHz)
            return sonic.configure(inputAudioFormat)
        }

        override fun isActive(): Boolean = sonic.isActive
        override fun queueInput(inputBuffer: ByteBuffer) = sonic.queueInput(inputBuffer)
        override fun queueEndOfStream() = sonic.queueEndOfStream()
        override fun getOutput(): ByteBuffer = sonic.output
        override fun isEnded(): Boolean = sonic.isEnded
        override fun getDurationAfterProcessorApplied(durationUs: Long): Long =
            sonic.getDurationAfterProcessorApplied(durationUs)

        @Deprecated("Media3 keeps both flush variants")
        override fun flush() = sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)
        override fun flush(streamMetadata: AudioProcessor.StreamMetadata) = sonic.flush(streamMetadata)
        override fun reset() = sonic.reset()
    }

    /**
     * Writes as little as possible before the player plays (D98): one buffer, which Media3 1.11 needs to call the
     * player ready (`MediaCodecAudioRenderer.isReady` asks the sink for pending data), then nothing until play. Media3
     * otherwise fills a prepared, paused player's whole AudioTrack ahead of time, and that pre-filled sound would carry
     * the level it had then — a crossfade's or a pick's incoming track would start at the wrong level for a whole
     * buffer. The engine sets an incoming player's level to 0 before it prepares, so that one buffer is silence and the
     * ramp it sets at play runs from the next frame. Also reports the AudioTrack's buffer size to [gain]
     * ([SampleGain.outputSilent]).
     */
    class GatedSink(sink: AudioSink, private val gain: SampleGain) : ForwardingAudioSink(sink) {
        @Volatile private var playing = false

        override fun play() {
            playing = true
            super.play()
        }

        override fun pause() {
            playing = false
            super.pause()
        }

        override fun reset() {
            playing = false
            super.reset()
        }

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            if (!playing && hasPendingData()) return false
            val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            gain.trackBufferUs = audioTrackBufferSizeUs
            return handled
        }
    }

    companion object {
        /**
         * The AudioTrack holds this much sound (Media3's default is 500 ms, never less than the device's minimum). A
         * fade is heard about that long after it is rendered and a cut drops what is buffered, so a short buffer keeps
         * fades audible and handovers inside it (F2), a long one is safer against underruns: tune it on the unit.
         */
        const val OUTPUT_BUFFER_US = 300_000

        /** Used when the platform does not report its mixer rate. */
        const val FALLBACK_RATE_HZ = 48_000

        /** The device mixer's native rate, from the platform's output properties. */
        fun mixerRate(context: Context): Int {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            return am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
                ?.takeIf { it > 0 } ?: FALLBACK_RATE_HZ
        }
    }
}
