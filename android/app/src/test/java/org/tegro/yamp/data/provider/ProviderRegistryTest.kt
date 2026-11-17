package org.tegro.yamp.data.provider

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.account.AccountContext
import org.tegro.yamp.data.provider.yandex.cache.PlaylistCacheManager
import org.tegro.yamp.data.network.AuthInterceptor
import org.tegro.yamp.data.network.TrackUrlResolver
import org.tegro.yamp.data.network.YandexClientsImpl
import org.tegro.yamp.data.player.fakes.FakeAuthRepository
import org.tegro.yamp.data.provider.yandex.YandexStreamResolver
import org.tegro.yamp.data.provider.yandex.repository.CatalogRepository
import org.tegro.yamp.data.provider.yandex.repository.LibraryRepository
import org.tegro.yamp.data.provider.yandex.repository.SearchRepository
import org.tegro.yamp.data.provider.yandex.repository.UserPlaylistsRepository
import org.tegro.yamp.di.DispatcherProvider
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.accountId
import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.ProviderError
import org.tegro.yamp.domain.queue.MediaRef

/**
 * Phase 3 step 10: one provider per signed-in account, each asking for its own account whatever account the
 * screens show; errors come back as [ProviderError], not exceptions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProviderRegistryTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val first = UserAccount(uid = "101", login = "first", displayName = "First", token = "token-first")
    private val second = UserAccount(uid = "202", login = "second", displayName = "Second", token = "token-second")

    /** Each request as the server saw it: "/search · token-second". */
    private val requests = mutableListOf<String>()

    private val server = Interceptor { chain ->
        val path = chain.request().url.encodedPath
        requests += "$path · ${chain.request().header("Authorization")?.removePrefix("OAuth ")}"
        when (path) {
            "/search" -> respond(chain, 200, """{"result":{"tracks":{"results":[{"id":"5","title":"Found"}]}}}""")
            "/users/202/likes/tracks" -> respond(chain, 200,
                """{"result":{"library":{"uid":0,"revision":1,"tracks":[{"id":"2","track":{"id":"2","title":"Second liked"}}]}}}""")
            else -> respond(chain, 404, """{"error":{"name":"not-found"}}""")
        }
    }

    private fun respond(chain: Interceptor.Chain, code: Int, body: String): Response = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    private val auth = FakeAuthRepository(first).apply { allAccounts.value = listOf(first, second) }
    private val clients = YandexClientsImpl(
        OkHttpClient.Builder().addInterceptor(AuthInterceptor { null }).addInterceptor(server).build(),
        json, auth, AccountContext(auth), context
    )
    private val cache = PlaylistCacheManager(context, json)
    private val library = LibraryRepository(clients, auth, cache)
    private val dispatchers = object : DispatcherProvider {
        override val main get() = Dispatchers.Unconfined
        override val io get() = Dispatchers.Unconfined
        override val default get() = Dispatchers.Unconfined
    }
    private val registry = ProviderRegistry(
        auth,
        YandexStreamResolver.Factory(NoLinks, dispatchers) { null },
        library,
        UserPlaylistsRepository(clients, auth, library, cache),
        SearchRepository(clients),
        CatalogRepository(clients)
    )

    @Test
    fun everySignedInAccountHasItsProviderInSignInOrder() {
        assertEquals(listOf(first.accountId(), second.accountId()), registry.all().map { it.account })
        assertSame(registry.provider(second.accountId()), registry.provider(second.accountId()))
        assertNull("not signed in", registry.provider(AccountId.yandex("999")))
    }

    @Test
    fun oneResolverPerAccount() {
        assertSame(registry.forAccount(second.accountId()), registry.forAccount(second.accountId()))
        assertSame(registry.provider(second.accountId())!!.streams, registry.forAccount(second.accountId()))
        assertTrue(registry.forAccount(first.accountId()) !== registry.forAccount(second.accountId()))
        assertNotNull("the context resolver while nobody is named", registry.forAccount(null))
    }

    @Test
    fun aProviderAsksForItsOwnAccountWhateverTheContext() = runBlocking {
        val provider = registry.provider(second.accountId())!!
        library.syncLikes(second.accountId())

        val found = (provider.search!!.search("q") as Outcome.Ok).value
        val liked = (provider.library!!.likedTracks() as Outcome.Ok).value

        assertEquals(listOf("Found"), found.tracks.map { it.title })
        assertEquals(listOf("Second liked"), liked.map { it.title })
        assertTrue(requests.toString(), requests.any { it == "/search · token-second" })
    }

    @Test
    fun aMissingAlbumIsNotFoundNotAnException() = runBlocking {
        val provider = registry.provider(first.accountId())!!

        val album = provider.catalog!!.album(MediaRef(MediaRef.PROVIDER_YANDEX, "album", "77"))

        assertEquals(Outcome.Err(ProviderError.NotFound), album)
    }

    @Test
    fun compositionShowsTheContextAccountOnlyAndNoBadgesWithOneProvider() = runBlocking {
        val composition = ProviderComposition(registry, AccountContext(auth))

        assertEquals("several Yandex accounts never merge (D56)", listOf(first.accountId()), composition.providers().map { it.account })
        val found = (composition.search("q") as Outcome.Ok).value

        assertEquals(listOf("Found"), found.tracks.map { it.item.title })
        assertEquals(listOf(first.accountId()), found.tracks.map { it.account })
        assertTrue("one provider: the screen looks as before (D75)", !found.showBadges)
        assertTrue(requests.toString(), requests.any { it == "/search · token-first" })
    }

    @Test
    fun compositionIsEmptyWhileNobodyIsSignedIn() = runBlocking {
        val nobody = FakeAuthRepository(null)
        val composition = ProviderComposition(
            ProviderRegistry(nobody, YandexStreamResolver.Factory(NoLinks, dispatchers) { null }, library,
                UserPlaylistsRepository(clients, nobody, library, cache), SearchRepository(clients), CatalogRepository(clients)),
            AccountContext(nobody)
        )

        assertTrue(composition.providers().isEmpty())
        assertEquals(Outcome.Err(ProviderError.NotFound), composition.search("q"))
    }

    /** The controller's link store is not used by these calls. */
    private object NoLinks : TrackUrlResolver {
        override fun qualityOf(trackId: String) = null
        override fun offeredOf(trackId: String): String? = null
        override fun getResolvedUrl(trackId: String, account: AccountId?): String? = null
        override suspend fun getOrFetchUrl(trackId: String, account: AccountId?): String? = null
        override suspend fun resolveOrdinaryStream(trackId: String, account: AccountId?) = null
        override fun playableCopy(trackId: String): java.io.File? = null
        override fun hasPlayableCopy(trackId: String) = false
        override fun evictUrl(trackId: String, account: AccountId?) = Unit
        override fun preloadTracks(trackIds: List<String>, account: AccountId?) = Unit
        override fun clearCache() = Unit
    }
}
