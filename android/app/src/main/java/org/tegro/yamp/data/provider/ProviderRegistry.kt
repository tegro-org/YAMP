package org.tegro.yamp.data.provider

import org.tegro.yamp.data.provider.yandex.YandexProvider
import org.tegro.yamp.data.provider.yandex.YandexStreamResolver
import org.tegro.yamp.data.provider.yandex.repository.CatalogRepository
import org.tegro.yamp.data.provider.yandex.repository.LibraryRepository
import org.tegro.yamp.data.provider.yandex.repository.SearchRepository
import org.tegro.yamp.data.provider.yandex.repository.UserPlaylistsRepository
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.accountId
import org.tegro.yamp.domain.provider.MusicProvider
import org.tegro.yamp.domain.provider.StreamResolver
import org.tegro.yamp.domain.repository.AuthRepository
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
 * stable provider order of D75. Yandex only until Phase 4 adds Jellyfin accounts here. A queue's entries always
 * resolve through the queue's account (D84).
 */
@Singleton
class ProviderRegistry @Inject constructor(
    private val authRepository: AuthRepository,
    private val yandexStreams: YandexStreamResolver.Factory,
    private val libraryRepository: LibraryRepository,
    private val playlistsRepository: UserPlaylistsRepository,
    private val searchRepository: SearchRepository,
    private val catalogRepository: CatalogRepository
) : StreamResolvers {

    private val resolvers = ConcurrentHashMap<String, StreamResolver>()
    private val providers = ConcurrentHashMap<String, MusicProvider>()
    private val contextResolver: StreamResolver by lazy { yandexStreams.forAccount(null) }

    override fun forAccount(account: AccountId?): StreamResolver =
        account?.let { resolvers.getOrPut(it.value) { yandexStreams.forAccount(it) } } ?: contextResolver

    /** The signed-in accounts' providers, in sign-in order. */
    fun all(): List<MusicProvider> = authRepository.allAccounts.value.map { providerOf(it.accountId()) }

    /** [account]'s provider; null when it is not signed in. */
    fun provider(account: AccountId): MusicProvider? =
        account.takeIf { id -> authRepository.allAccounts.value.any { it.accountId() == id } }?.let(::providerOf)

    private fun providerOf(account: AccountId): MusicProvider = providers.getOrPut(account.value) {
        YandexProvider(
            account, forAccount(account),
            libraryRepository, playlistsRepository, searchRepository, catalogRepository
        )
    }
}
