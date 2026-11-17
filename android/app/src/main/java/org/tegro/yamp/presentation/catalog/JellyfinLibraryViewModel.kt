package org.tegro.yamp.presentation.catalog

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tegro.yamp.R
import org.tegro.yamp.data.provider.ProviderRegistry
import org.tegro.yamp.data.provider.jellyfin.JellyfinAccountStore
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.provider.BrowseCounts
import org.tegro.yamp.domain.provider.getOrNull
import javax.inject.Inject

/** One Jellyfin account's library in the Library tab: its name and how much of each kind it holds (null: not known yet). */
data class JellyfinLibraryRow(val account: AccountId, val label: String, val counts: BrowseCounts? = null) {

    data class Entry(val kind: BrowseKind, val icon: ImageVector, @param:StringRes val title: Int)

    fun rows(): List<Entry> = ENTRIES

    fun count(kind: BrowseKind): Int? = counts?.let {
        when (kind) {
            BrowseKind.ALBUMS -> it.albums
            BrowseKind.ARTISTS -> it.artists
            BrowseKind.PLAYLISTS -> it.playlists
            BrowseKind.GENRES -> it.genres
            BrowseKind.GENRE_ALBUMS -> null
        }
    }

    private companion object {
        val ENTRIES = listOf(
            Entry(BrowseKind.ALBUMS, Icons.Rounded.Album, R.string.jellyfin_lib_albums),
            Entry(BrowseKind.ARTISTS, Icons.Rounded.Person, R.string.jellyfin_lib_artists),
            Entry(BrowseKind.PLAYLISTS, Icons.AutoMirrored.Rounded.PlaylistPlay, R.string.jellyfin_lib_playlists),
            Entry(BrowseKind.GENRES, Icons.Rounded.LibraryMusic, R.string.jellyfin_lib_genres)
        )
    }
}

/**
 * The signed-in Jellyfin accounts' libraries for the Library tab (step 22), in sign-in order, with their counts —
 * from the server, or from the account's database while it is away.
 */
@HiltViewModel
class JellyfinLibraryViewModel @Inject constructor(
    private val accounts: JellyfinAccountStore,
    private val registry: ProviderRegistry
) : ViewModel() {

    private val _libraries = MutableStateFlow<List<JellyfinLibraryRow>>(emptyList())
    val libraries: StateFlow<List<JellyfinLibraryRow>> = _libraries.asStateFlow()

    init {
        viewModelScope.launch {
            accounts.accounts.collect { list ->
                _libraries.value = list.map { account ->
                    JellyfinLibraryRow(account.id, account.label, _libraries.value.firstOrNull { it.account == account.id }?.counts)
                }
                refresh()
            }
        }
    }

    /** Asks every library for its counts again (the Library tab opened). */
    fun refresh() {
        _libraries.value.forEach { row ->
            viewModelScope.launch {
                val counts = registry.provider(row.account)?.browse?.counts()?.getOrNull() ?: return@launch
                _libraries.update { rows -> rows.map { if (it.account == row.account) it.copy(counts = counts) else it } }
            }
        }
    }
}
