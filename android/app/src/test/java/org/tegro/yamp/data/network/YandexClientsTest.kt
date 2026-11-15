package org.tegro.yamp.data.network

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.tegro.yamp.data.account.AccountContext
import org.tegro.yamp.data.player.fakes.FakeAuthRepository
import org.tegro.yamp.di.NetworkModule
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.model.UserAccount

/** Phase 2a step 2 (D60): each account's calls carry that account's token, and only that one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class YandexClientsTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val first = UserAccount(uid = "1001", login = "driver", token = "token-1")
    private val second = UserAccount(uid = "2002", login = "rock", token = "token-2")
    private val auth = FakeAuthRepository(first).apply { allAccounts.value = listOf(first, second) }

    private val sent = mutableListOf<Request>()

    /** The shared client as the app builds it, answering instead of the network after every interceptor ran. */
    private val shared: OkHttpClient = NetworkModule.provideOkHttpClient(AuthInterceptor(auth), context)
        .newBuilder()
        .addInterceptor { chain ->
            sent += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private val clients = YandexClientsImpl(shared, NetworkModule.provideJson(), auth, AccountContext(auth), context)

    private fun authorizationOf(api: YandexMusicApi): String? {
        runBlocking { runCatching { api.getAccountStatus() } }
        return sent.last().header("Authorization")
    }

    @Test
    fun eachAccountSendsItsOwnToken() {
        assertEquals("OAuth token-1", authorizationOf(clients.api(AccountId.yandex("1001"))))
        assertEquals("OAuth token-2", authorizationOf(clients.api(AccountId.yandex("2002"))))
    }

    @Test
    fun nobodySignedInSendsNoToken() {
        assertNull(authorizationOf(clients.api(null)))
        assertNull(authorizationOf(clients.api(AccountId.yandex("9999"))))
    }

    @Test
    fun theContextFollowsTheActiveAccountAsBefore() {
        assertEquals("OAuth token-1", authorizationOf(clients.current()))
        auth.activeAccount.value = second
        assertEquals("OAuth token-2", authorizationOf(clients.current()))
    }

    @Test
    fun oneClientPerAccountWithItsOwnCache() {
        assertSame(clients.api(AccountId.yandex("1001")), clients.api(AccountId.yandex("1001")))
        assertEquals("yandex_1001", YandexClientsImpl.cacheDirName(AccountId.yandex("1001")))
        assertEquals("none", YandexClientsImpl.cacheDirName(null))
    }

    @Test
    fun theClientHeadersStayOnEveryCall() {
        authorizationOf(clients.api(AccountId.yandex("2002")))
        assertEquals("YandexMusicAndroid/2024.01.0", sent.last().header("X-Yandex-Music-Client"))
    }
}
