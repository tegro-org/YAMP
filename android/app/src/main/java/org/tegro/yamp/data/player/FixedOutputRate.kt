package org.tegro.yamp.data.player

import android.content.Context
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
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
 */
@OptIn(UnstableApi::class)
class FixedOutputRate(val mixerRateHz: Int) {

    /** The rate the sinks produce: the mixer's own. */
    val targetRateHz: Int get() = mixerRateHz

    /** A sink for one player; call once per player. */
    fun buildSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    arrayOf<AudioProcessor>(newResampler()), SilenceSkippingAudioProcessor(), SonicAudioProcessor()
                )
            )
            .build()

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

    companion object {
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
