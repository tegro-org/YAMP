package org.tegro.yamp.data.provider.jellyfin

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.tegro.yamp.BuildConfig
import org.tegro.yamp.data.network.YandexClientsImpl
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.provider.jellyfin.JellyfinApi
import org.tegro.yamp.provider.jellyfin.JellyfinApiFactory
import org.tegro.yamp.provider.jellyfin.JellyfinClientInfo
import org.tegro.yamp.provider.jellyfin.JellyfinSignIn
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Jellyfin networking (docs/phase-3-providers.md C6, D78): one client per signed-in account, built on the providers'
 * shared base — same pool and timeouts — with the server's own auth header (for its host only) and an HTTP cache of
 * its own beside the Yandex ones (`http_cache_accounts/jellyfin_<server>_<user>`); plus unauthenticated clients for
 * the sign-in form.
 */
@Singleton
class JellyfinClients @Inject constructor(
    @org.tegro.yamp.di.ProviderBaseClient private val shared: OkHttpClient,
    private val store: JellyfinAccountStore,
    @ApplicationContext private val context: Context
) {
    private val clients = ConcurrentHashMap<String, JellyfinApi>()

    /** Who is calling, as the server lists the session; the device id stays the same for this installation. */
    val info: JellyfinClientInfo by lazy {
        JellyfinClientInfo(client = "YAMP", device = Build.MODEL.ifBlank { "Android" }, deviceId = deviceId(), version = BuildConfig.VERSION_NAME)
    }

    /** [account]'s client; null when it is not signed in. The token is read per request. */
    fun api(account: AccountId): JellyfinApi? {
        val stored = store.account(account) ?: return null
        return clients.computeIfAbsent(account.value) {
            val http = shared.newBuilder()
                .cache(Cache(cacheDir(account), CACHE_BYTES))
                .build()
            JellyfinApiFactory.create(http, stored.serverUrl, info) { store.token(account) }
        }
    }

    /**
     * The client a download fetches a file through (gap b): the shared base without the HTTP cache (a whole file does
     * not belong there) and with redirects off — the auth header goes on that one request and never to another host.
     */
    val downloads: OkHttpClient by lazy { shared.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    /** The sign-in flow over unauthenticated clients; plain http only to a local test server, and only in debug builds. */
    fun signIn(): JellyfinSignIn = JellyfinSignIn(
        api = { url -> JellyfinApiFactory.create(shared, url, info) { null } },
        allowHttp = ::cleartextAllowed
    )

    /** Drops [account]'s client and its HTTP cache (sign-out, D62). */
    fun forget(account: AccountId) {
        clients.remove(account.value)
        cacheDir(account).deleteRecursively()
    }

    private fun cacheDir(account: AccountId) = File(context.cacheDir, "$CACHE_ROOT/${YandexClientsImpl.cacheDirName(account)}")

    private fun deviceId(): String {
        val prefs = context.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_DEVICE_ID, id) }
        return id
    }

    companion object {
        private const val CACHE_ROOT = "http_cache_accounts"
        private const val CACHE_BYTES = 20L * 1024 * 1024
        private const val DEVICE_PREFS = "jellyfin_device"
        private const val KEY_DEVICE_ID = "device_id"
        private val LOCAL_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")

        /** Debug builds may reach a local test server (`scripts/jellyfin-dev/`) over http; release builds never. */
        fun cleartextAllowed(host: String): Boolean = BuildConfig.DEBUG && host in LOCAL_HOSTS
    }
}
