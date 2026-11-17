package org.tegro.yamp.presentation.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.tegro.yamp.data.account.AccountContext
import org.tegro.yamp.data.player.PlayRequests
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.data.player.PlaybackJournal
import org.tegro.yamp.data.player.PlaylistSource
import org.tegro.yamp.data.provider.ProviderRegistry
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.provider.ArtistPage
import org.tegro.yamp.domain.provider.Catalog
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.fitsQueueOf
import javax.inject.Inject

/** The artist dialog: the page loaded for its counts, or still loading / failed. */
data class ArtistDialogState(
    val target: MediaTarget,
    val action: MediaAction,
    val page: ArtistPage? = null,
    val failed: Boolean = false
)

/**
 * Album / artist actions (tasks/todo.md "Album / artist actions"): loads the tracks through the item's own account
 * (Yandex: the context account; Jellyfin: an account on its server, D78) and hands them to the queue in one
 * operation — play now, play next, add to end, or the "Add to queue…" picker.
 */
@HiltViewModel
class MediaActionsViewModel @Inject constructor(
    private val registry: ProviderRegistry,
    private val accountContext: AccountContext,
    private val playbackController: PlaybackController,
    private val playRequests: PlayRequests,
    private val journal: PlaybackJournal
) : ViewModel() {

    private val _artistDialog = MutableStateFlow<ArtistDialogState?>(null)
    val artistDialog: StateFlow<ArtistDialogState?> = _artistDialog.asStateFlow()

    /** Whether the active queue can take [target]'s tracks (D84): "Play next" and "Add to end" show only then. */
    fun fitsActiveQueue(target: MediaTarget): Boolean {
        val active = playbackController.activeQueue.value ?: return true
        return target.ref.fitsQueueOf(active.queue.account?.let(::AccountId))
    }

    /**
     * An album's action at once; an artist's after the dialog ([chooseArtist] opens it). [onAddTo] opens the queue
     * picker with the loaded tracks; [onDone] reports how many went where (null = nothing to say), [onFailed] that
     * nothing could be loaded.
     */
    fun albumAction(
        target: MediaTarget,
        action: MediaAction,
        onAddTo: (List<Track>, String) -> Unit,
        onDone: (Int) -> Unit,
        onFailed: () -> Unit
    ) {
        viewModelScope.launch {
            val tracks = albumTracks(target.ref)
            if (tracks.isNullOrEmpty()) onFailed() else apply(target, action, tracks, onAddTo, onDone)
        }
    }

    fun chooseArtist(target: MediaTarget, action: MediaAction) {
        _artistDialog.value = ArtistDialogState(target, action)
        viewModelScope.launch {
            val page = catalogOf(target.ref)?.artist(target.ref)
            _artistDialog.value = _artistDialog.value?.takeIf { it.target == target }?.let { state ->
                if (page is Outcome.Ok) state.copy(page = page.value) else state.copy(failed = true)
            }
        }
    }

    fun dismissArtist() {
        _artistDialog.value = null
    }

    /** The dialog's "Add" / "Play": the chosen tracks in release order, then the action, one queue operation. */
    fun artistAction(
        choice: ArtistChoice,
        withExtras: Boolean,
        onAddTo: (List<Track>, String) -> Unit,
        onDone: (Int) -> Unit,
        onFailed: () -> Unit
    ) {
        val state = _artistDialog.value ?: return
        val page = state.page ?: return
        _artistDialog.value = null
        viewModelScope.launch {
            val tracks = when (choice) {
                ArtistChoice.POPULAR -> page.popularTracks
                ArtistChoice.ALBUMS -> loadAlbums(artistReleases(page, withExtras).map { it.ref })
            }
            if (tracks.isEmpty()) onFailed() else apply(state.target, state.action, tracks, onAddTo, onDone)
        }
    }

    private suspend fun apply(
        target: MediaTarget,
        action: MediaAction,
        tracks: List<Track>,
        onAddTo: (List<Track>, String) -> Unit,
        onDone: (Int) -> Unit
    ) {
        journal.record(PlaybackJournal.Kind.TAP, target.title, "${action.name.lowercase()} ${if (target.isArtist) "artist" else "album"}: ${tracks.size} tracks")
        when (action) {
            MediaAction.PLAY_NOW -> {
                playRequests.userStarted()
                val account = accountOf(target.ref)
                if (account?.provider == Provider.JELLYFIN) playbackController.playInAccount(account, tracks, null)
                else playbackController.playFrom(tracks, PlaylistSource.SEARCH, null)
            }
            MediaAction.PLAY_NEXT -> {
                playbackController.playNextTracks(tracks)
                onDone(tracks.size)
            }
            MediaAction.ADD_TO_END -> {
                playbackController.addToEnd(tracks)
                onDone(tracks.size)
            }
            MediaAction.ADD_TO_QUEUE -> onAddTo(tracks, target.title)
        }
    }

    private suspend fun albumTracks(ref: MediaRef): List<Track>? =
        (catalogOf(ref)?.album(ref) as? Outcome.Ok)?.value?.tracks

    /** Every album's tracks, a few requests at a time, in the given order; an album that fails is left out. */
    private suspend fun loadAlbums(refs: List<MediaRef>): List<Track> = coroutineScope {
        val gate = Semaphore(PARALLEL_LOADS)
        refs.map { ref -> async { gate.withPermit { albumTracks(ref).orEmpty() } } }.awaitAll().flatten()
    }

    private fun catalogOf(ref: MediaRef): Catalog? = accountOf(ref)?.let(registry::provider)?.catalog

    /** Yandex: the context account (D56); Jellyfin: the signed-in account on the item's server (D78). */
    private fun accountOf(ref: MediaRef): AccountId? =
        if (ref.providerType == MediaRef.PROVIDER_YANDEX) accountContext.current()
        else accountContext.accountOf(org.tegro.yamp.domain.queue.TrackRef.of(ref.provider, ref.id))

    private companion object {
        const val PARALLEL_LOADS = 4
    }
}
