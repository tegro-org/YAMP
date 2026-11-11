package org.tegro.yamp.data.queue

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A persistent queue (docs/phase-1-queues.md §2). Behaviour in `kind`, display in `origin`. */
@Entity(tableName = "queues")
data class QueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val kind: String,
    val origin: String? = null,
    val seed: String? = null,
    val account: String? = null,
    val cursorEntryId: Long? = null,
    val positionMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: String = "ALL",
    val sortOrder: Int = 0,
    val updatedAt: Long = 0L
)

/**
 * One track in a queue. `position` is the play order, `originalPosition` the insertion order. The display
 * columns let the switcher and the picker render without the provider's track object; the full Yandex DTO
 * stays in `tracks` (keyed by the Yandex id) for playback until Phase 3.
 */
@Entity(
    tableName = "queue_entries",
    foreignKeys = [ForeignKey(entity = QueueEntity::class, parentColumns = ["id"], childColumns = ["queueId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["queueId", "position"]), Index(value = ["ref"])]
)
data class QueueEntryEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0L,
    val queueId: Long,
    val position: Int,
    val originalPosition: Int,
    /** `<provider>:track:<id>` (ARCHITECTURE C1). */
    val ref: String,
    /** The provider's own id (`ref` without its prefix): joins `tracks.id` in Phase 1. */
    @ColumnInfo(index = true) val trackId: String,
    val title: String,
    val artist: String,
    val coverUrl: String? = null,
    val durationMs: Long = 0L
)
