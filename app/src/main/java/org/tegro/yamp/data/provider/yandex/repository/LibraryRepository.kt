package org.tegro.yamp.data.provider.yandex.repository

import android.util.Log
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.accountId
import org.tegro.yamp.data.provider.yandex.cache.PlaylistCacheManager
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.network.YandexClients
import org.tegro.yamp.data.provider.yandex.network.YandexMusicApi
import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import org.tegro.yamp.data.repository.PendingReaction
import org.tegro.yamp.data.repository.PendingReactions

/**
 * The user's library (liked tracks) and the catalog charts, shared by all screens.
 *
 * Every load is single-flight: concurrent callers join the request already running instead of starting
 * another one, so app start produces one sync instead of the 2-4 duplicated loads it used to.
 * Likes are synced incrementally: one light request for the liked IDs and the revision; metadata is
 * fetched only for tracks that are not cached yet.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val clients: YandexClients,
    private val authRepository: AuthRepository,
    private val cacheManager: PlaylistCacheManager,
    /** Re-keys a provisional account's data once its uid is known (Phase 2a); tests pass none. */
    private val accountKeys: org.tegro.yamp.data.cache.CacheKeyMigration = org.tegro.yamp.data.cache.CacheKeyMigration.None
) {
    /** [folder]'s API: every call is made for the account whose data it changes (docs/phase-2a-accounts.md §4). */
    private fun apiOf(folder: String): YandexMusicApi = clients.api(folder.takeIf { it != NO_ACCOUNT }?.let(::AccountId))

    /** The account context's API, for what belongs to nobody in particular (charts). */
    private val api: YandexMusicApi get() = clients.current()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Library job failed", e) }
    )

    private val _likedTracks = MutableStateFlow<List<TrackDetailsDto>>(emptyList())
    val likedTracks: StateFlow<List<TrackDetailsDto>> = _likedTracks.asStateFlow()

    private val _likedIds = MutableStateFlow<Set<String>>(emptySet())
    val likedIds: StateFlow<Set<String>> = _likedIds.asStateFlow()

    private val _isSyncingLikes = MutableStateFlow(false)
    val isSyncingLikes: StateFlow<Boolean> = _isSyncingLikes.asStateFlow()
    private val _likesSyncFailed = MutableStateFlow(false)
    /** The last sync of the likes failed (no network, server error); cleared by the next successful one. */
    val likesSyncFailed: StateFlow<Boolean> = _likesSyncFailed.asStateFlow()

    private val _weeklyHits = MutableStateFlow<List<TrackDetailsDto>>(emptyList())
    val weeklyHits: StateFlow<List<TrackDetailsDto>> = _weeklyHits.asStateFlow()

    private val _trendTracks = MutableStateFlow<List<TrackDetailsDto>>(emptyList())
    val trendTracks: StateFlow<List<TrackDetailsDto>> = _trendTracks.asStateFlow()

    private val _popularTracks = MutableStateFlow<List<TrackDetailsDto>>(emptyList())
    val popularTracks: StateFlow<List<TrackDetailsDto>> = _popularTracks.asStateFlow()

    /**
     * Every account's likes, kept side by side: the flows above show the account context's, and a change of
     * context only shows another entry — nothing is dropped or reloaded (docs/phase-2a-accounts.md §4).
     */
    private class Likes(val tracks: List<TrackDetailsDto>, val ids: Set<String>)
    private val likesByAccount = java.util.concurrent.ConcurrentHashMap<String, Likes>()

    private val likesMutex = Mutex()
    private val pendingMutex = Mutex()
    /** One sync per account at a time; another account's sync runs beside it. */
    private val likesSyncs = mutableMapOf<String, Deferred<Boolean>>()
    private var chartsLoad: Deferred<Unit>? = null
    private var chartsLoadedThisSession = false
    private var loadedFolder: String? = null
    private var hitsPage = 1
    private var trendsPage = 1
    private val loadingMoreHits = java.util.concurrent.atomic.AtomicBoolean(false)
    private val loadingMoreTrends = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The account context: whose likes the screens show. */
    private val activeFolder: String
        get() = authRepository.getActiveAccount()?.accountId()?.value ?: NO_ACCOUNT

    private fun folderOf(account: AccountId?): String = account?.value ?: activeFolder

    /** [folder]'s likes: held in memory, else read from its cache once. */
    private suspend fun likesOf(folder: String): Likes = likesByAccount[folder]
        ?: cacheManager.loadLikedTracks(folder).let { cached -> Likes(cached, cached.map { it.idString }.toSet()) }
            .let { loaded -> likesByAccount.putIfAbsent(folder, loaded) ?: loaded }

    /** [account]'s liked tracks from memory or its cache, whichever account the context is (step 10). */
    suspend fun likedTracksOf(account: AccountId): List<TrackDetailsDto> = likesOf(account.value).tracks

    /** The account's liked albums, newest first (gap (e): Library and the car's Albums tab). Throws on a network error. */
    suspend fun likedAlbumsOf(account: AccountId): List<org.tegro.yamp.data.dto.AlbumDto> {
        val folder = folderOf(account)
        return apiOf(folder).getLikedAlbums(resolveUid(folder)).result.orEmpty().mapNotNull { it.album }
            .also { likedAlbumsByAccount[folder] = it }
    }

    /** What [likedAlbumsOf] last answered for the account, without a request. */
    fun knownLikedAlbums(account: AccountId): List<org.tegro.yamp.data.dto.AlbumDto>? = likedAlbumsByAccount[folderOf(account)]

    /** The account's liked artists. Throws on a network error. */
    suspend fun likedArtistsOf(account: AccountId): List<org.tegro.yamp.data.dto.ArtistDto> {
        val folder = folderOf(account)
        return apiOf(folder).getLikedArtists(resolveUid(folder)).result.orEmpty()
    }

    private val likedAlbumsByAccount = java.util.concurrent.ConcurrentHashMap<String, List<org.tegro.yamp.data.dto.AlbumDto>>()

    /** Keeps [folder]'s likes and shows them when it is the account context. */
    private fun putLikes(folder: String, tracks: List<TrackDetailsDto>, ids: Set<String> = tracks.map { it.idString }.toSet()) {
        likesByAccount[folder] = Likes(tracks, ids)
        if (folder == loadedFolder) {
            _likedTracks.value = tracks
            _likedIds.value = ids
        }
    }

    /**
     * Shows the account context's likes, from memory or its cache (no network). Safe to call repeatedly; a
     * change of context switches the view only.
     */
    suspend fun loadCached() {
        val folder = activeFolder
        if (loadedFolder == folder) return
        val likes = likesOf(folder)
        loadedFolder = folder
        _likedTracks.value = likes.tracks
        _likedIds.value = likes.ids
        if (_weeklyHits.value.isEmpty()) _weeklyHits.value = cacheManager.loadWeeklyHits()
        if (_trendTracks.value.isEmpty()) _trendTracks.value = cacheManager.loadTrendTracks()
        if (_popularTracks.value.isEmpty()) _popularTracks.value = cacheManager.loadPopularTracks()
    }

    /**
     * Brings the likes up to date. Joins a sync already in progress.
     * @return true when the liked list changed.
     */
    suspend fun syncLikes(account: AccountId? = null): Boolean {
        val folder = folderOf(account)
        val running = likesMutex.withLock {
            likesSyncs[folder]?.takeIf { it.isActive } ?: scope.async { doSyncLikes(folder) }.also { likesSyncs[folder] = it }
        }
        return running.await()
    }

    private suspend fun doSyncLikes(folder: String): Boolean {
        if (folder == NO_ACCOUNT || tokenOf(folder).isNullOrEmpty()) return false
        val api = apiOf(folder)
        val showsProgress = folder == activeFolder
        if (showsProgress) _isSyncingLikes.value = true
        try {
            loadCached()
            // First what was done without a connection, or the sync would take it back
            flushPendingReactions(AccountId(folder))
            val uid = resolveUid(folder)
            val response = try {
                api.getLikedTracks(uid, richTracks = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (uid != "me") api.getLikedTracks("me", richTracks = false) else throw e
            }
            val result = response.result ?: return false
            if (showsProgress) _likesSyncFailed.value = false
            val held = likesOf(folder)
            val revision = result.library?.revision ?: result.revision
            var ids = result.allTracks.map { it.idString }.filter { it.isNotEmpty() }.distinct()

            // Tracks saved before 1.12 lack loudness and the explicit mark: fetched again once
            val metaCurrent = cacheManager.likesMetaIsCurrent(folder)
            // Likes unchanged since the last sync and everything is cached: nothing else to do
            if (metaCurrent && revision != null && revision == cacheManager.loadLikesRevision(folder) &&
                ids.toSet() == held.ids && held.tracks.size == ids.size
            ) {
                Log.d(TAG, "Likes unchanged (revision $revision), ${ids.size} tracks")
                return false
            }

            if (ids.isEmpty()) {
                // Some accounts expose likes only through the "Liked" playlist (kind 3)
                ids = runCatching { extractTrackIds(api.getPlaylistRaw(uid, "3").result) }.getOrDefault(emptyList())
            }

            // A failed refresh keeps what was cached: never fewer details than before
            val previousById = held.tracks.associateBy { it.idString }
            val cachedById = if (metaCurrent) previousById else emptyMap()
            val embedded = result.allTracks.mapNotNull { it.track }.associateBy { it.idString }
            val missing = ids.filter { it !in cachedById && it !in embedded }
            val fetched = fetchMetadata(missing, api).associateBy { it.idString }
            Log.d(TAG, "Likes changed (revision $revision): ${ids.size} tracks, metadata fetched for ${missing.size}")

            // Keep the server's order (most recent like first)
            val tracks = ids.map { id ->
                cachedById[id] ?: embedded[id] ?: fetched[id] ?: previousById[id]
                    ?: TrackDetailsDto(id = kotlinx.serialization.json.JsonPrimitive(id))
            }
            // Kept for its own account even when the context moved on meanwhile
            putLikes(folder, tracks, ids.toSet())
            cacheManager.saveLikedTracks(folder, tracks)
            revision?.let { cacheManager.saveLikesRevision(folder, it) }
            if (!metaCurrent && fetched.size >= missing.size / 2) cacheManager.markLikesMetaCurrent(folder)
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Likes sync failed", e)
            if (showsProgress) _likesSyncFailed.value = true
            return false
        } finally {
            if (showsProgress) _isSyncingLikes.value = false
        }
    }

    private fun accountOf(folder: String): UserAccount? =
        authRepository.getAllAccounts().firstOrNull { it.accountId().value == folder }
            ?: authRepository.getActiveAccount()?.takeIf { it.accountId().value == folder }

    private fun tokenOf(folder: String): String? = accountOf(folder)?.let { authRepository.token(it.accountId()) }

    internal suspend fun fetchMetadata(ids: List<String>, api: YandexMusicApi = this.api): List<TrackDetailsDto> {
        val out = mutableListOf<TrackDetailsDto>()
        for (chunk in ids.chunked(METADATA_CHUNK)) {
            val meta = runCatching { api.getTracksMetadata(chunk).result.orEmpty() }.getOrDefault(emptyList())
                .ifEmpty { runCatching { api.getTracksMetadataQuery(chunk.joinToString(",")).result.orEmpty() }.getOrDefault(emptyList()) }
            out += meta
        }
        return out
    }

    /**
     * The account UID needed by the likes endpoints. Taken from the saved account; asks the server only
     * once for an account that was saved before its UID was known.
     */
    internal suspend fun resolveUid(folder: String = activeFolder): String {
        val account = accountOf(folder)
        account?.takeIf { it.hasRealUid }?.let { return it.uid }
        val api = apiOf(folder)
        val status = runCatching { api.getAccountStatus().result }.getOrNull() ?: return "me"
        val uid = status.uidString.takeIf { it.isNotEmpty() } ?: return "me"
        val token = tokenOf(folder) ?: authRepository.getToken().orEmpty()
        if (token.isNotEmpty()) {
            val avatar = status.avatarUrl ?: runCatching { api.getYandexLoginInfo().avatarUrl }.getOrNull()
            authRepository.saveOrUpdateAccount(
                UserAccount(
                    uid = uid,
                    login = status.account?.login.orEmpty(),
                    displayName = status.displayUserName,
                    token = token,
                    avatarUrl = avatar ?: account?.avatarUrl,
                    hasPlus = if (status.plusKnown) status.hasPlus else account?.hasPlus == true,
                    plusCheckedAt = if (status.plusKnown) System.currentTimeMillis() else account?.plusCheckedAt ?: 0L
                )
            )
            if (account != null && account.uid != uid) accountKeys.adoptRealUid(account, uid)
        }
        return uid
    }

    /**
     * Like/unlike with an optimistic update; reverted if the server call fails. [account] = whose like it is
     * (the playing queue's, from step 5); `null` = the account context.
     */
    fun setLiked(track: TrackDetailsDto, liked: Boolean, account: AccountId? = null, onDone: (Boolean) -> Unit = {}) {
        val id = track.idString
        val folder = folderOf(account)
        val held = likesByAccount[folder] ?: Likes(_likedTracks.value, _likedIds.value).takeIf { folder == loadedFolder }
        val previousTracks = held?.tracks.orEmpty()
        val previousIds = held?.ids.orEmpty()
        val tracks = if (liked) listOf(track) + previousTracks.filter { it.idString != id } else previousTracks.filter { it.idString != id }
        if (held != null) putLikes(folder, tracks, if (liked) previousIds + id else previousIds - id)
        val api = apiOf(folder)
        launchReaction setLiked@{
            // Likes not loaded yet for that account: the next sync brings this one with the rest
            if (held != null) cacheManager.saveLikedTracks(folder, tracks)
            val outcome = runCatching {
                val uid = resolveUid(folder)
                if (liked) api.likeTrack(uid, id) else api.unlikeTrack(uid, id)
            }.onFailure { Log.e(TAG, "Like change failed for $id", it) }
            // No connection: the like stands and is sent later. The server refusing it is another matter
            if (outcome.exceptionOrNull() is java.io.IOException) {
                addPendingReaction(folder, PendingReaction(id, if (liked) PendingReaction.Kind.LIKE else PendingReaction.Kind.UNLIKE))
                onDone(true)
                return@setLiked
            }
            val ok = outcome.isSuccess
            if (!ok && held != null) {
                putLikes(folder, previousTracks, previousIds)
                cacheManager.saveLikedTracks(folder, previousTracks)
            }
            onDone(ok)
        }
    }

    /**
     * Likes, unlikes, dislikes and their Undo go out one at a time in the order they were made, so an Undo
     * sent right after a dislike reaches the server after it.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val reactionCalls = Dispatchers.IO.limitedParallelism(1)

    /** Taken first thing on [reactionCalls], so it is taken in the order the calls were made, and held throughout. */
    private val reactionLock = Mutex()

    private fun launchReaction(block: suspend () -> Unit) {
        scope.launch(reactionCalls) { reactionLock.withLock { block() } }
    }

    /**
     * Undo of [dislike]: the track leaves the account's dislikes and, if [wasLiked], becomes liked again (locally
     * at once, on the server in the background). Without a connection the like is queued like any other.
     */
    fun undoDislike(track: TrackDetailsDto, wasLiked: Boolean, account: AccountId? = null) {
        val id = track.idString
        val folder = folderOf(account)
        val api = apiOf(folder)
        launchReaction {
            runCatching { api.removeDislikeTrack(resolveUid(folder), id) }
                .onFailure { Log.e(TAG, "Undo of a dislike failed for $id", it) }
        }
        if (wasLiked) setLiked(track, true, account)
    }

    /** Dislikes [track] (an existing like goes first); returns whether it was liked, for [undoDislike]. */
    fun dislike(track: TrackDetailsDto, account: AccountId? = null): Boolean {
        val id = track.idString
        val folder = folderOf(account)
        val held = likesByAccount[folder] ?: Likes(_likedTracks.value, _likedIds.value).takeIf { folder == loadedFolder }
        val wasLiked = held?.ids?.contains(id) == true
        val tracks = held?.tracks.orEmpty().filter { it.idString != id }
        if (held != null) putLikes(folder, tracks, held.ids - id)
        val api = apiOf(folder)
        launchReaction {
            if (held != null) cacheManager.saveLikedTracks(folder, tracks)
            runCatching {
                val uid = resolveUid(folder)
                if (wasLiked) api.unlikeTrack(uid, id)
                api.dislikeTrack(uid, id)
            }.onFailure {
                Log.e(TAG, "Dislike failed for $id", it)
                if (it is java.io.IOException) addPendingReaction(folder, PendingReaction(id, PendingReaction.Kind.DISLIKE))
            }
        }
        return wasLiked
    }

    private suspend fun addPendingReaction(folder: String, reaction: PendingReaction) {
        pendingMutex.withLock {
            val queue = cacheManager.loadPendingReactions(folder).mapNotNull(PendingReactions::parse)
            cacheManager.savePendingReactions(folder, PendingReactions.add(queue, reaction).map { it.saved })
        }
    }

    /** Sends what was liked and disliked without a connection, oldest first; what still cannot go stays. */
    suspend fun flushPendingReactions(account: AccountId? = null) {
        val folder = folderOf(account)
        val api = apiOf(folder)
        pendingMutex.withLock {
            val queue = cacheManager.loadPendingReactions(folder).mapNotNull(PendingReactions::parse)
            if (queue.isEmpty()) return
            val uid = resolveUid(folder)
            var left = queue
            for (reaction in queue) {
                try {
                    when (reaction.kind) {
                        PendingReaction.Kind.LIKE -> api.likeTrack(uid, reaction.trackId)
                        PendingReaction.Kind.UNLIKE -> api.unlikeTrack(uid, reaction.trackId)
                        PendingReaction.Kind.DISLIKE -> {
                            api.unlikeTrack(uid, reaction.trackId)
                            api.dislikeTrack(uid, reaction.trackId)
                        }
                    }
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Still no connection for the saved reactions", e)
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The server refused this one: sending it again will not help
                    Log.e(TAG, "Saved reaction refused for ${reaction.trackId}", e)
                }
                left = left - reaction
            }
            cacheManager.savePendingReactions(folder, left.map { it.saved })
        }
    }

    /** Charts for the search screen. Loaded once per app session unless [force]d (pull to refresh). */
    suspend fun refreshCharts(force: Boolean = false) {
        if (chartsLoadedThisSession && !force) return
        val running = likesMutex.withLock {
            chartsLoad?.takeIf { it.isActive } ?: scope.async { doRefreshCharts() }.also { chartsLoad = it }
        }
        running.await()
    }

    private suspend fun doRefreshCharts() = supervisorScope {
        val hits = listOf("чарт", "топ 100").map { q -> async { searchTracks(q, 0) } }.flatMap { it.await() }.distinctBy { it.idString }
        val trends = listOf("новинки", "премьеры").map { q -> async { searchTracks(q, 0) } }.flatMap { it.await() }.distinctBy { it.idString }
        if (hits.isNotEmpty()) {
            _weeklyHits.value = hits
            cacheManager.saveWeeklyHits(hits)
        }
        if (trends.isNotEmpty()) {
            _trendTracks.value = trends
            cacheManager.saveTrendTracks(trends)
        }
        val popular = trends.ifEmpty { hits }
        if (popular.isNotEmpty()) {
            _popularTracks.value = popular
            cacheManager.savePopularTracks(popular)
        }
        hitsPage = 1
        trendsPage = 1
        chartsLoadedThisSession = hits.isNotEmpty() || trends.isNotEmpty()
    }

    private suspend fun searchTracks(query: String, page: Int): List<TrackDetailsDto> =
        runCatching { api.search(query, page = page).result?.tracks?.results.orEmpty() }.getOrDefault(emptyList())

    fun loadMoreHits() = loadMore(loadingMoreHits, HITS_QUERIES, { ++hitsPage }) { more ->
        _weeklyHits.value = (_weeklyHits.value + more).distinctBy { it.idString }
    }

    fun loadMoreTrends() = loadMore(loadingMoreTrends, TRENDS_QUERIES, { ++trendsPage }) { more ->
        val combined = (_trendTracks.value + more).distinctBy { it.idString }
        _trendTracks.value = combined
        _popularTracks.value = combined
    }

    private fun loadMore(
        gate: java.util.concurrent.atomic.AtomicBoolean,
        queries: List<String>,
        nextPage: () -> Int,
        append: (List<TrackDetailsDto>) -> Unit
    ) {
        // Hits and trends have their own gate, so one paging does not block the other.
        if (!gate.compareAndSet(false, true)) return
        val page = nextPage()
        scope.launch {
            try {
                val more = searchTracks(queries[page % queries.size], page / queries.size)
                if (more.isNotEmpty()) append(more)
            } finally {
                gate.set(false)
            }
        }
    }

    /** Logout or "clear cache": forget every account's in-memory likes; they are read again from the cache. */
    fun resetForAccountChange() {
        likesSyncs.values.forEach { it.cancel() }
        likesSyncs.clear()
        likesByAccount.clear()
        loadedFolder = null
        _likedTracks.value = emptyList()
        _likedIds.value = emptySet()
    }

    private fun extractTrackIds(element: kotlinx.serialization.json.JsonElement?): List<String> {
        if (element == null) return emptyList()
        val ids = mutableListOf<String>()
        fun search(el: kotlinx.serialization.json.JsonElement) {
            when (el) {
                is kotlinx.serialization.json.JsonObject -> {
                    val trackObj = el["track"]
                    if (trackObj is kotlinx.serialization.json.JsonObject) {
                        (trackObj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }?.let { ids += it }
                    }
                    val directId = el["id"] ?: el["trackId"]
                    if (directId is kotlinx.serialization.json.JsonPrimitive && directId.content.length > 2 &&
                        directId.content.all { it.isDigit() || it == ':' || it == '-' } &&
                        (el.containsKey("albumId") || el.containsKey("timestamp"))
                    ) {
                        ids += directId.content
                    }
                    el.values.forEach { search(it) }
                }
                is kotlinx.serialization.json.JsonArray -> el.forEach { search(it) }
                else -> Unit
            }
        }
        search(element)
        return ids.distinct()
    }

    private companion object {
        const val TAG = "LibraryRepository"
        const val METADATA_CHUNK = 100
        /** The cache key when nobody is signed in (as before Phase 2a). */
        const val NO_ACCOUNT = "default"
        val HITS_QUERIES = listOf("чарт", "топ 100", "главные хиты", "суперхиты")
        val TRENDS_QUERIES = listOf("новинки", "в тренде", "горячие новинки", "премьеры")
    }
}
