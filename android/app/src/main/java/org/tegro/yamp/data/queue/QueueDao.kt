package org.tegro.yamp.data.queue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A queue with what the switcher shows: its size and its cursor entry. */
data class QueueRow(
    val queue: QueueEntity,
    val size: Int,
    val cursor: QueueEntryEntity?
)

@Dao
interface QueueDao {
    @Query("SELECT * FROM queues ORDER BY sortOrder, id")
    suspend fun queues(): List<QueueEntity>

    @Query("SELECT * FROM queues ORDER BY sortOrder, id")
    fun queuesFlow(): Flow<List<QueueEntity>>

    @Query("SELECT * FROM queues WHERE id = :id")
    suspend fun queue(id: Long): QueueEntity?

    @Query("SELECT COUNT(*) FROM queues")
    suspend fun count(): Int

    @Insert
    suspend fun insertQueue(queue: QueueEntity): Long

    @Update
    suspend fun updateQueue(queue: QueueEntity)

    @Query("DELETE FROM queues WHERE id = :id")
    suspend fun deleteQueue(id: Long)

    @Query("UPDATE queues SET cursorEntryId = :cursorEntryId, positionMs = :positionMs, updatedAt = :now WHERE id = :queueId")
    suspend fun saveCursor(queueId: Long, cursorEntryId: Long?, positionMs: Long, now: Long)

    @Query("SELECT * FROM queue_entries WHERE queueId = :queueId ORDER BY position")
    suspend fun entries(queueId: Long): List<QueueEntryEntity>

    @Query("SELECT * FROM queue_entries WHERE entryId = :entryId")
    suspend fun entry(entryId: Long): QueueEntryEntity?

    @Query("SELECT COUNT(*) FROM queue_entries WHERE queueId = :queueId")
    suspend fun size(queueId: Long): Int

    /** Sizes of every queue in one query, for the switcher. */
    @Query("SELECT queueId, COUNT(*) AS size FROM queue_entries GROUP BY queueId")
    fun sizesFlow(): Flow<List<QueueSize>>

    /** The cursor entries of every queue in one query, for the switcher. */
    @Query("SELECT e.* FROM queue_entries e JOIN queues q ON q.cursorEntryId = e.entryId")
    fun cursorsFlow(): Flow<List<QueueEntryEntity>>

    @Insert
    suspend fun insertEntries(entries: List<QueueEntryEntity>): List<Long>

    @Query("DELETE FROM queue_entries WHERE queueId = :queueId")
    suspend fun deleteEntries(queueId: Long)

    @Query("DELETE FROM queue_entries WHERE entryId = :entryId")
    suspend fun deleteEntry(entryId: Long)

    @Query("UPDATE queue_entries SET position = position + :by WHERE queueId = :queueId AND position >= :fromPosition")
    suspend fun shiftPositions(queueId: Long, fromPosition: Int, by: Int)

    @Query("UPDATE queue_entries SET position = :position WHERE entryId = :entryId")
    suspend fun setPosition(entryId: Long, position: Int)

    @Query("SELECT MAX(originalPosition) FROM queue_entries WHERE queueId = :queueId")
    suspend fun maxOriginalPosition(queueId: Long): Int?

    @Transaction
    suspend fun replaceEntries(queueId: Long, entries: List<QueueEntryEntity>): List<Long> {
        deleteEntries(queueId)
        return insertEntries(entries)
    }

    @Transaction
    suspend fun insertAt(queueId: Long, atPosition: Int, entries: List<QueueEntryEntity>): List<Long> {
        shiftPositions(queueId, atPosition, entries.size)
        return insertEntries(entries)
    }

    @Transaction
    suspend fun reorder(queueId: Long, entryIdsInPlayOrder: List<Long>) {
        entryIdsInPlayOrder.forEachIndexed { index, id -> setPosition(id, index) }
    }
}

data class QueueSize(val queueId: Long, val size: Int)
