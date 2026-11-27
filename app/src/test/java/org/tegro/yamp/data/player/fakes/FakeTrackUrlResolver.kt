package org.tegro.yamp.data.player.fakes

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.tegro.yamp.data.network.TrackPreloadManager
import org.tegro.yamp.data.network.TrackUrlResolver
import org.tegro.yamp.domain.account.AccountId

/**
 * Stream links by track id, no network. [getOrFetchUrl] answers from [urls] at once, or waits on
 * [pending] when a test sets one (a slow link). Nothing is resolved ahead of time ([getResolvedUrl] is
 * null), so the controller always goes through the suspending path, as it does on a cold start.
 */
class FakeTrackUrlResolver : TrackUrlResolver {
    val urls = mutableMapOf<String, String>()
    /** Track ids with a downloaded copy: they play from "" without a link. */
    val copies = mutableSetOf<String>()
    /** When set, [getOrFetchUrl] waits for it before answering. */
    var pending: CompletableDeferred<Unit>? = null

    val fetched = mutableListOf<String>()
    /** The account each [getOrFetchUrl] was for, in call order (`null` = the account context). */
    val fetchedFor = mutableListOf<AccountId?>()
    val evicted = mutableListOf<String>()
    val preloaded = mutableListOf<List<String>>()

    fun url(trackId: String) = "https://stream.example/$trackId.mp3"

    override fun qualityOf(trackId: String): TrackPreloadManager.StreamQuality? = null
    override fun offeredOf(trackId: String): String? = null
    override fun getResolvedUrl(trackId: String, account: AccountId?): String? = null

    override suspend fun getOrFetchUrl(trackId: String, account: AccountId?): String? {
        fetched += trackId
        fetchedFor += account
        pending?.await()
        return urls[trackId]
    }

    override suspend fun resolveOrdinaryStream(trackId: String, account: AccountId?): TrackPreloadManager.ResolvedStream? = null
    override fun playableCopy(trackId: String): File? = null
    override fun hasPlayableCopy(trackId: String): Boolean = trackId in copies

    override fun evictUrl(trackId: String, account: AccountId?) {
        evicted += trackId
    }

    override fun preloadTracks(trackIds: List<String>, account: AccountId?) {
        preloaded += trackIds
    }

    override fun clearCache() = urls.clear()
}
