package org.tegro.yamp.data.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Fixed output format" (D69, F5): the sink's resampler converts to the mixer's rate only while the switch is on. */
@androidx.annotation.OptIn(UnstableApi::class)
class FixedOutputRateTest {

    private val track441 = AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

    @Test
    fun offLeavesTheTrackRateAlone() {
        val fixed = FixedOutputRate(48_000)
        val resampler = fixed.newResampler()

        val out = resampler.configure(track441)

        assertFalse(resampler.isActive)
        assertEquals(44_100, out.sampleRate)
    }

    @Test
    fun onResamplesA441TrackToTheMixerRate_16BitStereo() {
        val fixed = FixedOutputRate(48_000)
        val resampler = fixed.newResampler()
        fixed.setEnabled(true)

        val out = resampler.configure(track441)

        assertTrue(resampler.isActive)
        assertEquals(48_000, out.sampleRate)
        assertEquals(2, out.channelCount)
        assertEquals(C.ENCODING_PCM_16BIT, out.encoding)
    }

    @Test
    fun theRateSurvivesAPlayerStop() {
        // Media3 resets the sink's processors when a player stops; the next track must still be resampled
        val fixed = FixedOutputRate(48_000)
        val resampler = fixed.newResampler()
        fixed.setEnabled(true)
        resampler.configure(track441)

        resampler.reset()

        assertEquals(48_000, resampler.configure(track441).sampleRate)
        assertTrue(resampler.isActive)
    }

    @Test
    fun theSwitchAppliesAtTheNextConfigure() {
        val fixed = FixedOutputRate(48_000)
        val resampler = fixed.newResampler()
        assertEquals(44_100, resampler.configure(track441).sampleRate)

        fixed.setEnabled(true)
        assertEquals(48_000, resampler.configure(track441).sampleRate)

        fixed.setEnabled(false)
        resampler.configure(track441)
        assertFalse(resampler.isActive)
    }

    @Test
    fun aTrackAlreadyAtTheMixerRatePassesThrough() {
        val fixed = FixedOutputRate(48_000)
        val resampler = fixed.newResampler()
        fixed.setEnabled(true)

        resampler.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))

        assertFalse(resampler.isActive)
    }
}
