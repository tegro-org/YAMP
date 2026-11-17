package org.tegro.yamp.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.account.AccountContext
import org.tegro.yamp.data.provider.yandex.cache.PlaylistCacheManager
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.network.AuthInterceptor
import org.tegro.yamp.data.network.YandexClientsImpl
import org.tegro.yamp.domain.account.accountId
import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.domain.repository.AuthRepository
import org.tegro.yamp.data.provider.yandex.repository.LibraryRepository
import org.tegro.yamp.data.provider.yandex.repository.UserPlaylistsRepository

/**
 * Phase 2a step 4 (docs/phase-2a-accounts.md §4): likes and playlists are kept per account. A change of account
 * shows the other account's without dropping or reloading anything, and a call made for one account goes out
 * with that account's token whatever account the screens show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PerAccountLibraryTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val first = UserAccount(uid = "101", login = "first", displayName = "First", token = "token-first")
    private val second = UserAccount(uid = "202", login = "second", displayName = "Second", token = "token-second")

    /** Each request as the server saw it: "GET /users/101/likes/tracks · token-first". */
    private val requests = mutableListOf<String>()

    private val server = Interceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath
        val token = request.header("Authorization")?.removePrefix("OAuth ")
        requests += "${request.method} $path · $token"
        val owner = listOf(first, second).firstOrNull { path.startsWith("/users/${it.uid}/") }
        when {
            owner != null && owner.token != token -> respond(chain, 401, """{"error":{"name":"wrong-token"}}""")
            path == "/users/101/likes/tracks" -> respond(chain, 200, likes("1", "First liked"))
            path == "/users/202/likes/tracks" -> respond(chain, 200, likes("2", "Second liked"))
            path.endsWith("/likes/tracks/add-multiple") -> respond(chain, 200, """{"result":{"revision":2}}""")
            path == "/users/101/playlists/list" -> respond(chain, 200, playlists("11", "First's playlist"))
            path == "/users/202/playlists/list" -> respond(chain, 200, playlists("22", "Second's playlist"))
            else -> respond(chain, 404, """{"error":{"name":"not-found"}}""")
        }
    }

    private fun likes(id: String, title: String) =
        """{"result":{"library":{"uid":0,"revision":1,"tracks":[{"id":"$id","track":{"id":"$id","title":"$title"}}]}}}"""

    private fun playlists(kind: String, title: String) =
        """{"result":[{"kind":$kind,"title":"$title","trackCount":0,"revision":1}]}"""

    private fun respond(chain: Interceptor.Chain, code: Int, body: String): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private val auth = TwoAccounts(first, second)
    private val accountContext = AccountContext(auth)

    /** The real per-account clients over a shared client whose own interceptor sends no token. */
    private val clients = YandexClientsImpl(
        OkHttpClient.Builder().addInterceptor(AuthInterceptor { null }).addInterceptor(server).build(),
        json,
        auth,
        accountContext,
        context
    )
    private val cache = PlaylistCacheManager(context, json)
    private val library = LibraryRepository(clients, auth, cache)
    private val playlists = UserPlaylistsRepository(clients, auth, library, cache)

    @org.junit.Before
    fun startClean() = runBlocking {
        // The cache database outlives a test: what an earlier test saved would make a sync "unchanged"
        listOf(first, second).forEach { cache.clearUserCache(it.accountId().value) }
    }

    private suspend fun showAccount(account: UserAccount) {
        auth.switchAccount(account.uid)
        library.loadCached()
        playlists.loadCached()
    }

    @Test
    fun switchingBackShowsTheFirstAccountsLikesWithoutAskingTheServer() = runBlocking {
        showAccount(first)
        assertTrue(library.syncLikes())
        assertEquals(setOf("1"), library.likedIds.value)

        showAccount(second)
        assertTrue(library.syncLikes())
        assertEquals(setOf("2"), library.likedIds.value)

        val asked = requests.size
        showAccount(first)
        assertEquals(setOf("1"), library.likedIds.value)
        assertEquals("a switch only shows what is held", asked, requests.size)
    }

    @Test
    fun aSyncForAnotherAccountUsesItsTokenAndLeavesTheShownLikesAlone() = runBlocking {
        showAccount(first)
        library.syncLikes()

        assertTrue(library.syncLikes(second.accountId()))

        assertTrue(requests.contains("GET /users/202/likes/tracks · token-second"))
        assertEquals("the screens still show the first account's", setOf("1"), library.likedIds.value)
        showAccount(second)
        assertEquals(setOf("2"), library.likedIds.value)
    }

    @Test
    fun aLikeForAnotherAccountGoesToThatAccountOnly() = runBlocking {
        showAccount(first)
        library.syncLikes()
        library.syncLikes(second.accountId())

        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        library.setLiked(TrackDetailsDto(id = kotlinx.serialization.json.JsonPrimitive("9")), true, second.accountId()) { done.complete(it) }
        assertTrue(done.await())

        assertTrue(requests.contains("POST /users/202/likes/tracks/add-multiple · token-second"))
        assertTrue(requests.none { it.startsWith("POST /users/101/") })
        assertEquals(setOf("1"), library.likedIds.value)
        showAccount(second)
        assertEquals(setOf("9", "2"), library.likedIds.value)
    }

    @Test
    fun eachAccountKeepsItsOwnPlaylists() = runBlocking {
        showAccount(first)
        assertTrue(playlists.refresh())
        showAccount(second)
        assertTrue(playlists.refresh())
        assertEquals(listOf("22"), playlists.playlists.value.map { it.kindString })

        val asked = requests.size
        showAccount(first)
        assertEquals(listOf("11"), playlists.playlists.value.map { it.kindString })
        assertEquals(asked, requests.size)
        assertTrue(requests.contains("GET /users/202/playlists/list · token-second"))
    }

    /** Two signed-in accounts; [switchAccount] changes which one the screens show, nothing else. */
    private class TwoAccounts(vararg accounts: UserAccount) : AuthRepository {
        override val allAccounts = MutableStateFlow(accounts.toList())
        override val activeAccount = MutableStateFlow<UserAccount?>(accounts.first())
        override val defaultAccountUid = MutableStateFlow<String?>(null)
        override fun getActiveAccount() = activeAccount.value
        override fun getAllAccounts() = allAccounts.value
        override fun getToken() = activeAccount.value?.token
        override fun isAuthorized() = flowOf(true)
        override fun getDefaultAccountUid(): String? = null
        override fun saveToken(token: String) = Unit
        override fun clearToken() = Unit
        override fun saveOrUpdateAccount(account: UserAccount) = Unit
        override fun switchAccount(uid: String): Boolean {
            activeAccount.value = allAccounts.value.firstOrNull { it.uid == uid } ?: return false
            return true
        }
        override fun removeAccount(uid: String): UserAccount? = null
        override fun clearAllAccounts() = Unit
        override fun setDefaultAccountUid(uid: String?) = Unit
    }
}
