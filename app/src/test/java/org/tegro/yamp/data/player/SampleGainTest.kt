package org.tegro.yamp.data.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fade level in the samples (D98): ramp shape, sample accuracy, reaching silence, both encodings. */
@androidx.annotation.OptIn(UnstableApi::class)
class SampleGainTest {

    private val rate = 48_000
    private val pcm16 = AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)
    private val float = AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT)

    private fun gain(format: AudioFormat = pcm16) = SampleGain().apply {
        configure(format)
        flush()
    }

    /** [frames] stereo frames of [left] / [right] through [g]; the output as (left, right) per frame. */
    private fun run16(g: SampleGain, frames: Int, left: Short = 10_000, right: Short = -20_000): List<Pair<Int, Int>> {
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames) {
            input.putShort(left)
            input.putShort(right)
        }
        input.flip()
        g.queueInput(input)
        val out = g.output
        return List(frames) { out.short.toInt() to out.short.toInt() }
    }

    private fun runFloat(g: SampleGain, frames: Int, left: Float = 0.5f, right: Float = -0.25f): List<Pair<Float, Float>> {
        val input = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder())
        repeat(frames) {
            input.putFloat(left)
            input.putFloat(right)
        }
        input.flip()
        g.queueInput(input)
        val out = g.output
        return List(frames) { out.float to out.float }
    }

    private fun framesOf(ms: Long) = (ms * rate / 1000).toInt()

    @Test
    fun aFadeOutFollowsTheDbCurveFrameByFrame_onEveryChannel() {
        val g = gain()
        g.ramp(0f, 100L, from = 1f)
        val n = framesOf(100L)
        val out = run16(g, n + 10)

        for (k in listOf(1, n / 4, n / 2, 3 * n / 4, n - 1)) {
            val level = FadeCurve.level(1f, 0f, k.toDouble() / n)
            assertEquals("frame $k left", (10_000 * level).roundToInt(), out[k - 1].first)
            assertEquals("frame $k right", (-20_000 * level).roundToInt(), out[k - 1].second)
        }
        assertTrue("half way is -30 dB", out[n / 2 - 1].first in 314..318)
    }

    @Test
    fun theRampIsSampleAccurate_startsWithTheNextFrame_andEndsAtExactlyItsLength() {
        val g = gain()
        run16(g, 480) // full level before
        g.ramp(0f, 10L, from = 1f, shape = OutputGain.Shape.EQUAL_POWER)
        val n = framesOf(10L)
        val out = run16(g, n + 5)

        assertEquals("the ramp starts with the first frame: half way is cos 45°", 7_071, out[n / 2 - 1].first)
        assertTrue("one frame before the end still sounds", out[n - 2].first > 0)
        assertEquals("the last frame of the ramp is silent", 0, out[n - 1].first)
        assertTrue("and silence after it", out.drop(n).all { it.first == 0 && it.second == 0 })
    }

    @Test
    fun aRampSpansBuffers_andARiseFromSilenceStartsAtZero() {
        val g = gain()
        g.ramp(1f, 20L, from = 0f)
        val n = framesOf(20L)
        val first = run16(g, n / 2)
        val second = run16(g, n)

        assertTrue("starts from silence", first[0].first < 50)
        assertTrue("rising across the buffers", first.last().first < second[0].first)
        assertEquals("full at the end", 10_000, second.last().first)
        assertEquals(1f, g.level, 0f)
    }

    @Test
    fun equalPowerCrossfadeSidesSumToOne() {
        val down = gain()
        val up = gain()
        down.ramp(0f, 100L, OutputGain.Shape.EQUAL_POWER, from = 1f)
        up.ramp(1f, 100L, OutputGain.Shape.EQUAL_POWER, from = 0f)
        val n = framesOf(100L)
        val a = run16(down, n)
        val b = run16(up, n)
        for (k in listOf(n / 8, n / 2, 7 * n / 8)) {
            val x = a[k].first / 10_000.0
            val y = b[k].first / 10_000.0
            assertEquals("power at frame $k", 1.0, x * x + y * y, 0.01)
        }
    }

    @Test
    fun outputIsSilentOnlyOnceTheZerosFillTheTrackBuffer() {
        val g = gain()
        g.trackBufferUs = 100_000L // 4800 frames
        g.ramp(0f, 10L, from = 1f)
        run16(g, 1_000) // the ramp is the first 480 frames
        assertEquals(0f, g.level, 0f)
        assertFalse("the ramp's sound is still in the buffer", g.outputSilent)
        // 4800 frames of buffer + one 1000-frame chunk that may still wait in the sink: 520 + 5 × 1000 is short of it
        repeat(5) { run16(g, 1_000) }
        assertFalse(g.outputSilent)
        run16(g, 1_000)
        assertTrue(g.outputSilent)
        assertEquals(100L, g.latencyMs)

        g.ramp(1f, 10L)
        assertFalse("a new ramp up is not silence", g.outputSilent)
    }

    @Test
    fun aLevelForTheNextStreamWaitsForTheFlush() {
        val g = gain()
        g.set(0f)
        run16(g, 960)
        g.setAfterFlush(1f)
        assertTrue("what the old stream still renders stays silent", run16(g, 480).all { it.first == 0 })
        g.flush()
        assertEquals("the next stream at full level", 10_000, run16(g, 480)[0].first)
    }

    @Test
    fun aSetLevelCountsAtOnce_andIsReachedWithoutAClick() {
        val g = gain()
        g.set(0f)
        assertEquals("the engine reads it at once", 0f, g.level, 0f)
        val out = run16(g, framesOf(OutputGain.DECLICK_MS) + 10)
        assertTrue("not a step", out[0].first > 9_000)
        assertEquals(0, out.last().first)
    }

    @Test
    fun theScaleIsSmoothed() {
        val g = gain()
        g.setScale(0.2f)
        val out = run16(g, framesOf(OutputGain.DECLICK_MS) + 10)
        assertTrue("moves gradually", out[framesOf(OutputGain.DECLICK_MS) / 2].first in 4_000..6_000)
        assertEquals(2_000, out.last().first)
    }

    @Test
    fun aFrozenRampStaysWhereItIs_unlessANewerOneCame() {
        val g = gain()
        val token = g.ramp(0f, 100L, from = 1f)
        run16(g, framesOf(50L))
        g.freeze(token)
        val held = run16(g, 100).map { it.first }.distinct()
        assertEquals("one level", 1, held.size)
        assertTrue(held[0] in 1..9_999)

        val newer = g.ramp(1f, 10L)
        g.freeze(token) // the old fade's cancel comes late: the newer ramp runs on
        run16(g, framesOf(20L))
        assertEquals(1f, g.level, 0f)
        g.freeze(newer)
    }

    @Test
    fun floatPcmIsFadedToo() {
        val g = gain(float)
        g.ramp(0f, 100L, from = 1f)
        val n = framesOf(100L)
        val out = runFloat(g, n + 1)
        val level = FadeCurve.level(1f, 0f, (n / 2).toDouble() / n)
        assertEquals(0.5f * level, out[n / 2 - 1].first, 1e-5f)
        assertEquals(-0.25f * level, out[n / 2 - 1].second, 1e-5f)
        assertEquals(0f, out[n].first, 0f)
    }

    @Test
    fun fullLevelPassesSamplesThroughUntouched() {
        val g = gain()
        assertTrue(run16(g, 100).all { it == 10_000 to -20_000 })
    }

    @Test
    fun otherEncodingsAreLeftAlone() {
        val g = SampleGain()
        g.configure(AudioFormat(rate, 2, C.ENCODING_PCM_24BIT))
        assertFalse(g.isActive)
    }
}
