package org.tegro.yamp.data.download

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.data.provider.jellyfin.JellyfinOnDevice
import org.tegro.yamp.domain.account.AccountId
import java.io.File

/** Gap (b): Jellyfin downloads — the auth header on that request only, the FLAC mark, and who a download is for. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class JellyfinDownloadsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val requests = mutableListOf<Request>()
    private var status = 200
    private var type = "audio/flac"
    private var bytes = ByteArray(4096) { it.toByte() }

    private val server = Interceptor { chain ->
        requests += chain.request()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("")
            .apply { if (status == 302) header("Location", "https://elsewhere.example/file") }
            .body(bytes.toResponseBody(type.toMediaType())).build()
    }

    /** The app's shared client: never given a Jellyfin header. */
    private val shared = OkHttpClient.Builder().addInterceptor { chain ->
        error("the shared client fetched ${chain.request().url}")
    }.build()
    private val jellyfinHttp = OkHttpClient.Builder().addInterceptor(server).build()
    private val home = AccountId.jellyfin("srv1", "u1")
    private val partner = AccountId.jellyfin("srv1", "u2")
    private val auth = mapOf("Authorization" to "MediaBrowser Token=\"t0k\"")
    private val url = "http://10.0.2.2:8096/Audio/item42/universal?UserId=u1"

    @Before
    fun clean() {
        File(context.filesDir, "tracks").deleteRecursively()
    }

    private fun downloads() = TrackDownloadManager(context, shared)

    @Test
    fun theAuthHeaderGoesWithTheDownloadThroughTheAccountsClientOnly() = runBlocking {
        val downloads = downloads()
        assertTrue(downloads.downloadTrack("item42", url, headers = auth, http = jellyfinHttp, owner = home))

        assertEquals(1, requests.size)
        assertEquals(url, requests.single().url.toString())
        assertEquals("MediaBrowser Token=\"t0k\"", requests.single().header("Authorization"))
        assertTrue(downloads.isKnownDownloaded("item42"))
    }

    @Test
    fun aRedirectIsNeverFollowedAndSavesNothing() = runBlocking {
        status = 302
        val downloads = downloads()
        assertFalse(downloads.downloadTrack("item42", url, headers = auth, http = jellyfinHttp, owner = home))

        assertEquals("only the server's own URL was asked", listOf(url), requests.map { it.url.toString() })
        assertFalse(downloads.isKnownDownloaded("item42"))
    }

    @Test
    fun aFlacFileIsMarkedLosslessSoCompatibleSoundSkipsIt() = runBlocking {
        val downloads = downloads()
        downloads.downloadTrack("item42", url, headers = auth, http = jellyfinHttp, owner = home)
        assertTrue(downloads.downloadedQuality("item42")!!.isLossless)

        type = "audio/mpeg"
        downloads.downloadTrack("item43", url, headers = auth, http = jellyfinHttp, owner = home)
        assertNull("an MP3 sent as it is gets no mark", downloads.downloadedQuality("item43"))
    }

    /** 0.6.1: compressed sound passes an AAC or Opus file as it is; it is saved as sent, never claimed to be MP3. */
    @Test
    fun aLossyFileSentAsItIsIsSavedWithoutAClaimedFormat() = runBlocking {
        val downloads = downloads()
        for ((id, mime) in listOf("aac" to "audio/mp4", "opus" to "audio/ogg")) {
            type = mime
            assertTrue(downloads.downloadTrack(id, url, headers = auth, http = jellyfinHttp, owner = home))
            assertTrue(downloads.isKnownDownloaded(id))
            assertNull("$mime: the player reads the format from the bytes", downloads.downloadedQuality(id))
        }
    }

    /**
     * 0.6.1: the original mode gets a file the device can't play (ALAC) as FLAC in Ogg — `audio/ogg`, like Opus. Its
     * first packet tells them apart: the FLAC one is marked lossless, the Opus one is not.
     */
    @Test
    fun flacInOggIsMarkedLosslessAndOpusIsNot() = runBlocking {
        val downloads = downloads()
        type = "audio/ogg"
        for ((id, mark) in listOf("alac" to "\u007FFLAC", "opus" to "OpusHead")) {
            // An Ogg page: "OggS", version, flags, granule, serial, page number, checksum, 1 segment of 51 bytes
            bytes = "OggS".toByteArray() + ByteArray(22) + byteArrayOf(1, 51) + mark.toByteArray() + ByteArray(4096)
            assertTrue(downloads.downloadTrack(id, url, headers = auth, http = jellyfinHttp, owner = home))
        }

        assertTrue(downloads.downloadedQuality("alac")!!.isLossless)
        assertNull(downloads.downloadedQuality("opus"))
    }

    @Test
    fun signOutRemovesOnlyWhatNoOtherAccountSaved() = runBlocking {
        val downloads = downloads()
        downloads.downloadTrack("shared", url, headers = auth, http = jellyfinHttp, owner = home)
        downloads.addOwner("shared", partner)
        downloads.downloadTrack("mine", url, headers = auth, http = jellyfinHttp, owner = home)

        assertEquals(listOf("mine"), downloads.releaseOwner(home))

        assertFalse(downloads.isTrackDownloaded("mine"))
        assertTrue("the partner still has it", downloads.isTrackDownloaded("shared"))
        assertEquals(setOf(partner.value), downloads.ownersOf("shared"))
        assertFalse(File(context.filesDir, "tracks/mine.owner").exists())
    }

    @Test
    fun theOwnersSurviveARestart() = runBlocking {
        downloads().downloadTrack("item42", url, headers = auth, http = jellyfinHttp, owner = home)
        assertEquals(setOf(home.value), downloads().ownersOf("item42"))
    }

    @Test
    fun aCopyIsPlayableForAnyAccountOfTheSameServerOnly() {
        assertTrue(JellyfinOnDevice.sameServer(setOf(home.value), partner))
        assertFalse(JellyfinOnDevice.sameServer(setOf(home.value), AccountId.jellyfin("srv2", "u1")))
        assertFalse("a Yandex download has no owners", JellyfinOnDevice.sameServer(emptySet(), home))
    }

    /** What the stream cache holds whole, by key (D91 decision 4). */
    private val wholeOnDisk = mutableMapOf<String, ByteArray>()
    private val cached = object : org.tegro.yamp.data.player.CachedStreams {
        override fun completeStreamFor(keyPrefix: String) = null
        override fun openComplete(key: String) = wholeOnDisk[key]?.inputStream()
    }
    private val convertedKey = "jellyfin@srv1/u1|item42|direct:flac-ogg"

    /** An Ogg page with a FLAC first packet, then the audio. */
    private val oggFlac = "OggS".toByteArray() + ByteArray(22) + byteArrayOf(1, 51) + "\u007FFLAC".toByteArray() + ByteArray(100_000) { (it * 7).toByte() }

    /** D91 decision 4: a conversion played whole is copied from the cache — no request, the same bytes, marked lossless. */
    @Test
    fun aConversionWholeInTheCacheIsCopiedWithNoRequest() = runBlocking {
        wholeOnDisk[convertedKey] = oggFlac
        val downloads = TrackDownloadManager(context, shared, cachedStreams = cached)
        assertTrue(downloads.downloadTrack("item42", url, headers = auth, http = jellyfinHttp, owner = home, cacheKey = convertedKey))

        assertEquals("no request", 0, requests.size)
        assertArrayEquals(oggFlac, downloads.getLocalTrackFile("item42")!!.readBytes())
        assertTrue("FLAC in Ogg: lossless", downloads.downloadedQuality("item42")!!.isLossless)
        assertEquals(setOf(home.value), downloads.ownersOf("item42"))
    }

    /** Not whole on disk (nothing, or a partial entry the cache does not open), or under another key: fetched as before. */
    @Test
    fun aConversionNotWholeOnDiskOrUnderAnotherKeyIsFetched() = runBlocking {
        val downloads = TrackDownloadManager(context, shared, cachedStreams = cached)
        assertTrue(downloads.downloadTrack("item42", url, headers = auth, http = jellyfinHttp, owner = home, cacheKey = convertedKey))
        assertEquals(1, requests.size)

        // Whole, but in another quality: the download's own key does not match
        wholeOnDisk["jellyfin@srv1/u1|item43|lossy-128"] = oggFlac
        assertTrue(downloads.downloadTrack("item43", url, headers = auth, http = jellyfinHttp, owner = home, cacheKey = "jellyfin@srv1/u1|item43|lossy-320"))
        assertEquals(2, requests.size)
        assertArrayEquals("the server's bytes", bytes, downloads.getLocalTrackFile("item43")!!.readBytes())
    }

    /** A partial entry in a real stream cache is never copied: the download fetches. */
    @Test
    fun aPartialCacheEntryIsFetched() = runBlocking {
        val dir = File(context.cacheDir, "jf_dl_cache_${System.nanoTime()}")
        val streamCache = org.tegro.yamp.data.player.StreamCache.createWithLimit(context, 10L * 1024 * 1024, dir)
        try {
            val set = androidx.media3.test.utils.FakeDataSet().apply {
                newData("https://jf.example/x").appendReadData(oggFlac).setSimulateUnknownLength(true)
            }
            androidx.media3.datasource.cache.CacheWriter(
                streamCache.writingSource(androidx.media3.test.utils.FakeDataSource.Factory().setFakeDataSet(set).createDataSource()),
                androidx.media3.datasource.DataSpec.Builder().setUri(android.net.Uri.parse("https://jf.example/x")).setKey(convertedKey).setLength(20_000).build(),
                null, null
            ).cache()
            val downloads = TrackDownloadManager(context, shared, cachedStreams = streamCache)
            assertTrue(downloads.downloadTrack("item42", url, headers = auth, http = jellyfinHttp, owner = home, cacheKey = convertedKey))
            assertEquals("fetched", 1, requests.size)
            assertArrayEquals(bytes, downloads.getLocalTrackFile("item42")!!.readBytes())
        } finally {
            streamCache.cache.release()
            dir.deleteRecursively()
        }
    }
}
