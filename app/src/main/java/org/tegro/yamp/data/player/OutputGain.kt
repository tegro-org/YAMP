package org.tegro.yamp.data.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * One player's fade level, applied to its samples (D98, [SampleGain]) rather than through `Player.volume`: an effect
 * that takes over the session's volume (Poweramp's DVC) ignores a player's own volume, so fades, crossfades and
 * the skip / pick handover must be in the audio itself.
 *
 * Ramps run in **output time**: they advance with the frames the player renders, not with the clock. A player that
 * does not play (paused, loading, not started) holds its ramp until it sounds, and a player that starts renders the
 * ramp's first frame first. What is rendered reaches the speaker [latencyMs] later (the sound card's buffer), so a
 * fade of a sounding player is heard that much after it is asked for; [outputSilent] says when all of it has been
 * heard. Called on the main thread.
 */
interface OutputGain {
    /** The fade level (0..1) the engine works from: a set level at once, a ramp's start until it runs. Excludes [setScale]. */
    val level: Float

    /** The level has reached 0 and everything the player still has buffered for the speaker is silence. */
    val outputSilent: Boolean

    /** How far the speaker runs behind what the player renders (its sound card buffer), ms; 0 when unknown. */
    val latencyMs: Long

    /** Ducking × the track's normalization, on top of [level]; changes are smoothed over a few ms (no click). */
    fun setScale(scale: Float)

    /**
     * Ramps the level from [from] (null: where it is) to [to] over [durationMs] of output in [shape], then on along
     * [then] if given. The latest call wins. Returns a token for [freeze].
     */
    fun ramp(to: Float, durationMs: Long, shape: Shape = Shape.DB, from: Float? = null, then: Segment? = null): Any

    /** Moves to [level] at once, smoothed over [DECLICK_MS] (no click). */
    fun set(level: Float): Any = ramp(level, DECLICK_MS, Shape.DB, from = null)

    /** Stops the ramp where it is, if [token] is still the latest call (a cancelled fade keeps its level). */
    fun freeze(token: Any)

    /** [level] for the player's next stream: applied once its current one is gone (stopped or flushed), never to it. */
    fun setAfterFlush(level: Float)

    /** One stretch of a ramp. */
    data class Segment(val to: Float, val durationMs: Long, val shape: Shape = Shape.DB)

    enum class Shape {
        /** Linear in decibels ([FadeCurve]): a single player's fade (play, pause, seek, skip, pick). */
        DB,

        /** Equal power (cos down, sin up; cos² + sin² = 1): the two sides of a crossfade. */
        EQUAL_POWER;

        /** The level [part] (0..1) of the way from [from] to [to]; the end points are exact. */
        fun level(from: Float, to: Float, part: Double): Float {
            if (part <= 0.0) return from
            if (part >= 1.0) return to
            return when (this) {
                DB -> FadeCurve.level(from, to, part)
                EQUAL_POWER -> if (to < from) {
                    (to + (from - to) * cos(part * PI / 2)).toFloat()
                } else {
                    (from + (to - from) * sin(part * PI / 2)).toFloat()
                }
            }.coerceIn(0f, 1f)
        }
    }

    companion object {
        /** A "set" level is reached over this long: an instant step in the samples clicks. */
        const val DECLICK_MS = 10L
    }
}
