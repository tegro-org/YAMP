package org.tegro.yamp.data.player

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stream headers (step 23): a Jellyfin token goes with requests to its own stream URL and nowhere else. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class StreamHeadersTest {

    private val jellyfinUrl = "http://10.0.2.2:8096/Audio/item42/universal?UserId=u&EnableRedirection=false"
    private val auth = mapOf("Authorization" to "MediaBrowser Token=\"t0k\"")

    /** Records what the HTTP source would have sent. */
    private class Recording : DataSource {
        val opened = mutableListOf<DataSpec>()
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            opened += dataSpec
            return 0L
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun getUri(): Uri? = opened.lastOrNull()?.uri
        override fun close() = Unit
    }

    private val recording = Recording()
    private val source = StreamHeaders.wrap { recording }.createDataSource()

    @After
    fun tearDown() = StreamHeaders.clear()

    private fun open(url: String): Map<String, String> {
        source.open(DataSpec(Uri.parse(url)))
        source.close()
        return recording.opened.last().httpRequestHeaders
    }

    @Test
    fun aRegisteredStreamGetsItsHeaders() {
        StreamHeaders.register(jellyfinUrl, auth)

        assertEquals(auth, open(jellyfinUrl))
    }

    @Test
    fun noOtherUrlOrHostGetsThem() {
        StreamHeaders.register(jellyfinUrl, auth)

        assertTrue("another item of the same server", open("http://10.0.2.2:8096/Audio/other/universal").isEmpty())
        assertTrue("a cover host", open("https://avatars.yandex.net/get-music-content/1/2/400x400").isEmpty())
        assertTrue("a Yandex stream", open("https://s1.storage.yandex.net/get-mp3/abc/def.mp3").isEmpty())
    }

    @Test
    fun aYandexStreamWithoutHeadersIsUnchanged() {
        val yandex = "https://s1.storage.yandex.net/get-mp3/abc/def.mp3#encraw_key=00ff"
        StreamHeaders.register(yandex, emptyMap())

        source.open(DataSpec(Uri.parse(yandex)))

        val spec = recording.opened.single()
        assertTrue(spec.httpRequestHeaders.isEmpty())
        assertEquals(yandex, spec.uri.toString())
    }
}
