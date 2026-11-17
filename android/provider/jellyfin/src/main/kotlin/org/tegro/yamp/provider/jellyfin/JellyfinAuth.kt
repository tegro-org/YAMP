package org.tegro.yamp.provider.jellyfin

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Who is calling, as Jellyfin's `Authorization: MediaBrowser …` header names it. [deviceId] must stay the same for
 * one installation: the server lists sessions and tokens per device.
 */
data class JellyfinClientInfo(
    val client: String,
    val device: String,
    val deviceId: String,
    val version: String
)

/** Builds the `MediaBrowser` authorization value; [token] is added once signed in (D79: the token goes in a header). */
object JellyfinAuthHeader {
    const val NAME = "Authorization"

    fun value(info: JellyfinClientInfo, token: String?): String = buildString {
        append("MediaBrowser ")
        append(field("Client", info.client)).append(", ")
        append(field("Device", info.device)).append(", ")
        append(field("DeviceId", info.deviceId)).append(", ")
        append(field("Version", info.version))
        if (!token.isNullOrEmpty()) append(", ").append(field("Token", token))
    }

    // Values are quoted; a quote or backslash inside one would end it early, so they are dropped.
    private fun field(name: String, value: String) = "$name=\"${value.filterNot { it == '"' || it == '\\' }}\""
}

/**
 * Adds the authorization header to requests for [server]'s host and port only (C6: no interceptor adds credentials
 * for a host it does not own — covers or redirects elsewhere get nothing). [token] is read per request, so a
 * sign-in or sign-out takes effect without rebuilding the client.
 */
class JellyfinAuthInterceptor(
    private val server: HttpUrl,
    private val info: JellyfinClientInfo,
    private val token: () -> String?
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != server.host || request.url.port != server.port) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header(JellyfinAuthHeader.NAME, JellyfinAuthHeader.value(info, token())).build()
        )
    }
}
