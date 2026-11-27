package org.tegro.yamp.data.player

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.player.fakes.FeedServer
import org.tegro.yamp.domain.provider.ResolvedStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** D91: what a player sees of a growing part — no length while it grows, its length once complete, the end exactly. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GrowingFileSourceTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val parts = File(context.cacheDir, "parts_${System.nanoTime()}")
    private val server = FeedServer()
    private val downloads = ProgressiveDownloads(parts, server, { _, _, _, _ -> false }, restartDelayMs = 0L)
    private val pool = Executors.newCachedThreadPool()
    private val key = "acc|1|lossy-320"
    private val uri = Uri.parse("https://jf.example/Audio/1/universal")
    private val data = ByteArray(200_000) { (it * 7 + 3).toByte() }

    @After
    fun tearDown() {
        downloads.cancelAll()
        pool.shutdownNow()
        parts.deleteRecursively()
    }

    private fun source(): GrowingFileSource {
        downloads.register(ResolvedStream(uri.toString(), cacheKey = key, progressive = true))
        downloads.playing(key)
        return GrowingFileSource(downloads.attach(key, uri.toString())!!, uri)
    }

    @Test
    fun aGrowingStreamHasNoLengthAndEndsExactlyAtItsTotal() {
        val feed = server.serve()
        val source = source()
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(DataSpec(uri)))
        val read = pool.submit<ByteArray> { DataSourceUtil.readToEnd(source) }
        // Trickled in chunks, the last one then the end
        data.toList().chunked(50_000).forEach { chunk ->
            feed.bytes(chunk.toByteArray())
            Thread.sleep(50)
        }
        assertFalse("waits for the end", read.isDone)
        feed.end()
        assertArrayEquals(data, read.get(5, TimeUnit.SECONDS))
        source.close()
    }

    @Test
    fun openedOnACompleteDownloadItHasItsLengthAndSeeks() {
        server.serve().bytes(data).end()
        val first = source()
        first.open(DataSpec(uri))
        DataSourceUtil.readToEnd(first)
        first.close()
        assertTrue(downloads.isComplete(key))

        val again = GrowingFileSource(downloads.attach(key, uri.toString())!!, uri)
        assertEquals("seekable: the length is known", 150_000L, again.open(DataSpec.Builder().setUri(uri).setPosition(50_000).build()))
        assertArrayEquals(data.copyOfRange(50_000, data.size), DataSourceUtil.readToEnd(again))
        assertEquals(C.RESULT_END_OF_INPUT, again.read(ByteArray(10), 0, 10))
        again.close()
        assertEquals("one request for all of it", 1, server.requests.size)
    }
}
