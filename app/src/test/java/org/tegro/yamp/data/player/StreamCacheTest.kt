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

    /** Canvas 5e: the share of a stream with no length on disk, by the bytes read per ms loaded over the track. */
    @Test
    fun theShareOfACopyIsEstimatedFromWhatThePlayerReadPerSecondOfSound() {
        // 1 MB read for 10 s loaded of a 100 s track: about 10 MB in all
        assertEquals(0.25f, StreamCache.estimateShare(1_000_000, 10_000, 100_000, written = 2_500_000), 0.001f)
        assertEquals("never less than the player read", 0.1f, StreamCache.estimateShare(1_000_000, 10_000, 100_000, written = 0), 0.001f)
        assertEquals("only a complete file is all of it", 0.99f, StreamCache.estimateShare(1_000_000, 10_000, 100_000, written = 20_000_000), 0.001f)
        assertEquals("nothing to go by", 0f, StreamCache.estimateShare(0, 10_000, 100_000, written = 5_000_000), 0f)
        assertEquals(0f, StreamCache.estimateShare(1_000_000, 10_000, 0, written = 5_000_000), 0f)
    }

    private fun conversion(uri: String, data: ByteArray): DataSource.Factory =
        FakeDataSource.Factory().setFakeDataSet(FakeDataSet().apply { newData(uri).appendReadData(data).setSimulateUnknownLength(true) })

    private fun progressiveCache(downloads: DataSource.Factory, maxBytes: Long = 10L * 1024 * 1024) =
        StreamCache.createWithLimit(context, maxBytes, dir, File(dir.path + "_parts"), downloads).also { caches += it }

    private fun open(source: DataSource, uri: String, key: String, position: Long = 0L): Long =
        source.open(DataSpec.Builder().setUri(Uri.parse(uri)).setKey(key).setPosition(position).build())

    private fun eventually(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            if (done()) return
            Thread.sleep(10)
        }
        throw AssertionError(what)
    }

    /** D91 route 2: a converted stream is read from its download; complete, it is imported and then read from the cache. */
    @Test
    fun aConvertedStreamPlaysFromItsDownloadAndThenFromTheCache() {
        val uri = "https://jf.example/Audio/1/universal"
        val data = bytes(300_000, 5)
        val key = "acc|1|direct:flac-ogg"
        val feed = org.tegro.yamp.data.player.fakes.FeedServer()
        feed.serve().bytes(data).end()
        val streamCache = progressiveCache(feed)
        streamCache.progressive.register(ResolvedStream(uri, cacheKey = key, progressive = true))
        streamCache.progressive.playing(key)

        val player = streamCache.wrap(noNetwork).createDataSource()
        assertEquals("no length while it downloads", androidx.media3.common.C.LENGTH_UNSET.toLong(), open(player, uri, key))
        assertArrayEquals("the player never asked the network itself", data, DataSourceUtil.readToEnd(player))
        player.close()
        eventually("imported") { streamCache.isComplete(key) }
        assertEquals("fetched once", 1, feed.requests.size)

        // Route 1: from the cache, seekable, still with no network
        val again = streamCache.wrap(noNetwork).createDataSource()
        assertEquals(200_000L, open(again, uri, key, position = 100_000))
        assertArrayEquals(data.copyOfRange(100_000, data.size), DataSourceUtil.readToEnd(again))
        again.close()
        eventually("the part goes") { File(dir.path + "_parts").listFiles().isNullOrEmpty() }
    }

    /** Route 3's safety net: a stream the probe missed comes with no length at its start → downloaded whole instead. */
    @Test
    fun aStreamWithNoLengthAtItsStartBecomesADownload() {
        val uri = "https://jf.example/Audio/2/universal"
        val data = bytes(100_000, 1)
        val streamCache = progressiveCache(conversion(uri, data))
        val key = "acc|2|lossy-320"

        val player = streamCache.wrap(conversion(uri, data)).createDataSource()
        // No length while the download runs; the 100 KB may already be whole when the part opens (it then reports its
        // length, GrowingFileSource) — a race of the test's instant server, seen once in a full run (2026-11-20)
        val opened = open(player, uri, key)
        assertTrue("opened: $opened", opened == androidx.media3.common.C.LENGTH_UNSET.toLong() || opened == data.size.toLong())
        assertTrue("from now on a converted stream", streamCache.progressive.isProgressive(key))
        assertArrayEquals(data, DataSourceUtil.readToEnd(player))
        player.close()
        eventually("imported") { streamCache.isComplete(key) }
    }

    /** Route 3 as before: an original (with a length) streams through the cache, filling it. */
    @Test
    fun anOriginalStreamsThroughTheCacheAsBefore() {
        val uri = "https://cdn.example/a.flac"
        val data = bytes(100_000, 2)
        val streamCache = progressiveCache(noNetwork)
        val player = streamCache.wrap(server(uri to data)).createDataSource()
        assertEquals(100_000L, open(player, uri, "acc|3|flac"))
        DataSourceUtil.readToEnd(player)
        player.close()
        assertTrue(streamCache.isComplete("acc|3|flac"))
        assertFalse(streamCache.progressive.isProgressive("acc|3|flac"))
    }

    @Test
    fun theShareFollowsThePlayersReadAndTheDownloadAndHasNoPercentBeforeAnyRead() {
        val uri = "https://jf.example/Audio/7/universal"
        val key = "acc|7|direct:flac-ogg"
        val feed = org.tegro.yamp.data.player.fakes.FeedServer()
        val served = feed.serve().bytes(bytes(100_000, 3))
        val streamCache = progressiveCache(feed)
        streamCache.progressive.register(ResolvedStream(uri, cacheKey = key, progressive = true))
        streamCache.progressive.playing(key)
        val player = streamCache.wrap(noNetwork).createDataSource()
        open(player, uri, key)
        assertEquals("nothing read yet: no percent", 0f, streamCache.downloadShare(key, 1_000, 10_000), 0f)
        var got = 0
        while (got < 50_000) got += player.read(ByteArray(50_000 - got), 0, 50_000 - got)
        eventually("written") { streamCache.progressive.written(key) == 100_000L }
        // 50 KB for 1 s loaded of a 10 s track: about 500 KB in all; the download has 100 KB
        assertEquals(0.2f, streamCache.downloadShare(key, 1_000, 10_000), 0.01f)
        served.end()
        eventually("complete") { streamCache.progressive.isComplete(key) }
        assertEquals("all of it on disk", 1f, streamCache.downloadShare(key, 1_000, 10_000), 0f)
        player.close()
    }

    @Test
    fun aRunningDownloadsStartCountsAsOnDisk() {
        val uri = "https://jf.example/Audio/8/universal"
        val key = "acc|8|lossy-320"
        val feed = org.tegro.yamp.data.player.fakes.FeedServer()
        val served = feed.serve().bytes(bytes(StreamCache.START_BYTES.toInt(), 1))
        val streamCache = progressiveCache(feed)
        streamCache.progressive.prefetch(ResolvedStream(uri, cacheKey = key, progressive = true))
        eventually("its start written") { streamCache.cachedState("acc|8|") == StreamCache.CachedState.START }
        served.end()
        eventually("complete") { streamCache.cachedState("acc|8|") == StreamCache.CachedState.COMPLETE }
    }

    @Test
    fun aDownloadLargerThanTheCacheIsNotImportedButStillPlays() {
        val uri = "https://jf.example/Audio/9/universal"
        val key = "acc|9|lossy-320"
        val data = bytes(200_000, 4)
        val journal = java.util.concurrent.CopyOnWriteArrayList<String>()
        val streamCache = progressiveCache(conversion(uri, data), maxBytes = 100_000)
        streamCache.onProgressive = { _, line -> journal += line }
        streamCache.progressive.register(ResolvedStream(uri, cacheKey = key, progressive = true))
        streamCache.progressive.playing(key)
        val player = streamCache.wrap(noNetwork).createDataSource()
        open(player, uri, key)
        assertArrayEquals(data, DataSourceUtil.readToEnd(player))
        player.close()
        eventually("not imported") { journal.contains("not imported: larger than the cache") }
        assertFalse(streamCache.isComplete(key))
        assertTrue("the part serves it", streamCache.progressive.isComplete(key))
    }

    @Test
    fun withTheCacheOffADownloadStillPlaysAndIsNotImported() {
        val uri = "https://jf.example/Audio/10/universal"
        val key = "acc|10|lossy-320"
        val data = bytes(50_000, 4)
        val journal = java.util.concurrent.CopyOnWriteArrayList<String>()
        val streamCache = progressiveCache(conversion(uri, data))
        streamCache.maxBytes = 0
        streamCache.onProgressive = { _, line -> journal += line }
        streamCache.progressive.register(ResolvedStream(uri, cacheKey = key, progressive = true))
        streamCache.progressive.playing(key)
        val player = streamCache.wrap(noNetwork).createDataSource()
        open(player, uri, key)
        assertArrayEquals(data, DataSourceUtil.readToEnd(player))
        player.close()
        eventually("not imported") { journal.contains("not imported: the cache is off") }
        assertEquals(0L, streamCache.cache.cacheSpace)
    }

    @Test
    fun signingOutDuringADownloadLeavesNoKeyAndNoPart() {
        val uri = "https://jf.example/Audio/11/universal"
        val key = "jf:1|11|lossy-320"
        val feed = org.tegro.yamp.data.player.fakes.FeedServer()
        feed.serve().bytes(bytes(10_000, 1))
        val streamCache = progressiveCache(feed)
        streamCache.progressive.prefetch(ResolvedStream(uri, cacheKey = key, progressive = true))
        eventually("downloading") { streamCache.progressive.written(key) > 0L }

        streamCache.removeAccount("jf:1")

        assertFalse(streamCache.progressive.isProgressive(key))
        assertTrue(File(dir.path + "_parts").listFiles().isNullOrEmpty())
        assertFalse(streamCache.cache.keys.contains(key))
    }

    /** D91 Decision 3: the playing-track copies of older builds and partial entries with no length go at startup. */
    @Test
    fun olderCopiesAndPartialsWithNoLengthAreDroppedAtStartup() {
        val streamCache = cache()
        val conversion = "https://jf.example/Audio/12/universal"
        // A partial of a stream with no length, as an older build streamed it, and its "#copy"
        listOf("acc|12|direct:flac-ogg", "acc|12|direct:flac-ogg#copy").forEach { key ->
            val writer = androidx.media3.datasource.cache.CacheWriter(
                androidx.media3.datasource.cache.CacheDataSource(streamCache.cache, conversion(conversion, bytes(50_000, 1)).createDataSource()),
                DataSpec.Builder().setUri(Uri.parse(conversion)).setKey(key).setLength(20_000).build(), null, null
            )
            writer.cache()
        }
        val original = "https://cdn.example/o.flac"
        val partial = streamCache.wrap(server(original to bytes(100_000, 2))).createDataSource()
        open(partial, original, "acc|13|flac")
        partial.read(ByteArray(10_000), 0, 10_000)
        partial.close()

        streamCache.dropStaleEntries()

        assertEquals("an original's partial resumes by range: it stays", setOf("acc|13|flac"), streamCache.cache.keys)
    }

    /** A next track whose stream turns out to have no length is handed over to a download (the probe missed). */
    @Test
    fun aNextTrackWithNoLengthIsHandedToADownload() = runBlocking {
        val uri = "https://jf.example/Audio/2/universal"
        val data = bytes(300_000, 1)
        val streamCache = progressiveCache(conversion(uri, data))
        val key = "acc|2|direct"

        val result = fetch(NextTrackCacher(streamCache, this, Dispatchers.IO, conversion(uri, data)), "2", stream(uri, key))

        assertEquals(NextTrackFetch.Result("2", NextTrackFetch.Outcome.WRITTEN), result)
        assertTrue(streamCache.progressive.isProgressive(key))
        eventually("imported") { streamCache.isComplete(key) }
        assertArrayEquals(data, read(streamCache.wrap(noNetwork), uri, key))
    }

    /** The next converted track is downloaded, not written by a CacheWriter: the warm player reads the same bytes. */
    @Test
    fun aConvertedNextTrackIsDownloadedOnce() = runBlocking {
        val uri = "https://jf.example/Audio/3/universal"
        val data = bytes(100_000, 6)
        val feed = org.tegro.yamp.data.player.fakes.FeedServer()
        val served = feed.serve()
        val streamCache = progressiveCache(feed)
        val key = "acc|3|lossy-320"
        val cacher = NextTrackCacher(streamCache, this, Dispatchers.IO, noNetwork)
        val result = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { cacher.results.first() }
        cacher.prefetch("3", ResolvedStream(uri, cacheKey = key, progressive = true))
        assertEquals("3", cacher.writingTrackId)

        // The warm player joins the running download
        val warm = streamCache.wrap(noNetwork).createDataSource()
        open(warm, uri, key)
        served.bytes(data).end()
        assertArrayEquals(data, DataSourceUtil.readToEnd(warm))
        warm.close()
        assertEquals(NextTrackFetch.Result("3", NextTrackFetch.Outcome.WRITTEN), withTimeout(10_000) { result.await() })
        assertEquals(1, feed.requests.size)
        assertNull(cacher.writingTrackId)
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
    fun aFullyCachedTrackPlaysOfflineAfterARestart() = runBlocking {
        val data = bytes(200_000, 9)
        val uri = "https://cdn.example/a.mp3?sign=abc"
        val first = cache()
        val kept = ResolvedStream(
            uri, quality = org.tegro.yamp.domain.provider.StreamQuality("mp3", 320),
            decryption = org.tegro.yamp.domain.provider.Decryption("encraw"), cacheKey = "yandex:1|5|mp3-320", durationMs = 1234L
        )
        assertEquals(NextTrackFetch.Outcome.WRITTEN, fetch(NextTrackCacher(first, this, Dispatchers.IO, server(uri to data)), "5", kept).outcome)
        first.cache.release()
        caches.remove(first)

        // A new process: nothing in memory, only the cache's own database
        val second = cache()
        var journaled: String? = null
        second.onOfflineStart = { journaled = it }
        assertEquals(kept, second.completeStreamFor("yandex:1|5|"))
        assertEquals("yandex:1|5|mp3-320", journaled)
        assertArrayEquals(data, read(second.wrap(noNetwork), "https://cdn.example/a.mp3?sign=new", "yandex:1|5|mp3-320"))
    }

    @Test
    fun aStreamThatNeedsHeadersIsNotKeptOnDisk() = runBlocking {
        val uri = "https://jf.example/Audio/7/universal"
        val first = cache()
        val withAuth = ResolvedStream(uri, headers = mapOf("Authorization" to "secret"), cacheKey = "jf:1|7|direct")
        fetch(NextTrackCacher(first, this, Dispatchers.IO, server(uri to bytes(50_000, 3))), "7", withAuth)
        first.cache.release()
        caches.remove(first)

        assertEquals(null, cache().completeStreamFor("jf:1|7|"))
    }

    @Test
    fun signingOutRemovesOnlyThatAccountsStreams() = runBlocking {
        val streamCache = cache()
        val a = "https://cdn.example/a.mp3"
        val b = "https://cdn.example/b.mp3"
        val cacher = NextTrackCacher(streamCache, this, Dispatchers.IO, server(a to bytes(50_000, 1), b to bytes(50_000, 2)))
        fetch(cacher, "1", stream(a, "yandex:1|1|mp3"))
        fetch(cacher, "2", stream(b, "yandex:2|2|mp3"))

        streamCache.removeAccount("yandex:1")

        assertFalse(streamCache.isComplete("yandex:1|1|mp3"))
        assertEquals(null, streamCache.completeStreamFor("yandex:1|1|"))
        assertTrue(streamCache.isComplete("yandex:2|2|mp3"))
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

    /** D91 decision 4: a download copies a whole converted stream from disk — the cache or a complete part — never a partial one. */
    @Test
    fun onlyAWholeStreamIsOpenedForADownload() {
        val uri = "https://jf.example/Audio/7/universal"
        val data = bytes(200_000, 8)
        val feed = org.tegro.yamp.data.player.fakes.FeedServer()
        val served = feed.serve()
        val streamCache = progressiveCache(feed, maxBytes = 100_000) // the part is larger than the cache: never imported
        val key = "acc|7|direct:flac-ogg"
        streamCache.progressive.register(ResolvedStream(uri, cacheKey = key, progressive = true))
        streamCache.progressive.playing(key)
        val player = streamCache.wrap(noNetwork).createDataSource()
        open(player, uri, key)
        served.bytes(data.copyOf(50_000))
        eventually("half written") { streamCache.progressive.written(key) == 50_000L }
        assertNull("a running download is not whole", streamCache.openComplete(key))

        served.bytes(data.copyOfRange(50_000, data.size)).end()
        eventually("complete") { streamCache.progressive.isComplete(key) }
        assertArrayEquals("from the complete part", data, streamCache.openComplete(key)!!.use { it.readBytes() })
        player.close()

        // A partial entry (no length known) in the cache is never opened; a complete one is
        val partialKey = "acc|8|lossy-320"
        androidx.media3.datasource.cache.CacheWriter(
            streamCache.writingSource(conversion("https://jf.example/8", bytes(50_000, 9)).createDataSource()),
            DataSpec.Builder().setUri(Uri.parse("https://jf.example/8")).setKey(partialKey).setLength(20_000).build(), null, null
        ).cache()
        assertFalse(streamCache.isComplete(partialKey))
        assertNull(streamCache.openComplete(partialKey))
        val whole = bytes(60_000, 10)
        androidx.media3.datasource.cache.CacheWriter(
            streamCache.writingSource(server("https://jf.example/9" to whole).createDataSource()),
            DataSpec.Builder().setUri(Uri.parse("https://jf.example/9")).setKey("acc|9|lossy-320").build(), null, null
        ).cache()
        assertArrayEquals(whole, streamCache.openComplete("acc|9|lossy-320")!!.use { it.readBytes() })
    }
}
