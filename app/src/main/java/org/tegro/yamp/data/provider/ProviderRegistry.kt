package org.tegro.yamp.data.provider

import org.tegro.yamp.data.provider.jellyfin.JellyfinAccountStore
import org.tegro.yamp.data.provider.jellyfin.JellyfinClients
import org.tegro.yamp.data.provider.jellyfin.JellyfinProvider
import org.tegro.yamp.data.provider.jellyfin.cache.JellyfinDatabases
import org.tegro.yamp.data.provider.yandex.YandexProvider
import org.tegro.yamp.data.provider.yandex.YandexStreamResolver
import org.tegro.yamp.data.provider.yandex.repository.CatalogRepository
import org.tegro.yamp.data.provider.yandex.repository.LibraryRepository
import org.tegro.yamp.data.provider.yandex.repository.SearchRepository
import org.tegro.yamp.data.provider.yandex.repository.UserPlaylistsRepository
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.account.accountId
import org.tegro.yamp.domain.provider.MusicProvider
import org.tegro.yamp.domain.provider.StreamResolver
import org.tegro.yamp.domain.repository.AuthRepository
import org.tegro.yamp.provider.jellyfin.JellyfinApiFactory
import org.tegro.yamp.provider.jellyfin.JellyfinLibrary
import org.tegro.yamp.provider.jellyfin.JellyfinMappers
import org.tegro.yamp.provider.jellyfin.JellyfinRefs
import org.tegro.yamp.provider.jellyfin.JellyfinStreamProfile
import org.tegro.yamp.provider.jellyfin.JellyfinStreamResolver
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** The stream resolver of an entry's account (D74): the controller never resolves for another account. */
fun interface StreamResolvers {
    /** Null: the account context at the moment of each call (nobody signed in, an entry naming no account). */
    fun forAccount(account: AccountId?): StreamResolver
}

/**
 * One [MusicProvider] per signed-in account (docs/phase-3-providers.md §2, step 10), in sign-in order — the
 * stable provider order of D75. [all] lists the Yandex accounts, [jellyfin] the Jellyfin ones, [others] those and
 * "This device" (docs/phase-4d-local-files.md §8); `ProviderComposition`
 * puts them together for Home, Library and Search (step 24). A queue's entries always resolve through the queue's
 * account (D84).
 */
@Singleton
class ProviderRegistry @Inject constructor(
    private val authRepository: AuthRepository,
    private val yandexStreams: YandexStreamResolver.Factory,
    private val libraryRepository: LibraryRepository,
    private val playlistsRepository: UserPlaylistsRepository,
    private val searchRepository: SearchRepository,
    private val catalogRepository: CatalogRepository,
    private val jellyfinAccounts: JellyfinAccountStore,
    private val jellyfinClients: JellyfinClients,
    private val jellyfinDatabases: JellyfinDatabases,
    private val soundMode: org.tegro.yamp.data.player.SoundMode,
    /** Downloaded copies and fully cached streams (gap b); null in tests that only browse. */
    private val onDevice: org.tegro.yamp.data.provider.jellyfin.JellyfinOnDevice? = null,
    /** "This device" (docs/phase-4d-local-files.md §8): its provider, present while it has a source; null in tests without it. */
    private val local: org.tegro.yamp.data.provider.local.LocalProvider? = null,
    private val localAccount: org.tegro.yamp.data.provider.local.LocalAccountStore? = null
) : StreamResolvers {

    private val resolvers = ConcurrentHashMap<String, StreamResolver>()
    private val providers = ConcurrentHashMap<String, MusicProvider>()
    private val jellyfinStreams = ConcurrentHashMap<String, JellyfinStreamResolver>()
    private val contextResolver: StreamResolver by lazy { yandexStreams.forAccount(null) }

    override fun forAccount(account: AccountId?): StreamResolver = when {
        account == null -> contextResolver
        account.provider == Provider.JELLYFIN -> provider(account)?.streams ?: contextResolver
        // Local files resolve on the device whether or not the account is listed: a queue of it is hidden meanwhile (D62)
        account.provider == Provider.LOCAL -> local?.streams ?: contextResolver
        else -> resolvers.getOrPut(account.value) { yandexStreams.forAccount(account) }
    }

    /** The signed-in accounts' providers, in sign-in order. */
    fun all(): List<MusicProvider> = authRepository.allAccounts.value.map { providerOf(it.accountId()) }

    /** The signed-in Jellyfin accounts' providers, in sign-in order. */
    fun jellyfin(): List<MusicProvider> = jellyfinAccounts.accounts.value.mapNotNull { provider(it.id) }

    /** The accounts of the other services: Jellyfin's in sign-in order, then "This device" while it exists. */
    fun others(): List<MusicProvider> = jellyfin() + listOfNotNull(localProvider())

    /** "This device"'s provider while it has a source (D92). */
    fun localProvider(): MusicProvider? = local?.takeIf { localAccount?.present?.value == true }

    /** [account]'s provider; null when it is not signed in. */
    fun provider(account: AccountId): MusicProvider? = when (account.provider) {
        Provider.JELLYFIN -> jellyfinProvider(account)
        Provider.LOCAL -> localProvider()
        else -> account.takeIf { id -> authRepository.allAccounts.value.any { it.accountId() == id } }?.let(::providerOf)
    }

    /** Drops [account]'s provider (sign-out): a later sign-in builds a fresh one. */
    fun forget(account: AccountId) {
        providers.remove(account.value)
        resolvers.remove(account.value)
        jellyfinStreams.remove(account.value)
    }

    /** A signed-in Jellyfin account's streams, for downloads (gap b); null when it is not signed in. */
    fun jellyfinStreams(account: AccountId): JellyfinStreamResolver? {
        if (jellyfinProvider(account) == null) return null
        return jellyfinStreams[account.value]
    }

    private fun jellyfinProvider(account: AccountId): MusicProvider? {
        val stored = jellyfinAccounts.account(account) ?: return null
        val api = jellyfinClients.api(account) ?: return null
        return providers.getOrPut(account.value) {
            val base = JellyfinApiFactory.baseUrl(stored.serverUrl).toString()
            JellyfinProvider(
                account,
                JellyfinLibrary(api, stored.userId, JellyfinMappers(JellyfinRefs(stored.serverId), base), jellyfinDatabases.cache(account)),
                JellyfinStreamResolver(
                    baseUrl = base,
                    userId = stored.userId,
                    accountKey = account.value,
                    info = jellyfinClients.info,
                    token = { jellyfinAccounts.token(account) },
                    // The account's quality for the network in use (Original / Compressed, up to 320 kbps / Economy) (D79)
                    profile = { JellyfinStreamProfile(lossless = !soundMode.isCompatible(account), economy = soundMode.economyFor(account)) },
                    profileNow = { JellyfinStreamProfile(lossless = !soundMode.compatibleFor(account), economy = soundMode.economyFor(account)) },
                    localCopy = { itemId -> onDevice?.copyFor(account, itemId) },
                    isComplete = { key -> onDevice?.isComplete(key) == true },
                    online = { onDevice?.online() ?: true },
                    converts = jellyfinClients::converts
                ).also { jellyfinStreams[account.value] = it }
            )
        }
    }

    private fun providerOf(account: AccountId): MusicProvider = providers.getOrPut(account.value) {
        YandexProvider(
            account, forAccount(account),
            libraryRepository, playlistsRepository, searchRepository, catalogRepository
        )
    }
}
