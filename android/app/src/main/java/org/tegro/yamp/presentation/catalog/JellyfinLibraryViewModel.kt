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

/** The rows an account of [provider] has: Liked / Favourites · Playlists · Albums · Artists · Downloaded (· Folders). Pure. */
fun libraryRows(provider: Provider): List<LibraryRow> =
    if (provider == Provider.JELLYFIN) LibraryRow.entries else LibraryRow.entries - LibraryRow.FOLDERS

/** The account headers show only with more than one section: one account's rows need no name over them (user, gap (e)). */
fun showsAccountHeaders(sections: List<AccountLibrary>): Boolean = sections.size > 1

/**
 * The Library's sections in order: every signed-in Yandex account (theirs, the default one first), the one in
 * [context] marked, then the Jellyfin accounts in sign-in order; what [known] already counted is kept. Pure.
 */
fun librarySections(
    yandex: List<AccountLibrary>,
    context: AccountId?,
    jellyfin: List<AccountLibrary>,
    known: List<AccountLibrary> = emptyList()
): List<AccountLibrary> {
    val counted = known.associateBy { it.account }
    return (yandex.map { it.copy(inContext = it.account == context) } + jellyfin)
        .map { section -> counted[section.account]?.let { section.copy(counts = it.counts, recent = it.recent) } ?: section }
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
    val inContext: Boolean = false
) {
    val rows: List<LibraryRow> get() = libraryRows(account.provider ?: Provider.YANDEX)
}

/**
 * The Library tab's sections, one per signed-in account (gap (e)): every Yandex account (the one in context marked),
 * then the Jellyfin accounts in sign-in order. Counts come from the providers (Jellyfin: the server, or its database while it
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
    private val playRequests: PlayRequests
) : ViewModel() {

    private val _sections = MutableStateFlow<List<AccountLibrary>>(emptyList())
    val sections: StateFlow<List<AccountLibrary>> = _sections.asStateFlow()

    init {
        viewModelScope.launch {
            combine(authRepository.allAccounts, accountContext.user, accounts.accounts) { yandex, user, jellyfin ->
                librarySections(
                    yandex = yandex.map { AccountLibrary(it.accountId(), it.displayName.ifBlank { it.login }, YANDEX_SERVICE) },
                    context = user?.accountId(),
                    jellyfin = jellyfin.map { AccountLibrary(it.id, it.userName, it.serverName.ifBlank { JELLYFIN_SERVICE }) },
                    known = _sections.value
                )
            }.collect { sections ->
                val fresh = sections.map { it.account } != _sections.value.map { it.account }
                _sections.value = sections
                // Only a changed set of accounts asks again; the context moving between them does not
                if (fresh) refresh()
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
        if (account.provider != Provider.JELLYFIN) return
        provider.folders?.root()?.getOrNull()?.let { put(account, mapOf(LibraryRow.FOLDERS to it.folders.size)) }
        provider.browse?.recentAlbums(RECENT)?.getOrNull()?.let { recent ->
            _sections.update { list -> list.map { if (it.account == account) it.copy(recent = recent) else it } }
        }
    }

    private fun put(account: AccountId, counts: Map<LibraryRow, Int>) =
        _sections.update { list -> list.map { if (it.account == account) it.copy(counts = it.counts + counts) else it } }

    /** "Play" / "Shuffle" of a Library without Yandex: the first Jellyfin account's favourites, in its own queue. */
    fun playFavourites(account: AccountId, shuffle: Boolean) {
        viewModelScope.launch {
            val liked = registry.provider(account)?.library?.likedTracks()?.getOrNull().orEmpty()
            if (liked.isEmpty()) return@launch
            playRequests.userStarted()
            playbackController.playInAccount(account, if (shuffle) liked.shuffled() else liked, null)
        }
    }

    private companion object {
        const val RECENT = 6
        const val YANDEX_SERVICE = "Yandex"
        const val JELLYFIN_SERVICE = "Jellyfin"
    }
}
