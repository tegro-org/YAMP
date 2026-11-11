package org.tegro.yamp.data.queue

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.tegro.yamp.data.cache.CacheDao
import org.tegro.yamp.data.cache.CacheDatabase
import org.tegro.yamp.data.cache.ValueEntity
import org.tegro.yamp.domain.queue.PlayQueue
import org.tegro.yamp.domain.queue.QueueEntry
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.queue.QueueStore
import org.tegro.yamp.domain.queue.QueueSummary
import org.tegro.yamp.domain.queue.RepeatMode
import org.tegro.yamp.domain.queue.TrackRef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [QueueStore] over Room (docs/phase-1-queues.md §2). The active queue id lives in `cache_values`
 * (owner [CacheDatabase.SHARED], key [ACTIVE_QUEUE]) so a switch is one transaction with the cursor save.
 */
@Singleton
class RoomQueueStore(
    private val dao: QueueDao,
    private val values: CacheDao,
    private val now: () -> Long
) : QueueStore {
    /** The injected form: the clock is a test seam only. */
    @Inject
    constructor(dao: QueueDao, values: CacheDao) : this(dao, values, System::currentTimeMillis)

    override fun summaries(activeQueueId: Flow<Long?>): Flow<List<QueueSummary>> =
        combine(dao.queuesFlow(), dao.sizesFlow(), dao.cursorsFlow(), activeQueueId) { queues, sizes, cursors, active ->
            val sizeOf = sizes.associate { it.queueId to it.size }
            val cursorOf = cursors.associateBy { it.queueId }
            queues.map { q ->
                QueueSummary(
                    queue = q.toDomain(),
                    current = cursorOf[q.id]?.takeIf { it.entryId == q.cursorEntryId }?.toDomain(),
                    size = sizeOf[q.id] ?: 0,
                    isActive = q.id == active
                )
            }
        }

    override fun activeQueueId(): Flow<Long?> = values.valueFlow(CacheDatabase.SHARED, ACTIVE_QUEUE).map { it?.toLongOrNull() }

    override suspend fun setActiveQueueId(id: Long?) {
        if (id == null) values.deleteValue(CacheDatabase.SHARED, ACTIVE_QUEUE)
        else values.putValue(ValueEntity(CacheDatabase.SHARED, ACTIVE_QUEUE, id.toString()))
    }

    override suspend fun queue(id: Long): PlayQueue? = dao.queue(id)?.toDomain()
    override suspend fun queues(): List<PlayQueue> = dao.queues().map { it.toDomain() }
    override suspend fun entries(queueId: Long): List<QueueEntry> = dao.entries(queueId).map { it.toDomain() }

    override suspend fun create(queue: PlayQueue): PlayQueue {
        val id = dao.insertQueue(queue.copy(updatedAt = now()).toEntity().copy(id = 0L))
        return queue.copy(id = id)
    }

    override suspend fun update(queue: PlayQueue) = dao.updateQueue(queue.copy(updatedAt = now()).toEntity())
    override suspend fun delete(queueId: Long) = dao.deleteQueue(queueId)

    override suspend fun replaceEntries(queueId: Long, entries: List<QueueEntry>): List<QueueEntry> {
        val rows = entries.mapIndexed { i, e -> e.copy(queueId = queueId, position = i, originalPosition = i).toEntity().copy(entryId = 0L) }
        val ids = dao.replaceEntries(queueId, rows)
        return rows.zip(ids) { row, id -> row.copy(entryId = id).toDomain() }
    }

    override suspend fun insertEntries(queueId: Long, atPosition: Int, entries: List<QueueEntry>): List<QueueEntry> {
        val nextOriginal = (dao.maxOriginalPosition(queueId) ?: -1) + 1
        val rows = entries.mapIndexed { i, e ->
            e.copy(queueId = queueId, position = atPosition + i, originalPosition = nextOriginal + i).toEntity().copy(entryId = 0L)
        }
        val ids = dao.insertAt(queueId, atPosition, rows)
        return rows.zip(ids) { row, id -> row.copy(entryId = id).toDomain() }
    }

    override suspend fun removeEntry(entryId: Long) = dao.deleteEntry(entryId)
    override suspend fun reorder(queueId: Long, entryIdsInPlayOrder: List<Long>) = dao.reorder(queueId, entryIdsInPlayOrder)
    override suspend fun saveCursor(queueId: Long, cursorEntryId: Long?, positionMs: Long) = dao.saveCursor(queueId, cursorEntryId, positionMs, now())

    companion object {
        const val ACTIVE_QUEUE = "active_queue"
    }
}

internal fun QueueEntity.toDomain() = PlayQueue(
    id = id, name = name, kind = QueueKind.valueOf(kind), origin = origin, seed = seed, account = account,
    cursorEntryId = cursorEntryId, positionMs = positionMs, shuffle = shuffle,
    repeat = RepeatMode.entries.firstOrNull { it.name == repeat } ?: RepeatMode.ALL,
    sortOrder = sortOrder, updatedAt = updatedAt
)

internal fun PlayQueue.toEntity() = QueueEntity(
    id = id, name = name, kind = kind.name, origin = origin, seed = seed, account = account,
    cursorEntryId = cursorEntryId, positionMs = positionMs, shuffle = shuffle, repeat = repeat.name,
    sortOrder = sortOrder, updatedAt = updatedAt
)

internal fun QueueEntryEntity.toDomain() = QueueEntry(
    entryId = entryId, queueId = queueId, position = position, originalPosition = originalPosition,
    ref = TrackRef(ref), title = title, artist = artist, coverUrl = coverUrl, durationMs = durationMs
)

internal fun QueueEntry.toEntity() = QueueEntryEntity(
    entryId = entryId, queueId = queueId, position = position, originalPosition = originalPosition,
    ref = ref.value, trackId = ref.id, title = title, artist = artist, coverUrl = coverUrl, durationMs = durationMs
)
