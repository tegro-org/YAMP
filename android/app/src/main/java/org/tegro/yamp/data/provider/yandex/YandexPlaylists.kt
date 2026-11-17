package org.tegro.yamp.data.provider.yandex

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.tegro.yamp.data.dto.UserPlaylistDto
import org.tegro.yamp.data.provider.yandex.repository.UserPlaylistsRepository.AddTrackResult
import org.tegro.yamp.data.provider.yandex.repository.UserPlaylistsRepository
import org.tegro.yamp.domain.media.Playlist
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.queue.MediaRef
import javax.inject.Inject
import javax.inject.Singleton

/** A playlist as the screens show it, with the service's revision that says when its tracks must be reloaded. */
data class PlaylistEntry(val playlist: Playlist, val revision: Int)

/**
 * The context account's playlists by ref (docs/phase-3-providers.md step 17): the screens hold neutral
 * [Playlist]s and edit them by [MediaRef]; the service's own objects stay here. Edits of a playlist that is gone
 * (deleted, another account) do nothing and return false.
 */
@Singleton
class YandexPlaylists @Inject constructor(private val repository: UserPlaylistsRepository) {

    /** The playlist last opened: it stays reachable while it is not in the list (another account's link). */
    @Volatile private var lastOpened: UserPlaylistDto? = null

    val entries: Flow<List<PlaylistEntry>> = repository.playlists.map { list -> list.mapNotNull { it.toEntry() } }

    fun currentEntries(): List<PlaylistEntry> = repository.playlists.value.mapNotNull { it.toEntry() }

    fun entry(ref: MediaRef): PlaylistEntry? = serviceOf(ref)?.toEntry()

    /** [ref]'s tracks in playlist order; throws on a failed load (the screen says why). Null when it is gone. */
    suspend fun tracks(ref: MediaRef): List<Track>? {
        val service = serviceOf(ref) ?: return null
        lastOpened = service
        return repository.tracks(service).map { it.toTrack() }
    }

    /** The created playlist; with [thenAdd] the track goes straight into it. */
    suspend fun create(title: String, thenAdd: Track? = null): Playlist? {
        val created = repository.create(title)
        if (thenAdd != null) repository.addTrack(created, thenAdd)
        return created.toPlaylist()
    }

    suspend fun rename(ref: MediaRef, title: String): Boolean =
        serviceOf(ref)?.let { repository.rename(it, title); true } ?: false

    suspend fun delete(ref: MediaRef): Boolean = serviceOf(ref)?.let { repository.delete(it); true } ?: false

    /** Null when the playlist is gone. */
    suspend fun addTrack(ref: MediaRef, track: Track): AddTrackResult? = serviceOf(ref)?.let { repository.addTrack(it, track) }

    suspend fun removeTrack(ref: MediaRef, trackId: String): Boolean =
        serviceOf(ref)?.let { repository.removeTrack(it, trackId); true } ?: false

    /** Throws [UserPlaylistsRepository.PlaylistChangedException] when it changed elsewhere meanwhile. */
    suspend fun reorder(ref: MediaRef, shownOrder: List<String>, newOrder: List<String>): Boolean =
        serviceOf(ref)?.let { repository.reorder(it, shownOrder, newOrder); true } ?: false

    suspend fun setPublic(ref: MediaRef, public: Boolean): Boolean =
        serviceOf(ref)?.let { repository.setPublic(it, public); true } ?: false

    suspend fun refresh() {
        repository.refresh()
    }

    private fun serviceOf(ref: MediaRef): UserPlaylistDto? =
        repository.playlists.value.firstOrNull { it.toPlaylist()?.ref == ref }
            ?: lastOpened?.takeIf { it.toPlaylist()?.ref == ref }

    private fun UserPlaylistDto.toEntry(): PlaylistEntry? = toPlaylist()?.let { PlaylistEntry(it, revision) }
}
