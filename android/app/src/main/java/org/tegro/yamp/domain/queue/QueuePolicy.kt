package org.tegro.yamp.domain.queue

import kotlin.random.Random

/** Pure rules of a queue's order and advance (ARCHITECTURE I1): no Android, no state, unit-tested. */
object QueuePolicy {

    /** What to do when the cursor entry ends or the user presses next. */
    sealed interface Advance {
        /** Move the cursor to this index of the play order. */
        data class To(val index: Int) : Advance
        /** A dynamic queue has nothing after the cursor: ask its source for more. */
        data object FetchMore : Advance
        /** Nothing to play next: stop at the end (repeat off, static queue). */
        data object End : Advance
    }

    /**
     * The next index after [cursorIndex] in a queue of [size] entries. [skip] marks entries to pass over
     * (unavailable provider, explicit track); a queue of only skipped entries ends. `ONE` repeats the cursor
     * only when the track ended by itself ([manual] = false): a press on next always moves on.
     */
    fun next(
        cursorIndex: Int,
        size: Int,
        kind: QueueKind,
        repeat: RepeatMode,
        manual: Boolean,
        skip: (Int) -> Boolean = { false }
    ): Advance {
        if (size == 0) return Advance.End
        if (repeat == RepeatMode.ONE && !manual && cursorIndex in 0 until size) return Advance.To(cursorIndex)
        val dynamic = kind == QueueKind.WAVE || kind == QueueKind.RADIO
        var i = cursorIndex + 1
        while (i < size) {
            if (!skip(i)) return Advance.To(i)
            i++
        }
        if (dynamic) return Advance.FetchMore
        if (repeat == RepeatMode.OFF) return Advance.End
        // ALL (and ONE on a manual press): wrap to the first playable entry, the cursor itself included
        for (j in 0..cursorIndex.coerceAtLeast(0).coerceAtMost(size - 1)) if (!skip(j)) return Advance.To(j)
        return Advance.End
    }

    /** The previous index, or null when the cursor is first (the caller then restarts the track). */
    fun previous(cursorIndex: Int, skip: (Int) -> Boolean = { false }): Int? {
        var i = cursorIndex - 1
        while (i >= 0) {
            if (!skip(i)) return i
            i--
        }
        return null
    }

    /**
     * A shuffled play order over [entries] (any order) that keeps the cursor entry first: what plays now
     * stays, what follows is random. Returns entry ids in the new play order.
     */
    fun shuffled(entries: List<QueueEntry>, cursorEntryId: Long?, random: Random = Random.Default): List<Long> {
        val rest = entries.filter { it.entryId != cursorEntryId }.map { it.entryId }.shuffled(random)
        val head = entries.firstOrNull { it.entryId == cursorEntryId }?.entryId
        return listOfNotNull(head) + rest
    }

    /** The insertion order restored: entry ids sorted by [QueueEntry.originalPosition]. */
    fun unshuffled(entries: List<QueueEntry>): List<Long> = entries.sortedBy { it.originalPosition }.map { it.entryId }

    /** Where new entries go when added "to the end" in shuffle mode: a random spot after the cursor. */
    fun randomInsertPosition(cursorIndex: Int, size: Int, random: Random = Random.Default): Int =
        if (size <= cursorIndex + 1) size else random.nextInt(cursorIndex + 1, size + 1)
}
