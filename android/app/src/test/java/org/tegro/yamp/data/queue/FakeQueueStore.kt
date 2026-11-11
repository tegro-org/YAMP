package org.tegro.yamp.data.queue

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.tegro.yamp.domain.queue.PlayQueue
import org.tegro.yamp.domain.queue.QueueEntry
import org.tegro.yamp.domain.queue.QueueStore
import org.tegro.yamp.domain.queue.QueueSummary

/** An in-memory [QueueStore] that counts its writes: the manager must write on every mutation (E3). */
class FakeQueueStore : QueueStore {
    private var nextQueueId = 1L
    private var nextEntryId = 100L
    val queuesById = linkedMapOf<Long, PlayQueue>()
    val entriesByQueue = linkedMapOf<Long, MutableList<QueueEntry>>()
    private val active = MutableStateFlow<Long?>(null)
    private val version = MutableStateFlow(0)
    var writes = 0
        private set

    private fun touched() { writes++; version.value++ }

    override fun summaries(activeQueueId: Flow<Long?>): Flow<List<QueueSummary>> = combine(version, activeQueueId) { _, a ->
        queuesById.values.sortedWith(compareBy({ it.sortOrder }, { it.id })).map { q ->
            val es = entriesByQueue[q.id].orEmpty()
            QueueSummary(q, es.firstOrNull { it.entryId == q.cursorEntryId }, es.size, q.id == a)
        }
    }

    override fun activeQueueId(): Flow<Long?> = active
    override suspend fun setActiveQueueId(id: Long?) { active.value = id; touched() }
    override suspend fun queue(id: Long): PlayQueue? = queuesById[id]
    override suspend fun queues(): List<PlayQueue> = queuesById.values.sortedWith(compareBy({ it.sortOrder }, { it.id }))
    override suspend fun entries(queueId: Long): List<QueueEntry> = entriesByQueue[queueId].orEmpty().sortedBy { it.position }

    override suspend fun create(queue: PlayQueue): PlayQueue {
        val q = queue.copy(id = nextQueueId++)
        queuesById[q.id] = q
        entriesByQueue[q.id] = mutableListOf()
        touched()
        return q
    }

    override suspend fun update(queue: PlayQueue) { queuesById[queue.id] = queue; touched() }
    override suspend fun delete(queueId: Long) { queuesById.remove(queueId); entriesByQueue.remove(queueId); touched() }

    override suspend fun replaceEntries(queueId: Long, entries: List<QueueEntry>): List<QueueEntry> {
        val rows = entries.mapIndexed { i, e -> e.copy(entryId = nextEntryId++, queueId = queueId, position = i, originalPosition = i) }
        entriesByQueue[queueId] = rows.toMutableList()
        touched()
        return rows
    }

    override suspend fun insertEntries(queueId: Long, atPosition: Int, entries: List<QueueEntry>): List<QueueEntry> {
        val list = entriesByQueue.getOrPut(queueId) { mutableListOf() }
        val nextOriginal = (list.maxOfOrNull { it.originalPosition } ?: -1) + 1
        val rows = entries.mapIndexed { i, e -> e.copy(entryId = nextEntryId++, queueId = queueId, position = atPosition + i, originalPosition = nextOriginal + i) }
        val shifted = list.map { if (it.position >= atPosition) it.copy(position = it.position + rows.size) else it }
        entriesByQueue[queueId] = (shifted + rows).sortedBy { it.position }.toMutableList()
        touched()
        return rows
    }

    override suspend fun removeEntry(entryId: Long) {
        entriesByQueue.values.forEach { list -> list.removeAll { it.entryId == entryId } }
        touched()
    }

    override suspend fun reorder(queueId: Long, entryIdsInPlayOrder: List<Long>) {
        val list = entriesByQueue[queueId] ?: return
        val byId = list.associateBy { it.entryId }
        entriesByQueue[queueId] = entryIdsInPlayOrder.mapIndexedNotNull { i, id -> byId[id]?.copy(position = i) }.toMutableList()
        touched()
    }

    override suspend fun saveCursor(queueId: Long, cursorEntryId: Long?, positionMs: Long) {
        queuesById[queueId]?.let { queuesById[queueId] = it.copy(cursorEntryId = cursorEntryId, positionMs = positionMs) }
        touched()
    }
}
