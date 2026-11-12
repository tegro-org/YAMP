package org.tegro.yamp.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(row: HistoryEntity): Long

    /** A new row, and the oldest ones beyond [keep] go — one transaction, so a crash never leaves the cap broken. */
    @Transaction
    suspend fun insertAndPrune(row: HistoryEntity, keep: Int): Long {
        val id = insert(row)
        prune(keep)
        return id
    }

    @Query("UPDATE history SET playedMs = :playedMs WHERE id = :id")
    suspend fun updatePlayed(id: Long, playedMs: Long)

    /** The newest row of [trackId], for the merge within the window. */
    @Query("SELECT * FROM history WHERE trackId = :trackId ORDER BY startedAt DESC, id DESC LIMIT 1")
    suspend fun lastFor(trackId: String): HistoryEntity?

    @Query("SELECT * FROM history ORDER BY startedAt DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE queueId = :queueId ORDER BY startedAt DESC, id DESC LIMIT :limit")
    fun recentOf(queueId: Long, limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT COUNT(*) FROM history WHERE startedAt >= :sinceMs")
    fun countSince(sinceMs: Long): Flow<Int>

    /** Every queue in the history, each with the name of its newest row. */
    @Query(
        "SELECT queueId, queueName FROM history WHERE id IN " +
            "(SELECT MAX(id) FROM history WHERE queueId IS NOT NULL GROUP BY queueId) ORDER BY id DESC"
    )
    fun queuesInHistory(): Flow<List<HistoryQueue>>

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int

    @Query("DELETE FROM history")
    suspend fun clear()

    /** Keeps the newest [keep] rows. */
    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY startedAt DESC, id DESC LIMIT :keep)")
    suspend fun prune(keep: Int)
}
