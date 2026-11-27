package org.tegro.yamp.data.provider.yandex.repository

import org.tegro.yamp.data.dto.AlbumDto
import org.tegro.yamp.data.dto.ArtistBriefDto
import org.tegro.yamp.data.network.YandexClients
import org.tegro.yamp.data.provider.yandex.network.YandexMusicApi
import org.tegro.yamp.domain.account.AccountId
import javax.inject.Inject
import javax.inject.Singleton

/** Artist and album pages. Kept in memory for the session, so going back and forth costs no requests. */
@Singleton
class CatalogRepository @Inject constructor(
    private val clients: YandexClients
) {
    /** The account context's API; calls name their own account from Phase 2a step 4 on. */
    private val api: YandexMusicApi get() = clients.current()

    private val artists = lruMap<String, ArtistBriefDto>()
    private val albums = lruMap<String, AlbumDto>()

    /** [account]: whose API asks (a provider's own, step 10); null: the account context. */
    private fun apiFor(account: AccountId?): YandexMusicApi = account?.let(clients::api) ?: api

    /** Throws on a network error. */
    suspend fun artist(id: String, account: AccountId? = null): ArtistBriefDto =
        synchronized(artists) { artists[id] } ?: (apiFor(account).getArtistBriefInfo(id).result ?: error("Empty artist " + id))
            .let { brief -> brief.copy(popularTracks = brief.popularTracks.distinctBy { it.idString }) }
            .also { synchronized(artists) { artists[id] = it } }

    /** Throws on a network error. */
    suspend fun album(id: String, account: AccountId? = null): AlbumDto =
        synchronized(albums) { albums[id] } ?: (apiFor(account).getAlbumWithTracks(id).result ?: error("Empty album " + id))
            .also { synchronized(albums) { albums[id] = it } }

    private fun <K, V> lruMap(): MutableMap<K, V> = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > CACHE_SIZE
    }

    private companion object {
        const val CACHE_SIZE = 20
    }
}
