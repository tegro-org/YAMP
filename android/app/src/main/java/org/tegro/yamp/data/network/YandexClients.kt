package org.tegro.yamp.data.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.tegro.yamp.data.account.AccountContext
import org.tegro.yamp.di.NetworkModule
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.repository.AuthRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One Yandex API per account (docs/phase-2a-accounts.md §3, D60): every call says whose it is, and only that
 * account's token and network cache are used for it. With one account everything resolves to it.
 */
interface YandexClients {
    /** The API that sends [account]'s token; `null` sends none (nobody signed in). */
    fun api(account: AccountId?): YandexMusicApi

    /** The API of the account context ([AccountContext]) — for calls that don't belong to a queue. */
    fun current(): YandexMusicApi

    /** [account]'s HTTP client, for the raw requests beside the API (a stream's download-info XML). */
    fun http(account: AccountId?): OkHttpClient

    /** Empties every account's network cache. */
    fun evictCaches()

    /** Sign-out (D62): [account]'s client goes, and its network cache with its directory. */
    fun forget(account: AccountId) = Unit
}

/**
 * Clients made lazily from the shared one ([NetworkModule.provideOkHttpClient]): same connection pool, dispatcher,
 * timeouts and logging, but an [AuthInterceptor] bound to the account and a cache of its own in
 * `http_cache_accounts/<id>` — beside, not inside, the shared `http_cache`, whose eviction deletes its directory.
 */
@Singleton
class YandexClientsImpl @Inject constructor(
    private val shared: OkHttpClient,
    private val json: Json,
    private val authRepository: AuthRepository,
    private val accountContext: AccountContext,
    @ApplicationContext private val context: Context
) : YandexClients {

    private class Client(val http: OkHttpClient, val api: YandexMusicApi)

    private val clients = ConcurrentHashMap<String, Client>()

    override fun api(account: AccountId?): YandexMusicApi =
        clients.computeIfAbsent(account?.value ?: SIGNED_OUT) { build(account) }.api

    override fun current(): YandexMusicApi = api(accountContext.current())

    override fun http(account: AccountId?): OkHttpClient =
        clients.computeIfAbsent(account?.value ?: SIGNED_OUT) { build(account) }.http

    override fun evictCaches() {
        clients.values.forEach { client -> client.http.cache?.let { runCatching { it.evictAll() } } }
    }

    override fun forget(account: AccountId) {
        clients.remove(account.value)?.http?.cache?.let { cache -> runCatching { cache.delete() } }
        // Also when no client was made in this run: the directory of an earlier one
        File(context.cacheDir, "$CACHE_ROOT/${cacheDirName(account)}").deleteRecursively()
    }

    private fun build(account: AccountId?): Client {
        val auth = AuthInterceptor { account?.let { authRepository.token(it) } }
        val http = shared.newBuilder()
            .apply { interceptors().replaceAll { if (it is AuthInterceptor) auth else it } }
            .cache(Cache(File(context.cacheDir, "$CACHE_ROOT/${cacheDirName(account)}"), CACHE_BYTES))
            .build()
        return Client(http, NetworkModule.yandexRetrofit(http, json).create(YandexMusicApi::class.java))
    }

    companion object {
        private const val SIGNED_OUT = "none"
        private const val CACHE_ROOT = "http_cache_accounts"
        private const val CACHE_BYTES = 20L * 1024 * 1024

        /** A directory name from the account id: "yandex:123" → "yandex_123"; nobody signed in → "none". */
        internal fun cacheDirName(account: AccountId?): String =
            account?.value?.replace(Regex("[^A-Za-z0-9_-]"), "_") ?: SIGNED_OUT
    }
}
