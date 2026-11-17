package org.tegro.yamp.domain.queue

/** How a queue behaves (ARCHITECTURE E2). `origin` says where it came from; this says what it does. */
enum class QueueKind {
    /** The user's own list: edited by hand, played in order (or shuffled), looped per [RepeatMode]. */
    STATIC,
    /** My Wave: one per Yandex account, not editable, extended by a [DynamicSource]. */
    WAVE,
    /** Radio from a seed (Phase 2, D35′): as many as the user starts, each extended by its own [DynamicSource]. */
    RADIO,
    /** A built mix (Phase 6). */
    MIX
}

enum class RepeatMode { OFF, ALL, ONE }

/**
 * A persistent queue (E2). The cursor is an entry id, never a track lookup, so a track may be in the queue
 * twice. `positionMs` is the playback position of the cursor entry, saved every 15 s and on pause/switch.
 */
data class PlayQueue(
    val id: Long,
    val name: String,
    val kind: QueueKind,
    /** Where the content came from, for display only ("Album · Dark Side of the Moon"); null for the user's own. */
    val origin: String? = null,
    /** What a wave/radio/mix was started from: a [MediaRef] for a radio, a station id for a wave; null for STATIC. */
    val seed: String? = null,
    /** The provider account a WAVE queue belongs to; null for app-wide queues (D22). */
    val account: String? = null,
    /** A radio retains what it played (D52); meaningful on RADIO only, false for every other kind. */
    val keepHistory: Boolean = false,
    val cursorEntryId: Long? = null,
    val positionMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.ALL,
    val sortOrder: Int = 0,
    val updatedAt: Long = 0L
)

/** One track in a queue. `position` is the play order, `originalPosition` the insertion order (for un-shuffle). */
data class QueueEntry(
    val entryId: Long,
    val queueId: Long,
    val position: Int,
    val originalPosition: Int,
    val ref: TrackRef,
    val title: String,
    val artist: String,
    val coverUrl: String? = null,
    val durationMs: Long = 0L
)

/** What the queue switcher shows per queue. */
data class QueueSummary(
    val queue: PlayQueue,
    val current: QueueEntry?,
    val size: Int,
    val isActive: Boolean
)
