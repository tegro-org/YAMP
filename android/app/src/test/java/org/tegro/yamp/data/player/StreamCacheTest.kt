package org.tegro.yamp.data.player

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.test.utils.FakeDataSet
import androidx.media3.test.utils.FakeDataSource
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.domain.provider.ResolvedStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The on-disk stream cache (D86): keys, the full write ahead, eviction, playing with no network. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StreamCacheTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val dir = File(context.cacheDir, "stream_cache_test_${System.nanoTime()}")
    private val caches = mutableListOf<StreamCache>()

    @After
    fun tearDown() {
        caches.forEach { it.cache.release() }
        dir.deleteRecursively()
    }

    private fun cache(maxBytes: Long = 10L * 1024 * 1024) =
        StreamCache.createWithLimit(context, maxBytes, dir).also { caches += it }

    private fun bytes(size: Int, seed: Int) = ByteArray(size) { (it * 31 + seed).toByte() }

    private fun server(vararg files: Pair<String, ByteArray>): DataSource.Factory {
        val set = FakeDataSet()
        files.forEach { (uri, data) -> set.setData(uri, data) }
        return FakeDataSource.Factory().setFakeDataSet(set)
    }

    /** A source whose [open] does [onOpen]: nothing is ever read from it. */
    private fun failing(onOpen: () -> Nothing) = DataSource.Factory {
        object : DataSource {
            override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long = onOpen()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("not open")
            override fun getUri(): Uri? = null
            override fun close() = Unit
        }
    }

    private val noNetwork = failing { throw IOException("no network") }

    private fun stream(uri: String, key: String) = ResolvedStream(uri, cacheKey = key)

    /** Starts a write and waits for its outcome (listening first: the results are not replayed). */
    private suspend fun kotlinx.coroutines.CoroutineScope.fetch(cacher: NextTrackCacher, id: String, stream: ResolvedStream): NextTrackFetch.Result {
        val result = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { cacher.results.first() }
        cacher.prefetch(id, stream)
        return withTimeout(10_000) { result.await() }
    }

    private fun read(factory: DataSource.Factory, uri: String, key: String): ByteArray {
        val source = factory.createDataSource()
        return try {
            source.open(DataSpec.Builder().setUri(Uri.parse(uri)).setKey(key).build())
            DataSourceUtil.readToEnd(source)
        } finally {
            source.close()
        }
    }

    @Test
    fun aKeyIsTheSameForOneAccountTrackAndQualityAndDiffersOtherwise() {
        val key = StreamCache.key("yandex:1", "100", "flac-1411")
        assertEquals(key, StreamCache.key("yandex:1", "100", "flac-1411"))
        assertNotEquals(key, StreamCache.key("yandex:2", "100", "flac-1411"))
        assertNotEquals(key, StreamCache.key("yandex:1", "100", "mp3-320"))
        assertTrue(key.startsWith(StreamCache.keyPrefix("yandex:1", "100")))
    }

    @Test
    fun theNextTrackWrittenAheadPlaysWithNoNetwork() = runBlocking {
        val data = bytes(200_000, 7)
        val uri = "https://cdn.example/next.flac?sign=abc"
        val streamCache = cache()
        val cacher = NextTrackCacher(streamCache, this, Dispatchers.IO, server(uri to data))

        val result = fetch(cacher, "2", stream(uri, "acc|2|flac"))

        assertEquals(NextTrackFetch.Result("2", NextTrackFetch.Outcome.WRITTEN), result)
        assertTrue(streamCache.isComplete("acc|2|flac"))
        // A new signed link, and no network at all: the key finds the bytes
        assertArrayEquals(data, read(streamCache.wrap(noNetwork), "https://cdn.example/next.flac?sign=new", "acc|2|flac"))
        assertEquals(stream(uri, "acc|2|flac"), streamCache.completeStreamFor("acc|2|"))
    }

    @Test
    fun aTrackAlreadyOnDiskIsNotWrittenAgain() = runBlocking {
        val uri = "https://cdn.example/t.mp3"
        val streamCache = cache()
        val cacher = NextTrackCacher(streamCache, this, Dispatchers.IO, server(uri to bytes(50_000, 1)))
        fetch(cacher, "2", stream(uri, "k"))

        assertEquals(NextTrackFetch.Outcome.ALREADY_ON_DISK, fetch(cacher, "2b", stream(uri, "k")).outcome)
    }

    @Test
    fun aSkipCancelsTheWrite() = runBlocking {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = failing {
            opened.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw java.io.InterruptedIOException("cancelled")
        }
        val streamCache = cache()
        val cacher = NextTrackCacher(streamCache, this, Dispatchers.IO, slow)

        cacher.prefetch("2", stream("https://cdn.example/slow.flac", "slow"))
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        assertEquals("2", cacher.writingTrackId)
        cacher.cancel()
        release.countDown()

        assertNull(cacher.writingTrackId)
        assertFalse(streamCache.isComplete("slow"))
    }

    @Test
    fun theLeastRecentlyUsedTrackGoesFirst() = runBlocking {
        val streamCache = cache(maxBytes = 300_000)
        val a = "https://cdn.example/a.mp3"
        val b = "https://cdn.example/b.mp3"
        val cacher = NextTrackCacher(streamCache, this, Dispatchers.IO, server(a to bytes(200_000, 1), b to bytes(200_000, 2)))

        fetch(cacher, "a", stream(a, "a"))
        fetch(cacher, "b", stream(b, "b"))

        assertTrue("the newest stays", streamCache.isComplete("b"))
        assertFalse("the oldest went", streamCache.isComplete("a"))
        assertTrue(streamCache.usedBytes <= 300_000)
    }

    @Test
    fun withTheCacheOffNothingIsWritten() = runBlocking {
        val streamCache = cache()
        streamCache.maxBytes = 0
        val cacher = NextTrackCacher(streamCache, this, Dispatchers.IO, server("https://cdn.example/x.mp3" to bytes(10_000, 3)))

        cacher.prefetch("x", stream("https://cdn.example/x.mp3", "x"))

        assertNull(cacher.writingTrackId)
        assertFalse(streamCache.isComplete("x"))
        assertEquals(0L, streamCache.usedBytes)
    }

    @Test
    fun aRequestWithoutAKeyBypassesTheCache() {
        val streamCache = cache()
        val data = bytes(10_000, 4)
        val uri = "https://cdn.example/radio"

        val source = streamCache.wrap(server(uri to data)).createDataSource()
        source.open(DataSpec(Uri.parse(uri)))
        DataSourceUtil.readToEnd(source)
        source.close()

        assertEquals(0L, streamCache.usedBytes)
    }
}
