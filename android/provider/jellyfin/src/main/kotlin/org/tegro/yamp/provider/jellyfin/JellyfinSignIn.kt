package org.tegro.yamp.provider.jellyfin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import java.io.IOException

/** The server behind an address, as the sign-in form shows it (docs/phase-3-providers.md §5). */
sealed interface ServerCheck {
    /** A Jellyfin server: [url] normalised, [id] is what accounts and refs are built on (D78). */
    data class Found(val url: String, val id: String, val name: String, val version: String, val quickConnect: Boolean) : ServerCheck

    /** Not a usable address: empty, not a URL, or plain `http://` where only `https://` is allowed. */
    data class BadAddress(val reason: Reason) : ServerCheck {
        enum class Reason { EMPTY, INVALID, NOT_HTTPS }
    }

    /** Nothing answered: wrong address, the server is down, or no network. */
    data object Unreachable : ServerCheck

    /** Something answered, but not as Jellyfin does. */
    data object NotJellyfin : ServerCheck
}

/** A completed sign-in: who on which server, and the access token the server issued (the password is not kept). */
data class SignedIn(
    val serverUrl: String,
    val serverId: String,
    val serverName: String,
    val serverVersion: String,
    val userId: String,
    val userName: String,
    val token: String
)

/** Why a sign-in did not complete. */
sealed interface SignInError {
    /** The server refused the name and password (401/403). */
    data object WrongCredentials : SignInError

    /** The Quick Connect code was not confirmed in time, or the server dropped it. */
    data object QuickConnectExpired : SignInError

    data object Unreachable : SignInError
}

class SignInFailed(val error: SignInError, cause: Throwable? = null) : Exception(error.toString(), cause)

/**
 * The Jellyfin sign-in as a flow of plain calls, without UI or storage (docs/phase-3-providers.md §5, step 21):
 * check the address, then name + password or Quick Connect. [api] makes an unauthenticated client for a server
 * address; [allowHttp] lets a debug build reach a local test server over `http://`.
 */
class JellyfinSignIn(
    private val api: (serverUrl: String) -> JellyfinApi,
    private val allowHttp: (host: String) -> Boolean = { false },
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val quickConnectTimeoutMs: Long = QUICK_CONNECT_TIMEOUT_MS
) {
    /** The address the user typed, normalised: `https://` added when no scheme is given, trailing slashes dropped. */
    fun normalise(typed: String): String? {
        val text = typed.trim()
        if (text.isEmpty()) return null
        val withScheme = (if (text.contains("://")) text else "https://$text").trimEnd('/')
        val url = withScheme.toHttpUrlOrNull() ?: return null
        return withScheme.takeIf { url.host.isNotBlank() && withScheme.substringAfter("://").isNotBlank() }
    }

    suspend fun check(typed: String): ServerCheck {
        if (typed.isBlank()) return ServerCheck.BadAddress(ServerCheck.BadAddress.Reason.EMPTY)
        val url = normalise(typed) ?: return ServerCheck.BadAddress(ServerCheck.BadAddress.Reason.INVALID)
        val parsed = url.toHttpUrlOrNull() ?: return ServerCheck.BadAddress(ServerCheck.BadAddress.Reason.INVALID)
        if (!parsed.isHttps && !allowHttp(parsed.host)) return ServerCheck.BadAddress(ServerCheck.BadAddress.Reason.NOT_HTTPS)
        val client = api(url)
        val info = try {
            client.publicSystemInfo()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            return ServerCheck.Unreachable
        } catch (_: HttpException) {
            return ServerCheck.NotJellyfin
        } catch (_: SerializationException) {
            return ServerCheck.NotJellyfin
        } catch (_: IllegalArgumentException) {
            return ServerCheck.NotJellyfin
        }
        val product = info.productName.orEmpty()
        if (info.id.isBlank() || (product.isNotEmpty() && !product.contains("Jellyfin", ignoreCase = true))) {
            return ServerCheck.NotJellyfin
        }
        val quickConnect = runCatchingIo { client.quickConnectEnabled() } ?: false
        return ServerCheck.Found(
            url = url,
            id = info.id,
            name = info.serverName?.takeIf { it.isNotBlank() } ?: parsed.host,
            version = info.version.orEmpty(),
            quickConnect = quickConnect
        )
    }

    /** Name and password: the password goes to the server once and is not kept (§5). */
    suspend fun withPassword(server: ServerCheck.Found, user: String, password: String): SignedIn =
        signedIn(server) { authenticateByName(AuthenticateByNameDto(user.trim(), password)) }

    /** Starts Quick Connect: the code to show and the secret to wait on. */
    suspend fun startQuickConnect(server: ServerCheck.Found): QuickConnectResultDto = call { api(server.url).quickConnectInitiate() }

    /**
     * Waits until the code is confirmed in Jellyfin, then signs in with it. Polls every [pollIntervalMs]; gives up
     * after [quickConnectTimeoutMs] with [SignInError.QuickConnectExpired]. Cancelling the coroutine stops it.
     */
    suspend fun awaitQuickConnect(server: ServerCheck.Found, secret: String): SignedIn {
        val client = api(server.url)
        var waited = 0L
        while (approved(client, secret) != true) {
            if (waited >= quickConnectTimeoutMs) throw SignInFailed(SignInError.QuickConnectExpired)
            delay(pollIntervalMs)
            waited += pollIntervalMs
        }
        return signedIn(server) { authenticateWithQuickConnect(QuickConnectAuthDto(secret)) }
    }

    /** One poll: true once confirmed; null when the network failed this time (the next poll tries again). */
    private suspend fun approved(client: JellyfinApi, secret: String): Boolean? = try {
        client.quickConnectState(secret).authenticated
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        // An unknown or expired secret
        throw SignInFailed(if (e.code() in 400..499) SignInError.QuickConnectExpired else SignInError.Unreachable, e)
    } catch (_: IOException) {
        null
    }

    private suspend fun signedIn(server: ServerCheck.Found, request: suspend JellyfinApi.() -> AuthenticationResultDto): SignedIn {
        val result = call { api(server.url).request() }
        return SignedIn(
            serverUrl = server.url,
            serverId = result.serverId.ifBlank { server.id },
            serverName = server.name,
            serverVersion = server.version,
            userId = result.user.id,
            userName = result.user.name.orEmpty(),
            token = result.accessToken
        )
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        throw SignInFailed(if (e.code() == 401 || e.code() == 403) SignInError.WrongCredentials else SignInError.Unreachable, e)
    } catch (e: IOException) {
        throw SignInFailed(SignInError.Unreachable, e)
    }

    private suspend fun <T> runCatchingIo(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        null
    } catch (_: HttpException) {
        null
    } catch (_: SerializationException) {
        null
    }

    companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val QUICK_CONNECT_TIMEOUT_MS = 5 * 60_000L
    }
}
