package org.tegro.yamp.data.provider.yandex.repository

import org.tegro.yamp.data.dto.AlbumDto
import org.tegro.yamp.data.dto.ArtistDto
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.network.YandexClients
import org.tegro.yamp.data.provider.yandex.network.YandexMusicApi
import org.tegro.yamp.domain.account.AccountId
import javax.inject.Inject
import javax.inject.Singleton

/** Track search, shared by the search screen and voice search in Android Auto. */
@Singleton
class SearchRepository @Inject constructor(
    private val clients: YandexClients
) {
    /** The account context's API; calls name their own account from Phase 2a step 4 on. */
    private val api: YandexMusicApi get() = clients.current()

    /** Throws on a network error. Lazy lists key items by id, so duplicates from the API are dropped. */
    suspend fun searchTracks(text: String): List<TrackDetailsDto> = search(text).tracks

    /**
     * Tracks, artists and albums for a query. Throws on a network error. [account] names whose API asks (a
     * provider's own, step 10); null: the account context.
     */
    suspend fun search(text: String, account: AccountId? = null): SearchResults {
        val result = (account?.let(clients::api) ?: api).search(text).result
        return SearchResults(
            tracks = result?.tracks?.results.orEmpty().distinctBy { it.idString },
            artists = result?.artists?.results.orEmpty().filter { it.idString != null }.distinctBy { it.idString }.take(MAX_ROW),
            albums = result?.albums?.results.orEmpty().filter { it.idString != null }.distinctBy { it.idString }.take(MAX_ROW)
        )
    }

    data class SearchResults(
        val tracks: List<TrackDetailsDto> = emptyList(),
        val artists: List<ArtistDto> = emptyList(),
        val albums: List<AlbumDto> = emptyList()
    )

    private companion object {
        const val MAX_ROW = 10
    }
}
