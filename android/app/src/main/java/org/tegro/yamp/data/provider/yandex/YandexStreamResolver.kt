package org.tegro.yamp.data.provider.yandex

import android.net.Uri
import kotlinx.coroutines.withContext
import org.tegro.yamp.data.network.TrackUrlResolver
import org.tegro.yamp.di.DispatcherProvider
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.provider.Decryption
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.ProviderError
import org.tegro.yamp.domain.provider.ResolvedStream
import org.tegro.yamp.domain.provider.StreamPrefs
import org.tegro.yamp.domain.provider.StreamQuality
import org.tegro.yamp.domain.provider.StreamResolver
import org.tegro.yamp.domain.queue.TrackRef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Yandex streams (docs/phase-3-providers.md step 4, D74) over [TrackUrlResolver] — the upstream-hot
 * `TrackPreloadManager` stays as it is (A3); this only says what it found in the neutral terms.
 *
 * Order: a downloaded or reserve copy on the device, a link resolved earlier and still fresh, then the network.
 * FM stations (`radio_…`) are not resolved here — their stream is the station's own URL.
 */
class YandexStreamResolver internal constructor(
    private val links: TrackUrlResolver,
    /** The copy check reads the disk: on [DispatcherProvider.io]. */
    private val dispatchers: DispatcherProvider,
    /** The on-disk stream cache (D86): a track whose bytes are all there still plays when no link can be had. */
    private val streamCache: org.tegro.yamp.data.player.CachedStreams,
    /**
     * Whose links these are (step 10: one resolver per account). Null: the account context at the moment of each
     * call — the resolver used while nobody is signed in or an entry names no account.
     */
    private val account: AccountId?
) : StreamResolver {

    /** Makes the resolver of one account; the provider registry keeps one per account. */
    @Singleton
    class Factory @Inject internal constructor(
        private val links: TrackUrlResolver,
        private val dispatchers: DispatcherProvider,
        private val streamCache: org.tegro.yamp.data.player.CachedStreams
    ) {
        fun forAccount(account: AccountId?) = YandexStreamResolver(links, dispatchers, streamCache, account)
    }

    override fun cached(track: TrackRef): ResolvedStream? {
        val id = track.id
        return links.getResolvedUrl(id, account)?.takeIf { it.isNotBlank() }?.let { stream(id, it) }
    }

    override suspend fun resolve(track: TrackRef, prefs: StreamPrefs): Outcome<ResolvedStream> {
        val id = track.id
        withContext(dispatchers.io) { links.playableCopy(id) }?.let {
            return Outcome.Ok(ResolvedStream(Uri.fromFile(it).toString(), localFile = true))
        }
        cached(track)?.let { return Outcome.Ok(it) }
        if (id.startsWith(RADIO_PREFIX)) return Outcome.Err(ProviderError.NotFound)
        val url = links.getOrFetchUrl(id, account)?.takeIf { it.isNotBlank() }
            ?: return streamCache.completeStreamFor(org.tegro.yamp.data.player.StreamCache.keyPrefix(account?.value, id))
                ?.let { Outcome.Ok(it) }
                ?: Outcome.Err(ProviderError.Unavailable("no stream link"))
        return Outcome.Ok(stream(id, url))
    }

    override fun invalidate(track: TrackRef) = links.evictUrl(track.id, account)

    private fun stream(id: String, url: String): ResolvedStream {
        val quality = links.qualityOf(id)?.let { StreamQuality(it.codec, it.bitrateKbps) }
        return ResolvedStream(
            uri = url,
            quality = quality,
            // The key rides in the URI fragment; EncrawDataSourceFactory reads it there (upstream-hot, A3)
            decryption = if (url.contains(ENCRAW_FRAGMENT)) Decryption(ENCRAW) else null,
            // Same account, track and quality: the same bytes on disk, whatever the signed link (D86)
            cacheKey = org.tegro.yamp.data.player.StreamCache.key(
                account?.value, id, quality?.let { "${it.codec}-${it.bitrateKbps}" } ?: "default"
            )
        )
    }

    private companion object {
        const val RADIO_PREFIX = "radio_"
        const val ENCRAW_FRAGMENT = "#encraw_key="
        const val ENCRAW = "encraw"
    }
}
