package org.tegro.yamp.data.player.fakes

import android.os.SystemClock
import androidx.media3.common.Player
import org.tegro.yamp.data.player.OutputGain

/**
 * A player's [OutputGain] as [org.tegro.yamp.data.player.SampleGain] behaves in a real sink, modelled on the clock:
 * a ramp advances only while the player plays (frames are rendered only then — the sink writes nothing before play),
 * the silence of level 0 reaches the speaker [latencyMs] of playing later, and a level for the next stream applies at
 * the player's next flush. Every level the engine set or reached is in [levels].
 */
class ClockGain(private val player: ScriptedExoPlayer, override val latencyMs: Long = LATENCY_MS) : OutputGain {
    private var segments: List<OutputGain.Segment> = emptyList()
    private var segFrom = 1f
    private var segStartedMs = 0L
    private var current = 1f
    private var flowing = player.isPlaying
    private var lastMs = now()
    private var silentMs = 0L
    private var afterFlush: Float? = null
    private var lastIssued: Any? = null
    var scale = 1f
        private set

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                advance()
                flowing = isPlaying
            }
        })
        player.flushListeners += {
            advance()
            afterFlush?.let {
                current = it
                segments = emptyList()
                afterFlush = null
            }
            silentMs = if (current == 0f) Long.MAX_VALUE / 2 else 0L
        }
    }

    /** The level the speaker gets now (fade level × scale). */
    val heard: Float get() = level * scale

    override val level: Float get() = advance().let { current }

    override val outputSilent: Boolean get() = advance().let { current == 0f && silentMs >= latencyMs }

    override fun setScale(scale: Float) {
        this.scale = scale
    }

    override fun ramp(to: Float, durationMs: Long, shape: OutputGain.Shape, from: Float?, then: OutputGain.Segment?): Any {
        advance()
        afterFlush = null
        from?.let { current = it }
        segments = listOfNotNull(OutputGain.Segment(to, durationMs.coerceAtLeast(0L), shape), then)
        segFrom = current
        segStartedMs = 0L
        if (durationMs <= 0L) step(0L)
        val token = Any()
        lastIssued = token
        return token
    }

    override fun set(level: Float): Any = ramp(level, 0L)

    override fun freeze(token: Any) {
        if (lastIssued !== token) return
        advance()
        segments = emptyList()
    }

    override fun setAfterFlush(level: Float) {
        afterFlush = level
    }

    private fun now() = SystemClock.uptimeMillis()

    /** Moves the ramp on by the time the player played since the last look. */
    private fun advance() {
        val t = now()
        val dt = t - lastMs
        lastMs = t
        if (flowing && dt > 0L) step(dt)
    }

    private fun step(dt: Long) {
        var left = dt
        if (segments.isNotEmpty()) silentMs = 0L
        while (segments.isNotEmpty()) {
            val segment = segments.first()
            val need = segment.durationMs - segStartedMs
            if (left < need) {
                segStartedMs += left
                current = segment.shape.level(segFrom, segment.to, segStartedMs.toDouble() / segment.durationMs)
                left = 0L
                break
            }
            left -= need
            current = segment.to
            segments = segments.drop(1)
            segFrom = current
            segStartedMs = 0L
        }
        // Only the time spent at 0 counts: what is left of the step after the ramp ended
        if (current == 0f) silentMs += left else silentMs = 0L
    }

    companion object {
        /** A sound card buffer, ms: what a handover has to wait for after its fade. */
        const val LATENCY_MS = 100L
    }
}
