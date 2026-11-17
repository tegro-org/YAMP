package org.tegro.yamp.data.provider

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.tegro.yamp.data.account.AccountContext
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.Artist
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.provider.Library
import org.tegro.yamp.domain.provider.MusicProvider
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.ProviderError
import javax.inject.Inject
import javax.inject.Singleton

/** An item and the account whose provider gave it: what a provider badge names (D21). */
data class Sourced<out T>(val item: T, val account: AccountId)

/**
 * What a query finds across the composed providers, one list per type in the stable provider order (D75).
 * [showBadges] is false with one provider: then the screen looks exactly as before composition.
 */
data class ComposedSearch(
    val tracks: List<Sourced<Track>> = emptyList(),
    val artists: List<Sourced<Artist>> = emptyList(),
    val albums: List<Sourced<Album>> = emptyList(),
    val showBadges: Boolean = false
)

/**
 * Builds Search, Library and Home from provider sections (C7, D19, D21, D75; docs/phase-3-providers.md step 16).
 *
 * The composed providers are, in this order: the account context's Yandex provider (the active queue's account,
 * D56 — several Yandex accounts never merge: one is the context), then each account of another service in
 * sign-in order (Jellyfin, Phase 4). A section shows only when a provider has the capability.
 */
@Singleton
class ProviderComposition @Inject constructor(
    private val registry: ProviderRegistry,
    private val accountContext: AccountContext
) {
    /** The providers a screen composes, in the stable order. Empty while nobody is signed in. */
    fun providers(): List<MusicProvider> {
        val all = registry.all()
        val context = accountContext.current()?.let(registry::provider) ?: all.firstOrNull { it.account.isYandex }
        val others = all.filterNot { it.account.isYandex }
        return listOfNotNull(context) + others
    }

    /** The providers that have a library, for the Library screen's sections. */
    fun libraries(): List<Pair<AccountId, Library>> = providers().mapNotNull { p -> p.library?.let { p.account to it } }

    /**
     * Searches every composed provider at once and keeps their order per type. A provider that fails is left out;
     * when every one fails the error is the first one (the screen keeps what it showed, as before composition).
     */
    suspend fun search(text: String): Outcome<ComposedSearch> = coroutineScope {
        val searching = providers().mapNotNull { p -> p.search?.let { search -> p.account to async { search.search(text) } } }
        if (searching.isEmpty()) return@coroutineScope Outcome.Err(ProviderError.NotFound)
        val results = searching.map { (account, found) -> account to found.await() }
        val ok = results.mapNotNull { (account, outcome) -> (outcome as? Outcome.Ok)?.let { account to it.value } }
        if (ok.isEmpty()) return@coroutineScope results.first().second as Outcome.Err
        Outcome.Ok(
            ComposedSearch(
                tracks = ok.flatMap { (account, r) -> r.tracks.map { Sourced(it, account) } },
                artists = ok.flatMap { (account, r) -> r.artists.map { Sourced(it, account) } },
                albums = ok.flatMap { (account, r) -> r.albums.map { Sourced(it, account) } },
                showBadges = searching.size >= 2
            )
        )
    }

    private val AccountId.isYandex: Boolean get() = provider == org.tegro.yamp.domain.account.Provider.YANDEX
}
