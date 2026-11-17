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
import org.tegro.yamp.domain.repository.AuthRepository
import org.tegro.yamp.domain.repository.SettingsRepository
import org.tegro.yamp.presentation.account.AccountTag
import org.tegro.yamp.presentation.account.accountTags
import org.tegro.yamp.presentation.account.removedAccountTag
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.queue.QueueSummary
import org.tegro.yamp.domain.queue.RadioTarget
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
    val isActive: Boolean,
    /** A radio's Keep (D52); false for every other kind. */
    val keepHistory: Boolean = false,
    /** The account the queue is bound to (`AccountId.value`, D57); null for a queue of no account. */
    val account: String? = null
) {
    val editable: Boolean get() = kind == QueueKind.STATIC
}

/** What a removed account's opened queue shows (canvas `ac-removed-open`, D62). */
@Immutable
data class RemovedQueueView(val next: List<Track>, val position: Int?, val total: Int) {
    @Immutable
    data class Track(val title: String, val artist: String)
}

@Immutable
data class QueueSwitcherUiState(
    val queues: List<QueueRowState>,
    val activeId: Long?,
    /** Null while the switcher is closed. */
    val mode: QueueSwitcherRequest?,
    /** The signed-in accounts; markers, headers and selectors only with more than one (D58). */
    val accounts: List<AccountTag> = emptyList(),
    /** "Group" in Settings → Accounts (D64). */
    val grouped: Boolean = true,
    /** Signed-out accounts' names by key, for their hidden queues' marker (D62). */
    val removedNames: Map<String, String> = emptyMap()
) {
    val multiAccount: Boolean get() = accounts.size > 1

    /** The active queue's account: the default of every selector (D57). */
    val contextAccount: String? get() = queues.firstOrNull { it.isActive }?.account?.takeIf { key -> accounts.any { it.key == key } }
        ?: accounts.firstOrNull()?.key

    fun tagOf(account: String?): AccountTag? = account?.let { key -> accounts.firstOrNull { it.key == key } }

    /** A queue whose account is no longer signed in (D62): kept, shown only in the collapsed group. */
    fun isRemoved(row: QueueRowState): Boolean = row.account != null && tagOf(row.account) == null

    /** The marker of a removed account's queue: the name it had, dimmed (D62). */
    fun removedTagOf(account: String?): AccountTag? = account?.let { removedAccountTag(it, removedNames[it]) }
}

/**
 * The queue switcher and the "Add to queue…" picker (docs/phase-1-queues.md §5). Depends on the controller
 * only (B3): every edit is a controller call, the rows are its [PlaybackController.queues].
 */
@HiltViewModel
class QueueSwitcherViewModel @Inject constructor(
    private val playbackController: PlaybackController,
    authRepository: AuthRepository,
    settings: SettingsRepository
) : ViewModel() {

    private val mode = MutableStateFlow<QueueSwitcherRequest?>(null)

    val state: StateFlow<QueueSwitcherUiState> = combine(
        playbackController.queues, mode, authRepository.allAccounts, settings.groupQueuesByAccount, authRepository.removedAccountNames
    ) { queues, mode, accounts, grouped, removed ->
        QueueSwitcherUiState(
            queues = queues.map { it.toRow() },
            activeId = queues.firstOrNull { it.isActive }?.queue?.id,
            mode = mode,
            accounts = accountTags(accounts),
            grouped = grouped,
            removedNames = removed
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueSwitcherUiState(emptyList(), null, null))

    fun open(request: QueueSwitcherRequest) {
        mode.value = request
    }

    fun close() {
        mode.value = null
    }

    fun switchTo(queueId: Long) = playbackController.switchQueue(queueId)

    fun create(name: String, account: String? = null) = playbackController.createQueue(name, account)

    fun rename(queueId: Long, name: String) = playbackController.renameQueue(queueId, name)

    fun delete(queueId: Long) = playbackController.deleteQueue(queueId)

    fun duplicate(queueId: Long, name: String) = playbackController.duplicateQueue(queueId, name)

    fun clear(queueId: Long) = playbackController.clearQueue(queueId)

    /** The picker's row: the tracks of the open [QueueSwitcherRequest.AddTo] go to [queueId]. */
    fun addTo(queueId: Long) {
        val tracks = (mode.value as? QueueSwitcherRequest.AddTo)?.tracks ?: return
        playbackController.addToQueue(queueId, tracks)
    }

    /**
     * The radio picker's row ([queueId], re-seeded — it keeps its account, D61) or "New radio" (null) on [account]
     * (the sheet's selector; null = the active queue's account), with the sheet's Keep switch.
     */
    fun startRadioIn(queueId: Long?, keepHistory: Boolean, account: String? = null) {
        val request = mode.value as? QueueSwitcherRequest.StartRadio ?: return
        val target = if (queueId == null) RadioTarget.New else RadioTarget.Replace(queueId)
        playbackController.startRadioFrom(request.seed, target, keepHistory, request.track, account.takeIf { queueId == null })
    }

    fun setKeepHistory(queueId: Long, on: Boolean) = playbackController.setKeepHistory(queueId, on)

    /**
     * A removed account's queue looked at (D62): its tracks from the cursor on, as stored — nothing is made active,
     * nothing plays. Null when the queue is gone.
     */
    suspend fun removedQueue(queueId: Long): RemovedQueueView? {
        val queue = playbackController.peekQueue(queueId) ?: return null
        val from = queue.cursorIndex.coerceAtLeast(0)
        return RemovedQueueView(
            next = queue.entries.drop(from).map { RemovedQueueView.Track(it.title, it.artist) },
            position = queue.cursorEntry?.let { queue.cursorIndex + 1 },
            total = queue.size
        )
    }

    /** The picker's "New queue": created and filled in one go. */
    fun createAndAdd(name: String, account: String? = null) {
        val tracks = (mode.value as? QueueSwitcherRequest.AddTo)?.tracks ?: return
        playbackController.createQueueWith(name, tracks, account)
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
            isActive = isActive,
            keepHistory = queue.kind == QueueKind.RADIO && queue.keepHistory,
            account = queue.account
        )
    }
}
