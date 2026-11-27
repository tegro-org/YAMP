package org.tegro.yamp.domain.queue

/** What a radio is started from (docs/phase-2-radio.md): a track, album or artist ref and its display title. */
data class RadioSeed(val ref: MediaRef, val title: String)

/** Where "Start radio" puts the radio (D51): a new queue, or an existing radio re-seeded in place. */
sealed interface RadioTarget {
    data object New : RadioTarget
    data class Replace(val queueId: Long) : RadioTarget
}
