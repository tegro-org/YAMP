package org.tegro.yamp.presentation.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tegro.yamp.data.account.AccountContext
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.data.player.PlayRequests
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.data.provider.ProviderRegistry
import org.tegro.yamp.data.provider.jellyfin.JellyfinAccountStore
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.provider.getOrNull
import org.tegro.yamp.domain.account.accountId
import org.tegro.yamp.domain.repository.AuthRepository
import javax.inject.Inject

/** A row of an account's Library section (canvas `hj-lib`), in order. */
enum class LibraryRow { LIKED, PLAYLISTS, ALBUMS, ARTISTS, DOWNLOADED, FOLDERS }

/**
 * The rows an account of [provider] has: Liked / Favourites · Playlists · Albums · Artists · Downloaded (· Folders).
 * "This device" (canvas `lo-lib`): Favourites (once there are some) · Playlists (once there are some — no M3U in
 * Stage 1) · Albums · Artists · Folders, no Downloaded (everything is on the device). Pure.
 */
fun libraryRows(provider: Provider, counts: Map<LibraryRow, Int> = emptyMap()): List<LibraryRow> = when (provider) {
    Provider.JELLYFIN -> LibraryRow.entries
    Provider.LOCAL -> LibraryRow.entries.filter { row ->
        when (row) {
            LibraryRow.LIKED, LibraryRow.PLAYLISTS -> (counts[row] ?: 0) > 0
            LibraryRow.DOWNLOADED -> false
            else -> true
        }
    }
    Provider.YANDEX -> LibraryRow.entries - LibraryRow.FOLDERS
}

/** The account headers show only with more than one section: one account's rows need no name over them (user, gap (e)). */
fun showsAccountHeaders(sections: List<AccountLibrary>): Boolean = sections.size > 1

/**
 * The Library's sections (and the car's Music columns) in the accounts' [order] (keys, Settings → Accounts, D90);
 * an account [order] does not hold yet follows, Yandex before Jellyfin. The Yandex account in [context] is marked;
 * what [known] already counted is kept. Pure.
 */
fun librarySections(
    yandex: List<AccountLibrary>,
    context: AccountId?,
    jellyfin: List<AccountLibrary>,
    known: List<AccountLibrary> = emptyList(),
    order: List<String> = emptyList()
): List<AccountLibrary> {
    val counted = known.associateBy { it.account }
    val place = order.withIndex().associate { (i, key) -> key to i }
    return (yandex.map { it.copy(inContext = it.account == context) } + jellyfin)
        .withIndex().sortedBy { (i, section) -> place[section.account.value] ?: (order.size + i) }.map { it.value }
        .map { section -> counted[section.account]?.let { section.copy(counts = it.counts, recent = it.recent, local = it.local) } ?: section }
}

/**
 * One signed-in account's section of the Library (gap (e), revises D33 — see kb/decisions.md D89): its rows with its
 * own counts (null: not known yet), nothing summed across accounts. [name] / [service] head the section.
 */
data class AccountLibrary(
    val account: AccountId,
    val name: String,
    val service: String,
    val counts: Map<LibraryRow, Int> = emptyMap(),
    /** Jellyfin with one account: "Recently added" under the rows. */
    val recent: List<Album> = emptyList(),
    /**
     * The Yandex account in context: its Liked, Playlists and Downloaded are the main view model's lists (the
     * Library's own pages); another Yandex account's open as pages of the catalog stack through its provider.
     */
    val inContext: Boolean = false,
    /** "This device": the scan in progress and the volumes not connected (canvas `lo-lib`, the status line). */
    val local: org.tegro.yamp.data.provider.local.LocalOverview? = null
) {
    val rows: List<LibraryRow> get() = libraryRows(account.provider ?: Provider.YANDEX, counts)
}

/**
 * The Library tab's sections, one per signed-in account (gap (e)), in the accounts' order (D90; the Yandex one in
 * context marked). Counts come from the providers (Jellyfin: the server, or its database while it
 * is away); the Yandex likes, playlists and downloads are the main view model's and are filled in by the tab.
 */
@HiltViewModel
class JellyfinLibraryViewModel @Inject constructor(
    private val accounts: JellyfinAccountStore,
    private val registry: ProviderRegistry,
    private val accountContext: AccountContext,
    private val authRepository: AuthRepository,
    private val downloads: TrackDownloadManager,
    private val playbackController: PlaybackController,
    private val playRequests: PlayRequests,
    private val signedIn: org.tegro.yamp.data.account.SignedInAccounts,
    private val localOverview: org.tegro.yamp.data.provider.local.LocalOverviewModel
) : ViewModel() {

    private val _sections = MutableStateFlow<List<AccountLibrary>>(emptyList())
    val sections: StateFlow<List<AccountLibrary>> = _sections.asStateFlow()

    init {
        viewModelScope.launch {
            combine(authRepository.allAccounts, accountContext.user, accounts.accounts, signedIn.accounts) { yandex, user, jellyfin, ordered ->
                librarySections(
                    yandex = yandex.map { AccountLibrary(it.accountId(), it.displayName.ifBlank { it.login }, YANDEX_SERVICE) },
                    context = user?.accountId(),
                    // Then the other services: Jellyfin, and "This device" while it exists (D92)
                    jellyfin = jellyfin.map { AccountLibrary(it.id, it.userName, it.serverName.ifBlank { JELLYFIN_SERVICE }) } +
                        ordered.filter { it.provider == Provider.LOCAL }
                            .map { AccountLibrary(it.id, it.displayName, it.displayName, local = localOverview.overview.value) },
                    known = _sections.value,
                    order = ordered.map { it.id.value }
                )
            }.collect { sections ->
                val fresh = sections.map { it.account } != _sections.value.map { it.account }
                _sections.value = sections
                // Only a changed set of accounts asks again; the context moving between them does not
                if (fresh) refresh()
            }
        }
        // "This device": its status line follows the scan and the volumes; a scan's result changes its counts
        viewModelScope.launch {
            var tracks = -1
            localOverview.overview.collect { overview ->
                val local = AccountId.local()
                _sections.update { list -> list.map { if (it.account == local) it.copy(local = overview) else it } }
                if (overview.tracks != tracks) {
                    tracks = overview.tracks
                    if (_sections.value.any { it.account == local }) load(local)
                }
            }
        }
    }

    /** Asks every account for its counts again (the Library tab opened). */
    fun refresh() {
        _sections.value.forEach { section ->
            viewModelScope.launch { load(section.account) }
        }
    }

    private suspend fun load(account: AccountId) {
        val provider = registry.provider(account) ?: return
        provider.browse?.counts()?.getOrNull()?.let { c ->
            put(account, mapOf(LibraryRow.ALBUMS to c.albums, LibraryRow.ARTISTS to c.artists, LibraryRow.PLAYLISTS to c.playlists))
        }
        // Yandex too: an account out of context counts its own likes (the one in context shows the main lists)
        provider.library?.likedTracks()?.getOrNull()?.let { liked ->
            put(account, mapOf(LibraryRow.LIKED to liked.size, LibraryRow.DOWNLOADED to liked.count { downloads.isKnownDownloaded(it.ref.id) }))
        }
        if (account.provider == Provider.YANDEX) return
        provider.folders?.root()?.getOrNull()?.let { put(account, mapOf(LibraryRow.FOLDERS to it.folders.size)) }
        provider.browse?.recentAlbums(RECENT)?.getOrNull()?.let { recent ->
            _sections.update { list -> list.map { if (it.account == account) it.copy(recent = recent) else it } }
        }
    }

    private fun put(account: AccountId, counts: Map<LibraryRow, Int>) =
        _sections.update { list -> list.map { if (it.account == account) it.copy(counts = it.counts + counts) else it } }

    /**
     * "Play" / "Shuffle" of a Library without Yandex: the first other account's favourites, in its own queue — for
     * "This device" without favourites, its whole library in folder order.
     */
    fun playFavourites(account: AccountId, shuffle: Boolean) {
        viewModelScope.launch {
            val provider = registry.provider(account)
            val liked = provider?.library?.likedTracks()?.getOrNull().orEmpty().ifEmpty {
                if (account.provider == Provider.LOCAL) allLocal(provider) else emptyList()
            }.filter { it.available }
            if (liked.isEmpty()) return@launch
            playRequests.userStarted()
            playbackController.playInAccount(account, if (shuffle) liked.shuffled() else liked, null)
        }
    }

    /** Every track of "This device": each root folder's, in folder order. */
    private suspend fun allLocal(provider: org.tegro.yamp.domain.provider.MusicProvider?): List<org.tegro.yamp.domain.media.Track> {
        val folders = provider?.folders ?: return emptyList()
        val root = folders.root().getOrNull() ?: return emptyList()
        return root.folders.flatMap { folders.tracks(it.ref).getOrNull().orEmpty() } + root.tracks
    }

    private companion object {
        const val RECENT = 6
        const val YANDEX_SERVICE = "Yandex"
        const val JELLYFIN_SERVICE = "Jellyfin"
    }
}
