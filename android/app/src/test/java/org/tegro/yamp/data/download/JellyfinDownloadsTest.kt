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

    private val server = Interceptor { chain ->
        requests += chain.request()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("")
            .apply { if (status == 302) header("Location", "https://elsewhere.example/file") }
            .body(ByteArray(4096) { it.toByte() }.toResponseBody(type.toMediaType())).build()
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
}
