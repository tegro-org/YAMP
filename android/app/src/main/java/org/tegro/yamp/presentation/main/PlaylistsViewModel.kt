package org.tegro.yamp.presentation.main

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.tegro.yamp.R
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.presentation.common.id
import org.tegro.yamp.data.provider.yandex.PlaylistEntry
import org.tegro.yamp.data.provider.yandex.YandexPlaylists
import org.tegro.yamp.domain.media.Playlist
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.data.player.PlaylistSource
import org.tegro.yamp.data.provider.yandex.repository.UserPlaylistsRepository
import org.tegro.yamp.data.provider.yandex.repository.UserPlaylistsRepository.AddTrackResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The user's playlists: the opened one, and edits (create, rename, delete, add and remove tracks).
 * The repository updates the list and the cache, so the library, the opened playlist and Android Auto
 * follow by themselves; edits only report their outcome to the user.
 */
@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: YandexPlaylists,
    private val playbackController: PlaybackController,
    private val playRequests: org.tegro.yamp.data.player.PlayRequests
) : ViewModel() {

    /** The screens read neutral playlists (phase 3 step 13); edits find the service's own entry again by its ref. */
    val playlists: StateFlow<List<Playlist>> = repository.entries
        .map { list -> list.map { it.playlist } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.currentEntries().map { it.playlist })

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    // region Opened playlist

    private val _opened = MutableStateFlow<OpenedPlaylist?>(null)
    val opened: StateFlow<OpenedPlaylist?> = _opened.asStateFlow()
    private var openJob: Job? = null

    /** The opened playlist as the service has it: its revision says when to reload. */
    private var openedEntry: PlaylistEntry? = null

    init {
        // Edits (here or in Android Auto's list), server refreshes and account changes
        repository.entries.onEach { list ->
            val opened = _opened.value ?: return@onEach
            val shown = openedEntry ?: return@onEach
            val now = list.firstOrNull { it.playlist.ref == shown.playlist.ref }
            when {
                now == null -> close() // deleted, or another account
                now.revision != shown.revision -> openEntry(now, keepShownTracks = true)
                now != shown -> {
                    openedEntry = now
                    _opened.value = opened.copy(playlist = now.playlist)
                }
            }
        }.launchIn(viewModelScope)
    }

    fun open(playlist: Playlist) {
        repository.entry(playlist.ref)?.let { openEntry(it) }
    }

    /** @param keepShownTracks reload in place (after an edit) instead of showing the spinner. */
    private fun openEntry(entry: PlaylistEntry, keepShownTracks: Boolean = false) {
        val playlist = entry.playlist
        openJob?.cancel()
        val shown = _opened.value?.takeIf { keepShownTracks && it.playlist.ref == playlist.ref }?.tracks.orEmpty()
        openedEntry = entry
        _opened.value = OpenedPlaylist(playlist, shown, isLoading = shown.isEmpty())
        openJob = viewModelScope.launch {
            val loaded = try {
                OpenedPlaylist(playlist, repository.tracks(playlist.ref).orEmpty(), isLoading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Playlist load failed", e)
                OpenedPlaylist(playlist, isLoading = false, failed = true, failReason = failureReason(e))
            }
            if (_opened.value?.playlist?.ref == playlist.ref) _opened.value = loaded
        }
    }

    fun close() {
        openJob?.cancel()
        openedEntry = null
        _opened.value = null
    }

    fun play(shuffle: Boolean) {
        val tracks = _opened.value?.tracks.orEmpty().let { if (shuffle) it.shuffled() else it }
        tracks.firstOrNull()?.let {
            playRequests.userStarted()
            playbackController.playTrack(it.id, tracks, PlaylistSource.LIBRARY)
        }
    }

    fun playTrack(trackId: String) {
        val tracks = _opened.value?.tracks ?: return
        playRequests.userStarted()
        playbackController.playTrack(trackId, tracks, PlaylistSource.LIBRARY)
    }

    // endregion

    // region Editing

    /** Creates a playlist; with [thenAdd] the track goes straight into it. */
    fun create(title: String, thenAdd: Track? = null) = edit {
        val created = repository.create(title, thenAdd)
        val shownTitle = created?.title?.ifEmpty { null } ?: title
        if (thenAdd != null) {
            say(R.string.playlist_msg_added, shownTitle)
        } else {
            say(R.string.playlist_msg_created, shownTitle)
        }
    }

    fun rename(playlist: Playlist, title: String) = edit {
        if (!repository.rename(playlist.ref, title)) return@edit
        say(R.string.playlist_msg_renamed, title.trim())
    }

    fun delete(playlist: Playlist) = edit {
        if (!repository.delete(playlist.ref)) return@edit
        say(R.string.playlist_msg_deleted, playlist.title)
    }

    fun addTrack(playlist: Playlist, track: Track) = edit {
        val result = repository.addTrack(playlist.ref, track) ?: return@edit
        say(if (result == AddTrackResult.ALREADY_THERE) R.string.playlist_msg_already else R.string.playlist_msg_added, playlist.title)
    }

    fun removeTrack(playlist: Playlist, trackId: String) = edit {
        if (!repository.removeTrack(playlist.ref, trackId)) return@edit
        say(R.string.playlist_msg_removed, playlist.title)
    }

    /** Saves a new track order; when the playlist changed elsewhere meanwhile, reloads it instead. */
    fun reorder(playlist: Playlist, shownOrder: List<String>, newOrder: List<String>) {
        if (shownOrder == newOrder) return
        if (repository.entry(playlist.ref) == null) return
        viewModelScope.launch {
            try {
                if (!repository.reorder(playlist.ref, shownOrder, newOrder)) return@launch
                say(R.string.playlist_msg_reordered, playlist.title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserPlaylistsRepository.PlaylistChangedException) {
                _messages.tryEmit(context.getString(R.string.playlist_msg_changed_elsewhere))
                repository.refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Reorder failed", e)
                _messages.tryEmit(context.getString(R.string.playlist_msg_failed))
            }
        }
    }

    fun setPublic(playlist: Playlist, public: Boolean) = edit {
        if (!repository.setPublic(playlist.ref, public)) return@edit
        say(if (public) R.string.playlist_msg_public else R.string.playlist_msg_private, playlist.title)
    }

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Playlist edit failed", e)
                _messages.tryEmit(context.getString(R.string.playlist_msg_failed))
            }
        }
    }

    private fun say(resId: Int, title: String) {
        _messages.tryEmit(context.getString(resId, title))
    }

    // endregion

    private companion object {
        const val TAG = "Playlists"
    }
}

/** A playlist opened from the library, with its tracks once loaded. */
data class OpenedPlaylist(
    val playlist: Playlist,
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    /** Short and technical: a tester's screenshot should say what to fix. */
    val failReason: String? = null
)

/** What went wrong with a request, in the fewest words: the HTTP status and the server's own error name, or the kind of failure. */
internal fun failureReason(e: Throwable): String = when (e) {
    is retrofit2.HttpException -> {
        val name = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            ?.let { Regex(""""name"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
        "HTTP ${e.code()}" + name?.let { " $it" }.orEmpty()
    }
    is java.net.SocketTimeoutException -> "TIMEOUT"
    is java.net.UnknownHostException -> "NO_DNS"
    is java.io.IOException -> "NETWORK"
    is kotlinx.serialization.SerializationException -> "BAD_RESPONSE"
    else -> e::class.java.simpleName
}
