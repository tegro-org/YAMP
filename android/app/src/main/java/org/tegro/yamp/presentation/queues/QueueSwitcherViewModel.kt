package org.tegro.yamp.presentation.queues

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.queue.QueueSummary
import javax.inject.Inject

/** One row of the switcher: what the canvas `QueueRow` shows. Strings are composed in the UI (D2). */
@Immutable
data class QueueRowState(
    val id: Long,
    val name: String,
    val kind: QueueKind,
    val trackTitle: String?,
    val artist: String?,
    val coverUrl: String?,
    /** Where the current track stands, 0..1; 0 while nothing is current. */
    val progress: Float,
    /** 1-based place of the current track, null while nothing is current. */
    val position: Int?,
    val total: Int,
    val isActive: Boolean
) {
    val editable: Boolean get() = kind == QueueKind.STATIC
}

@Immutable
data class QueueSwitcherUiState(
    val queues: List<QueueRowState>,
    val activeId: Long?,
    /** Null while the switcher is closed. */
    val mode: QueueSwitcherRequest?
)

/**
 * The queue switcher and the "Add to queue…" picker (docs/phase-1-queues.md §5). Depends on the controller
 * only (B3): every edit is a controller call, the rows are its [PlaybackController.queues].
 */
@HiltViewModel
class QueueSwitcherViewModel @Inject constructor(
    private val playbackController: PlaybackController
) : ViewModel() {

    private val mode = MutableStateFlow<QueueSwitcherRequest?>(null)

    val state: StateFlow<QueueSwitcherUiState> = combine(playbackController.queues, mode) { queues, mode ->
        QueueSwitcherUiState(
            queues = queues.map { it.toRow() },
            activeId = queues.firstOrNull { it.isActive }?.queue?.id,
            mode = mode
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueSwitcherUiState(emptyList(), null, null))

    fun open(request: QueueSwitcherRequest) {
        mode.value = request
    }

    fun close() {
        mode.value = null
    }

    fun switchTo(queueId: Long) = playbackController.switchQueue(queueId)

    fun create(name: String) = playbackController.createQueue(name)

    fun rename(queueId: Long, name: String) = playbackController.renameQueue(queueId, name)

    fun delete(queueId: Long) = playbackController.deleteQueue(queueId)

    fun duplicate(queueId: Long, name: String) = playbackController.duplicateQueue(queueId, name)

    fun clear(queueId: Long) = playbackController.clearQueue(queueId)

    /** The picker's row: the tracks of the open [QueueSwitcherRequest.AddTo] go to [queueId]. */
    fun addTo(queueId: Long) {
        val tracks = (mode.value as? QueueSwitcherRequest.AddTo)?.tracks ?: return
        playbackController.addToQueue(queueId, tracks)
    }

    /** The picker's "New queue": created and filled in one go. */
    fun createAndAdd(name: String) {
        val tracks = (mode.value as? QueueSwitcherRequest.AddTo)?.tracks ?: return
        playbackController.createQueueWith(name, tracks)
    }

    private fun QueueSummary.toRow(): QueueRowState {
        val current = current
        val duration = current?.durationMs ?: 0L
        return QueueRowState(
            id = queue.id,
            name = queue.name,
            kind = queue.kind,
            trackTitle = current?.title,
            artist = current?.artist,
            coverUrl = current?.coverUrl,
            progress = if (current != null && duration > 0L) (queue.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            position = current?.let { it.position + 1 },
            total = size,
            isActive = isActive
        )
    }
}
