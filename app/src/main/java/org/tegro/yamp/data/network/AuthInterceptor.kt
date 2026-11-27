package org.tegro.yamp.data.network

import org.tegro.yamp.domain.repository.AuthRepository
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Adds the Yandex client headers and the OAuth token that [token] gives at request time. The shared client's
 * one follows the active account; each per-account client (`YandexClients`) gets its own, bound to its account.
 */
class AuthInterceptor(private val token: () -> String?) : Interceptor {

    @Inject constructor(authRepository: AuthRepository) : this({ authRepository.getToken() })

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()
        if (originalRequest.header("User-Agent") == null) {
            requestBuilder.header("User-Agent", "YandexMusic/2024.01.0 (Android)")
        }
        if (originalRequest.header("X-Yandex-Music-Client") == null) {
            requestBuilder.header("X-Yandex-Music-Client", "YandexMusicAndroid/2024.01.0")
        }

        // The token is for Yandex alone: whatever address a request of this client gets, another host never sees it
        val oauth = if (isYandexHost(originalRequest.url.host)) token() else null
        if (!oauth.isNullOrEmpty()) {
            requestBuilder.header("Authorization", "OAuth $oauth")
        }

        return chain.proceed(requestBuilder.build())
    }

    companion object {
        private val YANDEX_DOMAINS = listOf("yandex.net", "yandex.ru", "yandex.com", "ya.ru")

        /** "api.music.yandex.net", "storage.mds.yandex.net"… but not "yandex.net.example.com". */
        fun isYandexHost(host: String): Boolean {
            val h = host.lowercase()
            return YANDEX_DOMAINS.any { h == it || h.endsWith(".$it") }
        }
    }
}
