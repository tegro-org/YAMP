package org.tegro.yamp.domain.queue

import kotlinx.coroutines.flow.Flow

/**
 * Persistence of queues (ARCHITECTURE E3, F1): every mutation is one transaction, written before the call
 * returns; nothing is held only in memory. Implemented over Room in `data/queue`.
 */
interface QueueStore {
    /** All queues in [PlayQueue.sortOrder], with their size and cursor entry — for the switcher. */
    fun summaries(activeQueueId: Flow<Long?>): Flow<List<QueueSummary>>

    fun activeQueueId(): Flow<Long?>
    suspend fun setActiveQueueId(id: Long?)

    suspend fun queue(id: Long): PlayQueue?
    suspend fun queues(): List<PlayQueue>
    suspend fun entries(queueId: Long): List<QueueEntry>

    suspend fun create(queue: PlayQueue): PlayQueue
    suspend fun update(queue: PlayQueue)
    suspend fun delete(queueId: Long)

    /** Replaces the whole content; returns the entries with their new ids, in play order. */
    suspend fun replaceEntries(queueId: Long, entries: List<QueueEntry>): List<QueueEntry>
    suspend fun insertEntries(queueId: Long, atPosition: Int, entries: List<QueueEntry>): List<QueueEntry>
    suspend fun removeEntry(entryId: Long)
    /** Rewrites the play order; the list holds every entry id of the queue in its new order. */
    suspend fun reorder(queueId: Long, entryIdsInPlayOrder: List<Long>)

    /** Cursor and position together, the most frequent write. */
    suspend fun saveCursor(queueId: Long, cursorEntryId: Long?, positionMs: Long)
}

/**
 * A source that extends a dynamic queue (E4): My Wave, a radio, a live mix. Appends only; never replaces.
 * Phase 1 has one implementation, the Yandex rotor station.
 */
interface DynamicSource {
    /** The next batch of tracks to append, excluding what the queue already holds; empty when exhausted. */
    suspend fun fetchBatch(exclude: Set<TrackRef>): List<TrackRef>

    /** What the listener did with a track (started, finished, skipped, liked…), for sources that learn. */
    suspend fun feedback(ref: TrackRef, event: String, playedMs: Long = 0L)
}
