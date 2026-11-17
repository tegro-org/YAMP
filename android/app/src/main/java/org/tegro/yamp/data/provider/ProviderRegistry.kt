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
 * stable provider order of D75. [all] lists the Yandex accounts, [jellyfin] the Jellyfin ones; `ProviderComposition`
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
    private val fixedOutput: org.tegro.yamp.data.player.FixedOutputRate
) : StreamResolvers {

    private val resolvers = ConcurrentHashMap<String, StreamResolver>()
    private val providers = ConcurrentHashMap<String, MusicProvider>()
    private val contextResolver: StreamResolver by lazy { yandexStreams.forAccount(null) }

    override fun forAccount(account: AccountId?): StreamResolver = when {
        account == null -> contextResolver
        account.provider == Provider.JELLYFIN -> provider(account)?.streams ?: contextResolver
        else -> resolvers.getOrPut(account.value) { yandexStreams.forAccount(account) }
    }

    /** The signed-in accounts' providers, in sign-in order. */
    fun all(): List<MusicProvider> = authRepository.allAccounts.value.map { providerOf(it.accountId()) }

    /** The signed-in Jellyfin accounts' providers, in sign-in order. */
    fun jellyfin(): List<MusicProvider> = jellyfinAccounts.accounts.value.mapNotNull { provider(it.id) }

    /** [account]'s provider; null when it is not signed in. */
    fun provider(account: AccountId): MusicProvider? = when (account.provider) {
        Provider.JELLYFIN -> jellyfinProvider(account)
        else -> account.takeIf { id -> authRepository.allAccounts.value.any { it.accountId() == id } }?.let(::providerOf)
    }

    /** Drops [account]'s provider (sign-out): a later sign-in builds a fresh one. */
    fun forget(account: AccountId) {
        providers.remove(account.value)
        resolvers.remove(account.value)
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
                    // "Compatible" sound is MP3 from the server too; never more than the device mixer plays (D79)
                    profile = { JellyfinStreamProfile(lossless = !soundMode.isCompatible(), maxSampleRateHz = fixedOutput.mixerRateHz) }
                )
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
