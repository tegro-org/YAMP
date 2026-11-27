package org.tegro.yamp.data.player

import kotlin.math.log10
import kotlin.math.pow

/**
 * The volume curve of a single player's fade (play, pause, seek, skip): linear in decibels, not in amplitude.
 * The ear hears loudness in dB, so a linear-amplitude fade-out lingers audibly and a fade-in seems abrupt;
 * in dB both take the same perceived time. Levels at or below [FLOOR_DB] count as silence. A fade that starts
 * mid-way (a pause during a rise) continues from the current level's dB. The end points are exact: a fade to
 * 0 ends at 0, a fade to a level ends at that level. Pure; unit-tested.
 */
object FadeCurve {
    const val FLOOR_DB = -60.0

    fun toDb(level: Float): Double = if (level <= 0f) FLOOR_DB else (20 * log10(level.toDouble())).coerceAtLeast(FLOOR_DB)

    fun fromDb(db: Double): Float = if (db <= FLOOR_DB) 0f else 10.0.pow(db / 20).toFloat()

    /** The level [part] (0..1) of the way from [from] to [to]. */
    fun level(from: Float, to: Float, part: Double): Float {
        if (part <= 0.0) return from
        if (part >= 1.0) return to
        val db = toDb(from) + (toDb(to) - toDb(from)) * part
        return fromDb(db).coerceIn(0f, 1f)
    }
}
