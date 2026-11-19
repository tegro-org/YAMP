package org.tegro.yamp.data.account

import org.tegro.yamp.BuildConfig
import android.util.Log
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.accountId
import org.tegro.yamp.domain.model.UserAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tegro.yamp.data.provider.yandex.cache.PlaylistCacheManager
import org.tegro.yamp.data.download.DownloadScheduler
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.data.network.YandexClients
import org.tegro.yamp.data.provider.yandex.network.YandexMusicApi
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.data.provider.yandex.repository.LibraryRepository
import org.tegro.yamp.data.provider.yandex.repository.UserPlaylistsRepository
import org.tegro.yamp.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in session: syncing the user's data, and switching or leaving accounts. Everything that
 * is per user (likes, playlists, wave queue, history, caches) is reset here, in one place, so the
 * screens only ask for an action.
 */
@Singleton
class AccountSessionManager @Inject constructor(
    private val clients: YandexClients,
    private val authRepository: AuthRepository,
    private val playbackController: PlaybackController,
    private val libraryRepository: LibraryRepository,
    private val playlistsRepository: UserPlaylistsRepository,
    private val cacheManager: PlaylistCacheManager,
    private val downloadManager: TrackDownloadManager,
    private val downloadScheduler: DownloadScheduler,
    private val httpClient: dagger.Lazy<okhttp3.OkHttpClient>,
    private val accountKeys: AccountKeyMigration,
    private val streamCache: org.tegro.yamp.data.player.StreamCache
) {
    /** The account context's API; calls name their own account from Phase 2a step 4 on. */
    private val api: YandexMusicApi get() = clients.current()

    /** Syncs outlive any screen. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Session job failed", e) }
    )

    /** One status refresh at a time: a fresh sign-in is seen by two callers, and its provisional id is adopted once. */
    private val statusLock = Mutex()

    private val refreshGate = StatusRefreshGate()

    /** Accounts whose My Wave queue was ensured in this process. */
    private val wavesEnsured = mutableSetOf<AccountId>()

    init {
        // The user moved into another account by making a queue of it active (D56): show that account's lists
        playbackController.accountEntered.onEach {
            loadCached()
            syncAll()
        }.launchIn(scope)
        // A second account signed in: "authorized" was already true, so nothing else notices. The server names it
        // here, its My Wave queue is created and the user is taken into it (D63)
        freshSignIns(authRepository.activeAccount).onEach { refreshAccountStatus() }.launchIn(scope)
        // Every named account has its My Wave queue (D63): also one named elsewhere (the likes' uid lookup) and one
        // signed in by a build that missed it — a start repairs that
        authRepository.allAccounts.onEach { accounts ->
            accounts.filter { it.hasRealUid }.map { it.accountId() }.filter { wavesEnsured.add(it) }
                .forEach { playbackController.ensureWaveQueue(it) }
        }.launchIn(scope)
    }

    /** Runs [action] in the session scope: an account change is never cut short by a closing screen. */
    fun launch(action: suspend AccountSessionManager.() -> Unit) {
        scope.launch { action() }
    }

    /** Cached library and playlists, shown before any network. */
    suspend fun loadCached() {
        libraryRepository.loadCached()
        playlistsRepository.loadCached()
    }

    /** Likes, charts, playlists and the My Wave queue. Concurrent calls join the requests already running. */
    fun syncAll() {
        scope.launch { refreshAccountStatus() }
        scope.launch { syncLikes() }
        scope.launch { libraryRepository.refreshCharts() }
        scope.launch {
            playlistsRepository.loadCached()
            playlistsRepository.refresh()
        }
        playbackController.primeWave()
    }

    /** Likes only (pull-to-refresh); new likes are downloaded when the account's auto-download is on. */
    suspend fun syncLikes() {
        libraryRepository.loadCached()
        val changed = libraryRepository.syncLikes()
        if (changed) downloadScheduler.downloadAllLiked(auto = true)
    }

    /** Refreshes account subscription status (hasPlus) and profile info from the server. */
    suspend fun refreshAccountStatus() = statusLock.withLock { refreshAccountStatusNow() }

    private suspend fun refreshAccountStatusNow() = withContext(Dispatchers.IO) {
        if (authRepository.getToken().isNullOrEmpty()) return@withContext
        val active = authRepository.getActiveAccount() ?: return@withContext
        // Startup, the account entered, the profile check and a fresh sign-in all ask: one refresh per account
        if (!refreshGate.shouldRefresh(active)) return@withContext
        // The account being signed in, not the context: a fresh sign-in is still provisional here
        val api = clients.api(active.accountId())
        try {
            val status = api.getAccountStatus().result ?: return@withContext
            val avatar = status.avatarUrl ?: runCatching { api.getYandexLoginInfo().avatarUrl }.getOrNull()
            // The server has just said who this is: a provisional id gives way to the real one
            val uid = status.uidString.takeIf { !active.hasRealUid && it.isNotEmpty() } ?: active.uid
            authRepository.saveOrUpdateAccount(
                active.copy(
                    uid = uid,
                    displayName = status.displayUserName.ifBlank { active.displayName },
                    login = status.account?.login?.ifBlank { active.login } ?: active.login,
                    avatarUrl = avatar ?: active.avatarUrl,
                    hasPlus = status.hasPlus
                )
            )
            // What was saved under the provisional id follows the account (D59)
            if (uid != active.uid) accountKeys.adoptRealUid(active, uid)
            val account = AccountId.yandex(uid)
            // Signed in again: its hidden queues are back (D62), so is its marker
            authRepository.forgetRemovedAccount(account.value)
            // Its My Wave queue exists from sign-in on (D63); a fresh sign-in takes the user into the account, and
            // the first start with no queue at all opens the first account's My Wave
            playbackController.ensureWaveQueue(account)
            playbackController.enterAccount(account, onlyWhenNoQueue = uid == active.uid)
            refreshGate.done(uid)
            Log.i(TAG, "Account status refreshed: hasPlus=${status.hasPlus}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Account status refresh: ${e.message}")
        }
    }

    /** Fills in the name and avatar of an account saved before they were known. */
    suspend fun refreshProfileIfIncomplete() = withContext(Dispatchers.IO) {
        refreshAccountStatus()
    }

    /**
     * "Switch account" is entering it (D56): its My Wave queue becomes active and the active account follows the
     * queue; its lists load on [PlaybackController.accountEntered]. Likes and playlists are kept per account, so
     * nothing is evicted or dropped.
     */
    suspend fun switchAccount(uid: String) {
        val account = authRepository.getAllAccounts().firstOrNull { it.uid == uid } ?: return
        playbackController.enterAccount(account.accountId())
    }

    /** Signs out of the account the Settings profile shows — the context account, the active queue's (D56, D62). */
    suspend fun logoutCurrent() {
        val queueAccount = playbackController.activeQueue.value?.queue?.account
        contextUser(queueAccount, authRepository.getAllAccounts(), authRepository.getActiveAccount())?.let { signOut(listOf(it)) }
    }

    suspend fun logoutAll() = signOut(authRepository.getAllAccounts())

    suspend fun removeAccount(uid: String) {
        authRepository.getAllAccounts().firstOrNull { it.uid == uid }?.let { signOut(listOf(it)) }
    }

    // Debug: test sign-out without losing credentials
    /**
     * "Hide (test sign-out)", debug builds only: the real sign-out of [uid] (D62 — its queues hidden, the active queue's
     * fallback, its lists and client dropped), except that its record and token are stashed instead of lost and its
     * downloads stay. [unhideAccount] signs it in again.
     */
    suspend fun hideAccount(uid: String) {
        if (!BuildConfig.DEBUG) return
        authRepository.getAllAccounts().firstOrNull { it.uid == uid }?.let { signOut(listOf(it), keepCredentials = true) }
    }

    /** "Unhide", debug builds only: exactly a re-sign-in with the same uid — its hidden queues come back (D62). */
    suspend fun unhideAccount(uid: String) {
        if (!BuildConfig.DEBUG) return
        val account = authRepository.takeHiddenAccount(uid) ?: return
        authRepository.saveOrUpdateAccount(account)
        refreshAccountStatus()
        launch { loadCached() }
    }

    /**
     * Sign-out (D62): the accounts' queues — their My Wave too — stay as they are, hidden until the same account
     * signs in again (the switcher derives "removed" from the account list; keyed by uid, D59). What goes: the
     * accounts' cached lists, their network clients and caches, and the downloads no remaining account likes. Each
     * name is kept for the hidden queues' marker. If the active queue was one of theirs, playback moves to the default
     * account's first queue (else the most recently used account's), or to none.
     */
    private suspend fun signOut(leaving: List<UserAccount>, keepCredentials: Boolean = false) {
        if (leaving.isEmpty()) return
        val leavingIds = leaving.map { it.accountId() }.toSet()
        // Signing in again refreshes it again (D62: its queues come back there)
        leaving.forEach { refreshGate.forget(it.uid) }
        val staying = authRepository.getAllAccounts().filter { it.accountId() !in leavingIds }
        // The last Yandex account leaving while Jellyfin stays: its default queue takes over
        val fallback = playbackController.fallbackAccount(leavingIds)
        leavingIds.forEach { playbackController.leaveAccount(it, fallback) }
        withContext(Dispatchers.IO) {
            // The clearMusicCache rule: a track another account likes stays downloaded
            val kept = staying.flatMapTo(mutableSetOf()) { other -> cacheManager.loadLikedTracks(other.accountId().value).map { it.idString } }
            leaving.forEach { account ->
                val id = account.accountId()
                authRepository.rememberRemovedAccount(id.value, account.displayName.ifBlank { account.login })
                if (!keepCredentials) {
                    SignOutPlan.downloadsToRemove(cacheManager.loadLikedTracks(id.value).map { it.idString }, kept).forEach { downloadManager.removeTrack(it) }
                    // Its streams on disk and the links kept with them go too
                    streamCache.removeAccount(id.value)
                }
                // Only this account's rows: the shared ones hold the hidden queues' track details
                cacheManager.clearUserCache(id.value)
                clients.forget(id)
            }
        }
        leaving.forEach {
            // Debug: test sign-out without losing credentials — the record and its token wait for "Unhide"
            if (keepCredentials) authRepository.stashHiddenAccount(it)
            authRepository.removeAccount(it.uid)
        }
        // The signed-out accounts' lists leave memory with them; the others' are read again from the cache
        resetUserData()
        if (authRepository.getActiveAccount() != null) launch { loadCached() }
    }

    /** Drops the active user's cached lists and downloaded tracks no other saved account has liked, then reloads. */
    suspend fun clearMusicCache() {
        withContext(Dispatchers.IO) {
            val activeUid = authRepository.getActiveAccount()?.uid
            cacheManager.clearUserCache(activeFolder)
            val protectedTrackIds = authRepository.getAllAccounts()
                .filter { it.uid != activeUid }
                .flatMapTo(mutableSetOf()) { other -> cacheManager.loadLikedTracks(other.accountId().value).map { it.idString } }
            libraryRepository.likedTracks.value.map { it.idString }
                .filterNot { it in protectedTrackIds }
                .forEach { downloadManager.removeTrack(it) }
        }
        reloadAfterCacheClear()
    }

    private suspend fun reloadAfterCacheClear() {
        resetUserData()
        playbackController.clearWaveAndHistory()
        scope.launch { syncLikes() }
    }

    private fun resetUserData() {
        // Answers kept by the network cache belong to the account that asked: another one never gets them
        scope.launch(Dispatchers.IO) {
            runCatching { httpClient.get().cache?.evictAll() }.onFailure { Log.w(TAG, "Network cache not cleared", it) }
            clients.evictCaches()
        }
        libraryRepository.resetForAccountChange()
        // Also closes an opened playlist: it is no longer in the list
        playlistsRepository.resetForAccountChange()
    }

    private val activeFolder: String
        get() = authRepository.getActiveAccount()?.accountId()?.value ?: "default"

    private companion object {
        const val TAG = "AccountSession"
    }
}

/**
 * Fresh sign-ins: the active account turns into one the server has not named yet (a provisional uid, set by the
 * sign-in screen). Emitted once per provisional account; the active account moving between signed-in accounts as
 * queues switch (D56), and the provisional id giving way to the real one, emit nothing.
 */
internal fun freshSignIns(active: Flow<UserAccount?>): Flow<String> =
    active.mapNotNull { account -> account?.takeIf { !it.hasRealUid }?.uid }.distinctUntilChanged()

/**
 * Which account status refreshes are worth a request: each named account once per process (until it signs out), a
 * provisional one — a fresh sign-in the server has not named yet — always.
 */
internal class StatusRefreshGate {
    private val refreshed = mutableSetOf<String>()

    fun shouldRefresh(account: UserAccount): Boolean = !account.hasRealUid || account.uid !in refreshed

    fun done(uid: String) {
        refreshed += uid
    }

    fun forget(uid: String) {
        refreshed -= uid
    }
}
