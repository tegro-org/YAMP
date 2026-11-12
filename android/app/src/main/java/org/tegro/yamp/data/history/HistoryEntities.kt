package org.tegro.yamp.data.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One track that sounded (docs/phase-1b-history.md): a snapshot of what the row shows, so it reads after the
 * track left every list, plus where it played — the queue's id and its name at the time, so a deleted queue's
 * history still reads. [playedMs] grows while the same track sounds again within the merge window.
 */
@Entity(tableName = "history", indices = [Index("startedAt"), Index("queueId")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** `<provider>:track:<id>` (ARCHITECTURE C1). */
    val ref: String,
    /** The provider's own id: joins `tracks.id`, which the orphan rule keeps for this row. */
    val trackId: String,
    val title: String,
    val artist: String,
    val coverUrl: String? = null,
    val durationMs: Long = 0L,
    /** Wall-clock time the track first sounded, for the day groups. */
    val startedAt: Long,
    val playedMs: Long = 0L,
    val queueId: Long? = null,
    val queueName: String? = null
)

/** A queue that appears in the history, with the name it was last recorded under. */
data class HistoryQueue(val queueId: Long, val queueName: String?)
