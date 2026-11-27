package org.tegro.yamp.data.provider.yandex

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.network.TrackPreloadManager
import org.tegro.yamp.data.network.TrackUrlResolver
import org.tegro.yamp.di.DispatcherProvider
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.provider.Decryption
import org.tegro.yamp.domain.provider.MidTrackAction
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.ProviderError
import org.tegro.yamp.domain.provider.ResolvedStream
import org.tegro.yamp.domain.provider.StreamQuality
import org.tegro.yamp.domain.queue.TrackRef
import java.io.File

/** Yandex streams in neutral terms (phase 3 step 4, D74): copy first, then a fresh link, then the network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class YandexStreamResolverTest {

    private val links = Links()
    /** What the on-disk stream cache holds in full, by key prefix (D86). */
    private val onDisk = mutableMapOf<String, ResolvedStream>()
    private val account = AccountId("yandex:42")
    private val dispatchers = object : DispatcherProvider {
        override val main get() = Dispatchers.Unconfined
        override val io get() = Dispatchers.Unconfined
        override val default get() = Dispatchers.Unconfined
    }

    /** One resolver per account (phase 3 step 10). */
    private fun resolverOf(of: AccountId) = YandexStreamResolver(links, dispatchers, { prefix -> onDisk[prefix] }, of)
    private val track = TrackRef.yandex("100")
    private val resolver by lazy { resolverOf(account) }

    private fun resolve(ref: TrackRef = track): Outcome<ResolvedStream> = runBlocking { resolver.resolve(ref) }

    @Test
    fun aMidTrackFailureIsClassifiedByTheServersAnswer() {
        assertEquals(MidTrackAction.RENEW, resolver.midTrackAction(track, 401))
        assertEquals(MidTrackAction.RENEW, resolver.midTrackAction(track, 403))
        assertEquals(MidTrackAction.UNAVAILABLE, resolver.midTrackAction(track, 404))
        assertEquals(MidTrackAction.UNAVAILABLE, resolver.midTrackAction(track, 410))
        assertEquals(MidTrackAction.RESTART, resolver.midTrackAction(track, null))
        assertEquals(MidTrackAction.RESTART, resolver.midTrackAction(track, 503))
    }

    @Test
    fun aCopyOnTheDeviceComesFirst() {
        links.copy = File("/data/music/100.mp3")
        links.fresh["100"] = "https://stream.example/100.mp3"

        val stream = (resolve() as Outcome.Ok).value

        assertTrue(stream.localFile)
        assertEquals("file:///data/music/100.mp3", stream.uri)
        assertTrue("no network for a copy", links.fetched.isEmpty())
    }

    @Test
    fun aFreshLinkIsHandedWithoutTheNetwork() {
        links.fresh["100"] = "https://stream.example/100.flac"
        links.quality = TrackPreloadManager.StreamQuality("flac", 1411)

        assertEquals(
            ResolvedStream(
                "https://stream.example/100.flac",
                quality = StreamQuality("flac", 1411),
                cacheKey = "yandex:42|100|flac-1411"
            ),
            resolver.cached(track)
        )
        assertTrue(links.fetched.isEmpty())
    }

    @Test
    fun withoutAFreshLinkTheNetworkIsAskedForTheAccount() {
        links.fetchable["100"] = "https://stream.example/100.mp3#encraw_key=00ff"

        val stream = (resolve() as Outcome.Ok).value

        assertEquals(listOf("100" to account), links.fetched)
        // The encraw key stays in the fragment, where the data source reads it (A3)
        assertEquals("https://stream.example/100.mp3#encraw_key=00ff", stream.uri)
        assertEquals(Decryption("encraw"), stream.decryption)
    }

    @Test
    fun noLinkIsAnErrorNotAnEmptyStream() {
        assertEquals(Outcome.Err(ProviderError.Unavailable("no stream link")), resolve())
        assertNull(resolver.cached(track))
    }

    @Test
    fun theCacheKeyIsTheSameForAnyLinkOfOneAccountTrackAndQuality() {
        links.quality = TrackPreloadManager.StreamQuality("mp3", 320)
        links.fresh["100"] = "https://a.example/100.mp3?sign=1"
        val first = resolver.cached(track)!!.cacheKey
        links.fresh["100"] = "https://b.example/100.mp3?sign=2"

        assertEquals(first, resolver.cached(track)!!.cacheKey)
        assertEquals("other account, other key", false, first == resolverOf(AccountId("yandex:7")).cached(track)!!.cacheKey)
    }

    @Test
    fun withNoLinkATrackWhoseBytesAreOnDiskStillPlays() {
        val kept = ResolvedStream("https://old.example/100.mp3", cacheKey = "yandex:42|100|mp3-320")
        onDisk["yandex:42|100|"] = kept

        assertEquals(Outcome.Ok(kept), resolve())
    }

    @Test
    fun aRadioStationIsNotResolvedHere() {
        assertEquals(Outcome.Err(ProviderError.NotFound), resolve(TrackRef.yandex("radio_7")))
        assertTrue(links.fetched.isEmpty())
    }

    @Test
    fun invalidatingAStreamForgetsItsLinkForThatAccount() {
        resolver.invalidate(track)

        assertEquals(listOf("100" to account), links.evicted)
    }

    private class Links : TrackUrlResolver {
        var copy: File? = null
        var quality: TrackPreloadManager.StreamQuality? = null
        val fresh = mutableMapOf<String, String>()
        val fetchable = mutableMapOf<String, String>()
        val fetched = mutableListOf<Pair<String, AccountId?>>()
        val evicted = mutableListOf<Pair<String, AccountId?>>()

        override fun qualityOf(trackId: String) = quality
        override fun offeredOf(trackId: String): String? = null
        override fun getResolvedUrl(trackId: String, account: AccountId?) = fresh[trackId]
        override suspend fun getOrFetchUrl(trackId: String, account: AccountId?): String? {
            fetched += trackId to account
            return fetchable[trackId]
        }
        override suspend fun resolveOrdinaryStream(trackId: String, account: AccountId?): TrackPreloadManager.ResolvedStream? = null
        override fun playableCopy(trackId: String) = copy
        override fun hasPlayableCopy(trackId: String) = copy != null
        override fun evictUrl(trackId: String, account: AccountId?) {
            evicted += trackId to account
        }
        override fun preloadTracks(trackIds: List<String>, account: AccountId?) = Unit
        override fun clearCache() = Unit
    }
}
