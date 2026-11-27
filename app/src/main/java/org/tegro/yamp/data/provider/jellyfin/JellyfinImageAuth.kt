package org.tegro.yamp.data.provider.jellyfin

import okhttp3.Interceptor
import okhttp3.Response
import org.tegro.yamp.provider.jellyfin.JellyfinApiFactory
import org.tegro.yamp.provider.jellyfin.JellyfinAuthHeader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Covers of a Jellyfin server (docs/phase-3-providers.md step 23, D79): the image loader's requests to a signed-in
 * account's server get that account's `Authorization` header — and only those (C6). Every other image host (Yandex
 * covers, radio logos on third-party sites) gets nothing. The token is read per request, so a sign-out stops it at
 * once.
 */
@Singleton
class JellyfinImageAuth @Inject constructor(
    private val store: JellyfinAccountStore,
    private val clients: JellyfinClients
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val account = store.accounts.value.firstOrNull { account ->
            val server = runCatching { JellyfinApiFactory.baseUrl(account.serverUrl) }.getOrNull() ?: return@firstOrNull false
            server.host == url.host && server.port == url.port
        } ?: return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header(JellyfinAuthHeader.NAME, JellyfinAuthHeader.value(clients.info, account.token)).build()
        )
    }
}
