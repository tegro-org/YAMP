package org.tegro.yamp.data.queue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tegro.yamp.di.AppScope
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.di.DispatcherProvider
import org.tegro.yamp.domain.queue.PlayQueue
import org.tegro.yamp.domain.queue.QueueEntry
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.queue.QueuePolicy
import org.tegro.yamp.domain.queue.QueueStore
import org.tegro.yamp.domain.queue.QueueSummary
import org.tegro.yamp.domain.queue.RadioTarget
import org.tegro.yamp.domain.queue.RepeatMode
import org.tegro.yamp.domain.queue.TrackRef
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import org.tegro.yamp.data.provider.yandex.queue.QueueTrackBridgeApi

/**
 * The active queue in memory: its entries in play order and, aligned with them, the tracks the player works with
 * (neutral since phase 3 step 11). [cursorIndex] is -1 while nothing is current.
 */
data class ActiveQueue(
    val queue: PlayQueue,
    val entries: List<QueueEntry>,
    val tracks: List<Track>,
    val cursorIndex: Int
) {
    val cursorEntry: QueueEntry? get() = entries.getOrNull(cursorIndex)
    val cursorTrack: Track? get() = tracks.getOrNull(cursorIndex)
    val size: Int get() = entries.size
    fun indexOfEntry(entryId: Long): Int = entries.indexOfFirst { it.entryId == entryId }

    /** True when [shown] is this queue's list as the screens got it (the same tracks in the same order). */
    fun isShownAs(shown: List<Track>): Boolean =
        entries.size == shown.size && entries.indices.all { entries[it].ref.id == shown[it].ref.id }
}

/**
 * Owns the list of queues and the active one (docs/phase-1-queues.md §3). Every operation is written to the
 * store before it returns (E3): nothing is held only in memory. The controller reads [active] and asks for
 * changes; it never edits a queue itself. One mutation at a time, in call order.
 */
@Singleton
class QueueManager @Inject constructor(
    private val store: QueueStore,
    private val tracks: QueueTrackBridgeApi,
    @AppScope private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider
) {
    private val mutex = Mutex()

    private val _active = MutableStateFlow<ActiveQueue?>(null)
    /** The active queue, or null before [load] and while there is none. */
    val active: StateFlow<ActiveQueue?> = _active.asStateFlow()

    /** Every queue for the switcher, with sizes, cursor entries and the active mark. */
    val queues: StateFlow<List<QueueSummary>> = store.summaries(store.activeQueueId())
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _loaded = MutableStateFlow(false)
    /** True once [load] has read the store (restore is read-only, DPI rule 2). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Reads the active queue from the store. Writes nothing. */
    suspend fun load() = mutation {
        val id = store.activeQueueIdOnce()
        _active.value = id?.let { readActive(it) }
        _loaded.value = true
    }

    // ---- queues

    /**
     * A new queue, bound to [account] for good (D57, D61); without one, to the active queue's account — the
     * account the user is in.
     */
    suspend fun create(name: String, kind: QueueKind = QueueKind.STATIC, account: String? = null, seed: String? = null): PlayQueue = mutation {
        val order = (store.queues().maxOfOrNull { it.sortOrder } ?: -1) + 1
        store.create(PlayQueue(id = 0L, name = name, kind = kind, account = accountForNew(account), seed = seed, sortOrder = order))
    }

    private fun accountForNew(account: String?): String? = account ?: _active.value?.queue?.account

    suspend fun rename(queueId: Long, name: String) = mutation {
        store.queue(queueId)?.let { store.update(it.copy(name = name)) }
        refreshIfActive(queueId)
    }

    /** Removes the queue; if it was active, the next one in the list (or none) becomes active, paused. */
    suspend fun delete(queueId: Long): Unit = mutation {
        val before = store.queues()
        store.delete(queueId)
        if (_active.value?.queue?.id == queueId) {
            val remaining = before.filter { it.id != queueId }
            val index = before.indexOfFirst { it.id == queueId }
            val next = remaining.getOrNull(index) ?: remaining.lastOrNull()
            activate(next?.id)
        }
    }

    suspend fun duplicate(queueId: Long, name: String): PlayQueue? = mutation {
        val source = store.queue(queueId) ?: return@mutation null
        val order = (store.queues().maxOfOrNull { it.sortOrder } ?: -1) + 1
        // The copy stays with the account of the queue it was made from (D57)
        val copy = store.create(source.copy(id = 0L, name = name, kind = QueueKind.STATIC, seed = null, cursorEntryId = null, positionMs = 0L, sortOrder = order))
        val entries = store.entries(queueId)
        val written = store.replaceEntries(copy.id, entries)
        val cursor = source.cursorEntryId?.let { id -> entries.indexOfFirst { it.entryId == id } }?.takeIf { it >= 0 }?.let { written[it].entryId }
        store.saveCursor(copy.id, cursor, 0L)
        copy.copy(cursorEntryId = cursor)
    }

    suspend fun clear(queueId: Long) = mutation {
        store.replaceEntries(queueId, emptyList())
        store.saveCursor(queueId, null, 0L)
        refreshIfActive(queueId)
    }

    suspend fun reorderQueues(idsInOrder: List<Long>) = mutation {
        val byId = store.queues().associateBy { it.id }
        idsInOrder.forEachIndexed { i, id -> byId[id]?.let { if (it.sortOrder != i) store.update(it.copy(sortOrder = i)) } }
    }

    /** Saves the active queue's position, then makes [queueId] active; the caller shows it paused at its cursor. */
    suspend fun switchTo(queueId: Long, currentPositionMs: Long?) = mutation {
        if (currentPositionMs != null) _active.value?.let { store.saveCursor(it.queue.id, it.cursorEntry?.entryId, currentPositionMs) }
        activate(queueId)
    }

    /** Saves the active queue's position and makes no queue active (the last account signed out, D62). */
    suspend fun leave(currentPositionMs: Long?) = mutation {
        if (currentPositionMs != null) _active.value?.let { store.saveCursor(it.queue.id, it.cursorEntry?.entryId, currentPositionMs) }
        activate(null)
    }

    /** Any queue as stored, looked at without making it active (a removed account's queue, D62). */
    suspend fun peek(queueId: Long): ActiveQueue? = mutation { readActive(queueId) }

    // ---- content

    /**
     * Replaces the content of the active queue (E5) — the only operation that replaces — and puts the cursor on
     * [startRef] (its first occurrence) or on the first entry. Creates the queue when none is active.
     */
    suspend fun replaceActive(tracks: List<Track>, origin: String?, startRef: TrackRef?, nameIfNew: String, accountIfNew: String? = null): ActiveQueue = mutation {
        val queueId = _active.value?.queue?.id ?: run {
            val order = (store.queues().maxOfOrNull { it.sortOrder } ?: -1) + 1
            val q = store.create(PlayQueue(id = 0L, name = nameIfNew, kind = QueueKind.STATIC, account = accountIfNew, sortOrder = order))
            store.setActiveQueueId(q.id)
            q.id
        }
        this.tracks.remember(tracks)
        val entries = store.replaceEntries(queueId, tracks.map { entryOf(it) })
        val cursor = startRef?.let { ref -> entries.firstOrNull { it.ref == ref } } ?: entries.firstOrNull()
        store.queue(queueId)?.let { store.update(it.copy(origin = origin, shuffle = false)) }
        store.saveCursor(queueId, cursor?.entryId, 0L)
        checkNotNull(readActive(queueId)) { "queue $queueId vanished" }.also { _active.value = it }
    }

    /** Appends to any queue ("Add to end of queue"); in shuffle mode the newcomers land after the cursor. */
    suspend fun appendTo(queueId: Long, tracks: List<Track>) = mutation {
        this.tracks.remember(tracks)
        val queue = store.queue(queueId) ?: return@mutation
        val existing = store.entries(queueId)
        val active = _active.value?.takeIf { it.queue.id == queueId }
        val at = if (queue.shuffle && active != null) QueuePolicy.randomInsertPosition(active.cursorIndex, existing.size, Random.Default) else existing.size
        store.insertEntries(queueId, at, tracks.map { entryOf(it) })
        refreshIfActive(queueId)
    }

    /** "Play next": right after the cursor of the active queue. */
    suspend fun insertAfterCursor(tracks: List<Track>) = mutation {
        val active = _active.value ?: return@mutation
        this.tracks.remember(tracks)
        store.insertEntries(active.queue.id, active.cursorIndex + 1, tracks.map { entryOf(it) })
        refreshActive()
    }

    suspend fun remove(entryId: Long) = mutation {
        val active = _active.value ?: return@mutation
        if (active.cursorEntry?.entryId == entryId) return@mutation // the playing track leaves through playNext
        store.removeEntry(entryId)
        store.reorder(active.queue.id, store.entries(active.queue.id).map { it.entryId })
        refreshActive()
    }

    /** Moves an entry to the place of [targetEntryId] (drag in the queue sheet). */
    suspend fun move(entryId: Long, targetEntryId: Long) = mutation {
        val active = _active.value ?: return@mutation
        val ids = active.entries.map { it.entryId }.toMutableList()
        val from = ids.indexOf(entryId)
        val to = ids.indexOf(targetEntryId)
        if (from < 0 || to < 0 || from == to) return@mutation
        ids.add(to, ids.removeAt(from))
        store.reorder(active.queue.id, ids)
        refreshActive()
    }

    // ---- cursor and modes

    suspend fun moveCursor(entryId: Long, positionMs: Long = 0L) = mutation {
        val active = _active.value ?: return@mutation
        val index = active.indexOfEntry(entryId)
        if (index < 0) return@mutation
        store.saveCursor(active.queue.id, entryId, positionMs)
        _active.value = active.copy(queue = active.queue.copy(cursorEntryId = entryId, positionMs = positionMs), cursorIndex = index)
    }

    /** The periodic and on-pause save of where the cursor track is. */
    suspend fun savePosition(positionMs: Long) = mutation {
        val active = _active.value ?: return@mutation
        store.saveCursor(active.queue.id, active.cursorEntry?.entryId, positionMs)
        _active.value = active.copy(queue = active.queue.copy(positionMs = positionMs))
    }

    suspend fun setShuffle(on: Boolean) = mutation {
        val active = _active.value ?: return@mutation
        val order = if (on) QueuePolicy.shuffled(active.entries, active.cursorEntry?.entryId) else QueuePolicy.unshuffled(active.entries)
        store.reorder(active.queue.id, order)
        store.update(active.queue.copy(shuffle = on))
        refreshActive()
    }

    suspend fun setRepeat(mode: RepeatMode) = mutation {
        val active = _active.value ?: return@mutation
        store.update(active.queue.copy(repeat = mode))
        _active.value = active.copy(queue = active.queue.copy(repeat = mode))
    }

    /**
     * An account's default queue (D83): created empty, named [name], when the account has no queue at all — the
     * first sign-in of a Jellyfin account. When it has some (a re-sign-in of the same server + user, D62), nothing
     * is created and its first queue is returned.
     */
    suspend fun defaultQueue(account: String, name: String): PlayQueue = mutation {
        store.queues().filter { it.account == account }.minByOrNull { it.sortOrder }
            ?: store.create(
                PlayQueue(id = 0L, name = name, kind = QueueKind.STATIC, account = account,
                    sortOrder = (store.queues().maxOfOrNull { it.sortOrder } ?: -1) + 1)
            )
    }

    // ---- dynamic queues (My Wave)

    /** The WAVE queue of an account, created empty on first use. */
    suspend fun waveQueue(account: String, name: String): PlayQueue = mutation {
        store.queues().firstOrNull { it.kind == QueueKind.WAVE && it.account == account }
            ?: store.create(PlayQueue(id = 0L, name = name, kind = QueueKind.WAVE, account = account, sortOrder = -1))
    }

    /**
     * Appends a fetched batch to a dynamic queue; what is already there is not added twice. A radio without
     * Keep drops its played head at the same time (D52), in the same mutation.
     */
    suspend fun appendFetched(queueId: Long, tracks: List<Track>) = mutation {
        val queue = store.queue(queueId) ?: return@mutation
        val existing = store.entries(queueId)
        val have = existing.map { it.ref.id }.toSet()
        val fresh = tracks.filter { it.ref.id !in have }
        if (fresh.isEmpty()) return@mutation
        this.tracks.remember(fresh)
        store.insertEntries(queueId, existing.size, fresh.map { entryOf(it) })
        if (queue.kind == QueueKind.RADIO) {
            val cursor = queue.cursorEntryId?.let { id -> existing.indexOfFirst { it.entryId == id } } ?: -1
            val trimmed = QueuePolicy.radioTrim(existing, cursor, queue.keepHistory)
            if (trimmed.isNotEmpty()) {
                trimmed.forEach { store.removeEntry(it) }
                store.reorder(queueId, store.entries(queueId).map { it.entryId })
            }
        }
        refreshIfActive(queueId)
    }

    // ---- radios (docs/phase-2-radio.md §3)

    /**
     * Starts a radio (D51): a new RADIO queue, or an existing radio re-seeded in place — its tracks dropped, the
     * name following the seed. [first] is what plays before the first batch (the seed track, or nothing). The
     * cursor is on the first entry; the caller switches to the queue and plays it. A new radio is bound to [account]
     * (else the active queue's); a re-seeded one keeps its own (D61).
     */
    suspend fun startRadio(target: RadioTarget, seed: String, name: String, keepHistory: Boolean, first: List<Track>, account: String? = null): PlayQueue = mutation {
        val queue = when (target) {
            is RadioTarget.New -> {
                val order = (store.queues().maxOfOrNull { it.sortOrder } ?: -1) + 1
                store.create(PlayQueue(id = 0L, name = name, kind = QueueKind.RADIO, account = accountForNew(account), seed = seed, keepHistory = keepHistory, sortOrder = order))
            }
            is RadioTarget.Replace -> {
                val existing = store.queue(target.queueId)
                require(existing != null && existing.kind == QueueKind.RADIO) { "not a radio: ${target.queueId}" }
                existing.copy(name = name, seed = seed, keepHistory = keepHistory, origin = null, shuffle = false)
            }
        }
        this.tracks.remember(first)
        val written = store.replaceEntries(queue.id, first.map { entryOf(it) })
        val cursor = written.firstOrNull()?.entryId
        store.update(queue)
        store.saveCursor(queue.id, cursor, 0L)
        refreshIfActive(queue.id)
        queue.copy(cursorEntryId = cursor, positionMs = 0L)
    }

    /** Keep on or off (D52): the flag only — the trim is tied to refills. */
    suspend fun setKeepHistory(queueId: Long, on: Boolean) = mutation {
        val queue = store.queue(queueId)?.takeIf { it.kind == QueueKind.RADIO } ?: return@mutation
        if (queue.keepHistory == on) return@mutation
        store.update(queue.copy(keepHistory = on))
        refreshIfActive(queueId)
    }

    /** A dynamic queue switches its seed (wave by track, return to My Wave): new content after the cursor. */
    suspend fun restartDynamic(queueId: Long, seed: String?, keepUpToCursor: Boolean, tracks: List<Track>) = mutation {
        val queue = store.queue(queueId) ?: return@mutation
        val active = _active.value?.takeIf { it.queue.id == queueId }
        val kept = if (keepUpToCursor && active != null) active.entries.take(active.cursorIndex + 1) else emptyList()
        this.tracks.remember(tracks)
        val written = store.replaceEntries(queueId, kept + tracks.map { entryOf(it) })
        val cursor = if (kept.isNotEmpty()) written[kept.size - 1].entryId else written.firstOrNull()?.entryId
        store.update(queue.copy(seed = seed))
        store.saveCursor(queueId, cursor, if (kept.isNotEmpty()) queue.positionMs else 0L)
        refreshIfActive(queueId)
    }

    // ---- internals

    private suspend fun <T> mutation(block: suspend () -> T): T = mutex.withLock { withContext(dispatchers.io) { block() } }

    private suspend fun activate(queueId: Long?) {
        store.setActiveQueueId(queueId)
        _active.value = queueId?.let { readActive(it) }
    }

    private suspend fun refreshActive() {
        _active.value?.let { _active.value = readActive(it.queue.id) }
    }

    private suspend fun refreshIfActive(queueId: Long) {
        if (_active.value?.queue?.id == queueId) refreshActive()
    }

    private suspend fun readActive(queueId: Long): ActiveQueue? {
        val queue = store.queue(queueId) ?: return null
        val entries = store.entries(queueId)
        val shown = tracks.recall(entries)
        val cursor = queue.cursorEntryId?.let { id -> entries.indexOfFirst { it.entryId == id } } ?: -1
        return ActiveQueue(queue, entries, shown, cursor)
    }

    private fun entryOf(track: Track) = QueueEntry(
        entryId = 0L, queueId = 0L, position = 0, originalPosition = 0,
        // The track's own ref: a Jellyfin entry stays Jellyfin, so it resolves through its account (step 23)
        ref = track.ref,
        title = track.title,
        artist = track.artists.joinToString(", ") { it.name },
        coverUrl = track.cover?.key,
        durationMs = track.durationMs ?: 0L
    )
}

private suspend fun QueueStore.activeQueueIdOnce(): Long? = activeQueueId().first()
