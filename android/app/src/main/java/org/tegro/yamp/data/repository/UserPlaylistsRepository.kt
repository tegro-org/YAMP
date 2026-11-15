package org.tegro.yamp.data.repository

import android.util.Log
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.accountId
import org.tegro.yamp.data.cache.PlaylistCacheManager
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.dto.UserPlaylistDto
import org.tegro.yamp.data.network.YandexClients
import org.tegro.yamp.data.network.YandexMusicApi
import org.tegro.yamp.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own playlists (besides "Liked", which [LibraryRepository] handles). The list and each
 * playlist's tracks are cached per account; tracks are keyed by the playlist revision, so an unchanged
 * playlist opens without network.
 */
@Singleton
class UserPlaylistsRepository @Inject constructor(
    private val clients: YandexClients,
    private val authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    private val cacheManager: PlaylistCacheManager
) {
    /** [folder]'s API: an edit is made for the account whose playlist it is (docs/phase-2a-accounts.md §4). */
    private fun apiOf(folder: String): YandexMusicApi = clients.api(folder.takeIf { it != NO_ACCOUNT }?.let(::AccountId))

    private val _playlists = MutableStateFlow<List<UserPlaylistDto>>(emptyList())
    val playlists: StateFlow<List<UserPlaylistDto>> = _playlists.asStateFlow()

    /** Serializes server refreshes and edits, so a refresh never overwrites a list mid-edit. */
    private val mutex = Mutex()
    private var loadedFolder: String? = null

    /** Every account's list, side by side; [playlists] shows the account context's (docs/phase-2a-accounts.md §4). */
    private val playlistsByAccount = java.util.concurrent.ConcurrentHashMap<String, List<UserPlaylistDto>>()

    /** The account context: whose playlists the screens show. */
    private val activeFolder: String
        get() = authRepository.getActiveAccount()?.accountId()?.value ?: NO_ACCOUNT

    /** [folder]'s list: held in memory, else read from its cache once. */
    private suspend fun playlistsOf(folder: String): List<UserPlaylistDto> = playlistsByAccount[folder]
        ?: cacheManager.loadUserPlaylists(folder).let { playlistsByAccount.putIfAbsent(folder, it) ?: it }

    /** Shows the account context's list from memory or its cache; a change of context switches the view only. */
    suspend fun loadCached() {
        val folder = activeFolder
        if (loadedFolder == folder) return
        val list = playlistsOf(folder)
        loadedFolder = folder
        _playlists.value = list
    }

    /** @return true when the list was updated from the server. */
    suspend fun refresh(account: AccountId? = null): Boolean = mutex.withLock {
        val folder = account?.value ?: activeFolder
        try {
            val list = apiOf(folder).getUserPlaylists(libraryRepository.resolveUid(folder)).result.orEmpty()
                .filter { it.kindString.isNotEmpty() && it.kindString != LIKED_KIND }
            // Kept for its own account even when the context moved on meanwhile
            publish(folder, list)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Playlists refresh failed", e)
            false
        }
    }

    /**
     * Tracks in playlist order. When the server fails, the copy saved last, even of an older revision;
     * throws only when nothing was ever saved for this playlist.
     */
    suspend fun tracks(playlist: UserPlaylistDto): List<TrackDetailsDto> {
        val folder = activeFolder
        val kind = playlist.kindString
        cacheManager.loadPlaylistTracks(folder, kind, playlist.revision).takeIf { it.isNotEmpty() }?.let { return it }

        return try {
            val full = apiOf(folder).getUserPlaylist(playlist.ownerUid ?: libraryRepository.resolveUid(folder), kind).result
                ?: return emptyList()
            val tracks = resolveTracks(folder, full)
            if (tracks.isNotEmpty()) cacheManager.savePlaylistTracks(folder, kind, full.revision, tracks)
            tracks
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val last = cacheManager.loadLastPlaylistTracks(folder, kind)
            if (last.isEmpty()) throw e
            Log.w(TAG, "Playlist $kind failed, showing the copy saved last", e)
            last
        }
    }

    /** Track metadata of a playlist fetched with its track list: cached for that revision, else embedded + fetched. */
    private suspend fun resolveTracks(folder: String, full: UserPlaylistDto): List<TrackDetailsDto> {
        cacheManager.loadPlaylistTracks(folder, full.kindString, full.revision).takeIf { it.isNotEmpty() }?.let { return it }
        val ids = full.tracks.mapNotNull { it.trackId }.distinct()
        val embedded = full.tracks.mapNotNull { it.track }.associateBy { it.idString }
        val fetched = libraryRepository.fetchMetadata(ids.filter { it !in embedded }, apiOf(folder)).associateBy { it.idString }
        return ids.mapNotNull { embedded[it] ?: fetched[it] }
    }

    // region Editing. Each call needs the network and throws on failure; the cache changes only on success.

    suspend fun create(title: String): UserPlaylistDto = mutex.withLock {
        val folder = activeFolder
        val created = apiOf(folder).createPlaylist(libraryRepository.resolveUid(folder), title.trim()).result
            ?: error("Empty create response")
        publish(folder, listOf(created.copy(tracks = emptyList())) + playlistsOf(folder))
        created
    }

    suspend fun rename(playlist: UserPlaylistDto, title: String): Unit = mutex.withLock {
        val folder = activeFolder
        val kind = playlist.kindString
        val renamed = apiOf(folder).renamePlaylist(libraryRepository.resolveUid(folder), kind, title.trim()).result
        val current = playlistsOf(folder).firstOrNull { it.kindString == kind } ?: playlist
        val updated = current.copy(
            title = renamed?.title ?: title.trim(),
            revision = renamed?.revision?.takeIf { it > 0 } ?: current.revision
        )
        if (updated.revision != current.revision) {
            // Same tracks under the new revision, so the playlist still opens without network
            cacheManager.loadPlaylistTracks(folder, kind, current.revision).takeIf { it.isNotEmpty() }
                ?.let { cacheManager.savePlaylistTracks(folder, kind, updated.revision, it) }
        }
        replace(folder, updated)
    }

    suspend fun delete(playlist: UserPlaylistDto): Unit = mutex.withLock {
        val folder = activeFolder
        val kind = playlist.kindString
        apiOf(folder).deletePlaylist(libraryRepository.resolveUid(folder), kind)
        publish(folder, playlistsOf(folder).filterNot { it.kindString == kind })
        cacheManager.deletePlaylistTracks(folder, kind)
    }

    /** Adds [track] to the top of [playlist], like the official app. */
    suspend fun addTrack(playlist: UserPlaylistDto, track: TrackDetailsDto): AddTrackResult = mutex.withLock {
        val folder = activeFolder
        val api = apiOf(folder)
        val uid = libraryRepository.resolveUid(folder)
        val kind = playlist.kindString
        // The current server version: exact positions and revision, so an edit from another device is not lost
        val fresh = api.getUserPlaylist(uid, kind).result ?: error("Empty playlist response")
        if (fresh.tracks.any { it.trackId == track.idString }) return@withLock AddTrackResult.ALREADY_THERE
        val albumId = track.albumId
            ?: libraryRepository.fetchMetadata(listOf(track.idString), api).firstOrNull()?.albumId
            ?: error("No album for track ${track.idString}")
        val before = resolveTracks(folder, fresh)
        val changed = api.changePlaylist(uid, kind, PlaylistDiff.insert(0, track.idString, albumId), fresh.revision).result
        applyTrackChange(folder, fresh, changed, listOf(track) + before)
        AddTrackResult.ADDED
    }

    /** Removes every occurrence of [trackId] from [playlist]. */
    suspend fun removeTrack(playlist: UserPlaylistDto, trackId: String): Unit = mutex.withLock {
        val folder = activeFolder
        val api = apiOf(folder)
        val uid = libraryRepository.resolveUid(folder)
        val kind = playlist.kindString
        val fresh = api.getUserPlaylist(uid, kind).result ?: error("Empty playlist response")
        val positions = fresh.tracks.indices.filter { fresh.tracks[it].trackId == trackId }
        val after = resolveTracks(folder, fresh).filterNot { it.idString == trackId }
        val changed = if (positions.isEmpty()) null // already gone on the server
        else api.changePlaylist(uid, kind, PlaylistDiff.delete(positions), fresh.revision).result
        applyTrackChange(folder, fresh, changed, after)
    }

    /**
     * Puts the playlist's tracks in [newOrder]. [shownOrder] is the order the user started from: if the
     * playlist changed elsewhere meanwhile, nothing is saved and [PlaylistChangedException] is thrown.
     */
    suspend fun reorder(playlist: UserPlaylistDto, shownOrder: List<String>, newOrder: List<String>): Unit = mutex.withLock {
        require(newOrder.sorted() == shownOrder.sorted()) { "A new order must keep the same tracks" }
        val folder = activeFolder
        val api = apiOf(folder)
        val uid = libraryRepository.resolveUid(folder)
        val kind = playlist.kindString
        val fresh = api.getUserPlaylist(uid, kind).result ?: error("Empty playlist response")
        val freshIds = fresh.tracks.mapNotNull { it.trackId }
        if (freshIds.distinct() != shownOrder) throw PlaylistChangedException()
        // A track added twice keeps its copies together
        val target = newOrder.flatMap { id -> List(freshIds.count { it == id }) { id } }
        val albums = fresh.tracks.associate { it.trackId to it.albumIdString }
        val missing = albums.filterValues { it == null }.keys.filterNotNull()
        val fetchedAlbums = if (missing.isEmpty()) emptyMap()
        else libraryRepository.fetchMetadata(missing, api).associate { it.idString to it.albumId }
        val diff = PlaylistDiff.reorder(freshIds, target) { id ->
            albums[id] ?: fetchedAlbums[id] ?: error("No album for track $id")
        }
        if (diff.isEmpty()) return@withLock
        val byId = resolveTracks(folder, fresh).associateBy { it.idString }
        val changed = api.changePlaylist(uid, kind, diff, fresh.revision).result
        applyTrackChange(folder, fresh, changed, newOrder.mapNotNull { byId[it] })
    }

    suspend fun setPublic(playlist: UserPlaylistDto, public: Boolean): Unit = mutex.withLock {
        val folder = activeFolder
        val kind = playlist.kindString
        val updated = apiOf(folder).setPlaylistVisibility(libraryRepository.resolveUid(folder), kind, if (public) "public" else "private").result
        val current = playlistsOf(folder).firstOrNull { it.kindString == kind } ?: playlist
        val revision = updated?.revision?.takeIf { it > 0 } ?: current.revision
        if (revision != current.revision) {
            cacheManager.loadPlaylistTracks(folder, kind, current.revision).takeIf { it.isNotEmpty() }
                ?.let { cacheManager.savePlaylistTracks(folder, kind, revision, it) }
        }
        replace(folder, current.copy(visibility = if (public) "public" else "private", revision = revision))
    }

    /** Stores the edited track list under the new revision and updates the playlist entry. */
    private suspend fun applyTrackChange(folder: String, fresh: UserPlaylistDto, changed: UserPlaylistDto?, tracks: List<TrackDetailsDto>) {
        val kind = fresh.kindString
        val revision = changed?.revision?.takeIf { it > 0 } ?: fresh.revision
        val current = playlistsOf(folder).firstOrNull { it.kindString == kind } ?: fresh
        val updated = current.copy(
            revision = revision,
            trackCount = tracks.size,
            cover = changed?.cover ?: current.cover,
            tracks = emptyList()
        )
        if (tracks.isEmpty()) cacheManager.deletePlaylistTracks(folder, kind)
        else cacheManager.savePlaylistTracks(folder, kind, revision, tracks)
        replace(folder, updated)
    }

    private suspend fun replace(folder: String, playlist: UserPlaylistDto) {
        val list = playlistsOf(folder)
        val kind = playlist.kindString
        publish(folder, if (list.any { it.kindString == kind }) list.map { if (it.kindString == kind) playlist else it } else listOf(playlist) + list)
    }

    /** Keeps [folder]'s list and shows it when that account is the context. */
    private suspend fun publish(folder: String, list: List<UserPlaylistDto>) {
        playlistsByAccount[folder] = list
        if (folder == activeFolder) {
            _playlists.value = list
            loadedFolder = folder
        }
        cacheManager.saveUserPlaylists(folder, list)
    }

    // endregion

    /** Logout or "clear cache": forget every account's in-memory lists; they are read again from the cache. */
    fun resetForAccountChange() {
        playlistsByAccount.clear()
        loadedFolder = null
        _playlists.value = emptyList()
    }

    enum class AddTrackResult { ADDED, ALREADY_THERE }

    /** The playlist changed on another device while the user was editing it. */
    class PlaylistChangedException : Exception("Playlist changed elsewhere")

    private companion object {
        const val TAG = "UserPlaylists"
        /** "Liked" is shown separately as the library itself. */
        const val LIKED_KIND = "3"
        /** The cache key when nobody is signed in (as before Phase 2a). */
        const val NO_ACCOUNT = "default"
    }
}
