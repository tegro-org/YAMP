package org.tegro.yamp.presentation.history

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.history.HistoryItem
import org.tegro.yamp.data.history.ListeningHistory
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.repository.SettingsRepository
import java.time.ZoneId
import javax.inject.Inject

/** One chip: a queue (its id and name, the kind when the queue still exists), or "All" when [queueId] is null. */
@Immutable
data class HistoryFilter(val queueId: Long?, val name: String?, val kind: QueueKind?)

/** One row: the track as [org.tegro.yamp.presentation.main.components.TrackItem] shows it, and where it played. */
@Immutable
data class HistoryRow(val id: Long, val track: TrackDetailsDto, val queueName: String?)

@Immutable
data class DaySection(val day: DayKey, val rows: List<HistoryRow>)

@Immutable
data class HistoryUiState(
    /** The active queue first, the other queues, the queues that live on only in the history, "All" last. */
    val filters: List<HistoryFilter> = emptyList(),
    /** Null: all queues. */
    val selectedQueueId: Long? = null,
    val days: List<DaySection> = emptyList(),
    /** The setting: off means nothing new is recorded. */
    val enabled: Boolean = true,
    val loading: Boolean = true,
    /** Rows of the last seven days, for the Library row. */
    val weekCount: Int = 0
)

/**
 * Library → History (docs/phase-1b-history.md): the rows of one queue or of all, grouped by day, newest first.
 * Depends on the history manager and the controller (B3). The active queue is selected on [opened] (D50).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val history: ListeningHistory,
    private val playbackController: PlaybackController,
    settings: SettingsRepository
) : ViewModel() {

    private sealed interface Selection {
        /** Whatever queue is active, "All" when none is. */
        data object Active : Selection
        data class Queue(val id: Long?) : Selection
    }

    private val selection = MutableStateFlow<Selection>(Selection.Active)

    private val selectedId: Flow<Long?> = combine(selection, playbackController.activeQueue) { chosen, active ->
        when (chosen) {
            Selection.Active -> active?.queue?.id
            is Selection.Queue -> chosen.id
        }
    }.distinctUntilChanged()

    private val filters: Flow<List<HistoryFilter>> = combine(playbackController.queues, history.queues()) { queues, inHistory ->
        val activeId = queues.firstOrNull { it.isActive }?.queue?.id
        val known = queues
            .sortedWith(compareBy({ it.queue.id != activeId }, { it.queue.kind.rank }))
            .map { HistoryFilter(it.queue.id, it.queue.name, it.queue.kind) }
        val gone = inHistory.filter { h -> known.none { it.queueId == h.queueId } }.map { HistoryFilter(it.queueId, it.queueName, null) }
        known + gone + HistoryFilter(null, null, null)
    }

    /** Null until the first read of the chosen filter. */
    private val items: Flow<List<HistoryItem>?> = selectedId
        .flatMapLatest { id -> history.items(id).map<List<HistoryItem>, List<HistoryItem>?> { it }.onStart { emit(null) } }

    private val weekCount: Flow<Int> = flow { emitAll(history.countSince(System.currentTimeMillis() - WEEK_MS)) }

    val state: StateFlow<HistoryUiState> = combine(filters, selectedId, items, settings.listeningHistory, weekCount) { filters, selected, items, enabled, week ->
        HistoryUiState(
            filters = filters,
            selectedQueueId = selected,
            days = items?.let { sections(it) }.orEmpty(),
            enabled = enabled,
            loading = items == null,
            weekCount = week
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    /** The page opens: the active queue's rows (D50). */
    fun opened() {
        selection.value = Selection.Active
    }

    /** A chip: [queueId], or null for all queues. */
    fun select(queueId: Long?) {
        selection.value = Selection.Queue(queueId)
    }

    fun clear() = history.clear()

    private fun sections(items: List<HistoryItem>): List<DaySection> {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        return items
            .groupBy { DayKey.of(it.row.startedAt, now, zone) }
            .map { (day, rows) -> DaySection(day, rows.map { HistoryRow(it.row.id, it.track, it.row.queueName) }) }
    }

    private val QueueKind.rank: Int
        get() = when (this) {
            QueueKind.STATIC -> 0
            QueueKind.WAVE -> 1
            QueueKind.RADIO -> 2
            QueueKind.MIX -> 3
        }

    private companion object {
        const val WEEK_MS = 7 * 24 * 60 * 60_000L
    }
}
