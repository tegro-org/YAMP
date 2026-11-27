package org.tegro.yamp.data.player

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.player.fakes.FeedServer
import org.tegro.yamp.domain.provider.ResolvedStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * D91: a converted stream is fetched once, whole, into a growing part file; readers wait at its written edge; a broken
 * fetch restarts from byte 0 as a new generation while the old one stays readable.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProgressiveDownloadsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val parts = File(context.cacheDir, "parts_${System.nanoTime()}")
    private val server = FeedServer()
    private var importResult = true
    private val imports = CopyOnWriteArrayList<Pair<String, Long>>()
    private val journal = CopyOnWriteArrayList<String>()
    private val downloads = ProgressiveDownloads(parts, server, { stream, _, length, _ ->
        imports += stream.cacheKey!! to length
        importResult
    }, restartDelayMs = 0L).apply { this.journal = { _, line -> this@ProgressiveDownloadsTest.journal += line } }
    private val pool = Executors.newCachedThreadPool()

    @After
    fun tearDown() {
        downloads.cancelAll()
        pool.shutdownNow()
        parts.deleteRecursively()
    }

    private fun key(id: String) = "acc|$id|direct:flac-ogg"
    private fun uri(id: String) = "https://jf.example/Audio/$id/universal"
    private fun stream(id: String) = ResolvedStream(uri(id), cacheKey = key(id), progressive = true)
    private fun bytes(size: Int, seed: Int) = ByteArray(size) { (it * 31 + seed).toByte() }

    /** [id] plays: registered, the engine's playing key, a reader attached (which starts the download). */
    private fun play(id: String): ProgressiveDownloads.Reader {
        downloads.register(stream(id))
        downloads.playing(key(id))
        return downloads.attach(key(id), uri(id))!!
    }

    private fun eventually(what: String, timeoutMs: Long = 5_000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (done()) return
            Thread.sleep(10)
        }
        fail(what)
    }

    private fun <T> Future<T>.within(ms: Long): T = get(ms, TimeUnit.MILLISECONDS)

    /** Reads everything [reader] offers from [from] to its end. */
    private fun readAll(reader: ProgressiveDownloads.Reader, from: Long = 0L): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.io.RandomAccessFile(reader.file, "r").use { file ->
            var at = from
            file.seek(at)
            while (true) {
                val n = reader.available(at)
                if (n == C.RESULT_END_OF_INPUT.toLong()) break
                val buffer = ByteArray(n.toInt())
                file.readFully(buffer)
                out.write(buffer)
                at += n
            }
        }
        return out.toByteArray()
    }

    @Test
    fun theReaderWaitsAtTheEdgeAndWakesOnBytesAndOnTheEnd() {
        val feed = server.serve()
        val reader = play("1")
        val first = pool.submit<Long> { reader.available(0L) }
        Thread.sleep(200)
        assertFalse("waits at the edge", first.isDone)

        feed.bytes(bytes(1_000, 1))
        assertEquals("wakes on bytes", 1_000L, first.within(2_000))

        val end = pool.submit<Long> { reader.available(1_000L) }
        Thread.sleep(200)
        assertFalse(end.isDone)
        feed.end()
        assertEquals("the end exactly at the total", C.RESULT_END_OF_INPUT.toLong(), end.within(2_000))
        assertEquals(1_000L, reader.length())
        assertTrue(downloads.isComplete(key("1")))
        assertEquals("one request", 1, server.requests.size)
        reader.close()
    }

    @Test
    fun aRefusedLinkFailsAtOnceAndTheWaitingReaderGetsTheError() {
        server.serve(FeedServer.Feed(openError = FeedServer.refused(403)))
        val reader = play("1")
        val error = runCatching { pool.submit<Long> { reader.available(0L) }.within(3_000) }.exceptionOrNull()?.cause
        assertTrue("$error", error is ProgressiveDownloadException)
        assertEquals("the cause's code: RETRY, then skip", PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, (error as ProgressiveDownloadException).reason)
        Thread.sleep(200)
        assertEquals("never restarted", 1, server.requests.size)
        assertTrue(journal.any { it.startsWith("failed:") })
    }

    @Test
    fun anInterruptDuringAWaitReturnsAtOnce() {
        server.serve()
        val reader = play("1")
        var thread: Thread? = null
        val wait = pool.submit<Throwable?> {
            thread = Thread.currentThread()
            runCatching { reader.available(0L) }.exceptionOrNull()
        }
        eventually("waiting") { thread != null }
        Thread.sleep(100)
        val at = System.currentTimeMillis()
        thread!!.interrupt()
        assertTrue(wait.within(ProgressiveDownloads.WAKE_MS) is InterruptedIOException)
        assertTrue(System.currentTimeMillis() - at < ProgressiveDownloads.WAKE_MS)
    }

    @Test
    fun aCloseDuringAWaitEndsItWithinTheWake() {
        server.serve()
        val reader = play("1")
        val wait = pool.submit<Throwable?> { runCatching { reader.available(0L) }.exceptionOrNull() }
        Thread.sleep(100)
        reader.close()
        assertTrue(wait.within(ProgressiveDownloads.WAKE_MS + 100) is InterruptedIOException)
    }

    @Test
    fun aRestartKeepsTheOldGenerationReadableAndStartsTheNewOneAtZero() {
        importResult = false // the new generation stays a part, read below
        val first = bytes(10_000, 1)
        val whole = bytes(30_000, 2)
        server.serve().bytes(first).fail(FeedServer.timeout())
        val second = server.serve()
        val old = play("1")
        assertArrayEquals("generation 1 plays on", first, pool.submit<ByteArray> {
            val got = ByteArray(first.size)
            java.io.RandomAccessFile(old.file, "r").use { f ->
                var at = 0L
                while (at < first.size) {
                    val n = old.available(at).toInt()
                    f.seek(at)
                    f.readFully(got, at.toInt(), n)
                    at += n
                }
            }
            got
        }.within(3_000))
        eventually("restarted") { server.requests.size == 2 }
        assertEquals("from byte 0", 0L, server.requests[1].position)
        assertTrue(journal.any { it.startsWith("restart 1/2") })

        // The reader of generation 1 waits at its end, also after generation 2 is complete: no early switch
        val parked = pool.submit<Long> { old.available(first.size.toLong()) }
        eventually("parked") { downloads.parkedAtBrokenGeneration(key("1")) }
        second.bytes(whole).end()
        eventually("complete") { downloads.isComplete(key("1")) }
        Thread.sleep(300)
        assertFalse("still waiting at the old edge", parked.isDone)

        val fresh = downloads.attach(key("1"), uri("1"))!!
        assertEquals("the new generation has its length", whole.size.toLong(), fresh.length())
        assertArrayEquals(whole, readAll(fresh))
        val oldFile = old.file
        old.close()
        assertFalse("generation 1's part goes with its reader", oldFile.exists())
        fresh.close()
    }

    @Test
    fun aDownloadRestartsTwiceAtMostThenFails() {
        repeat(3) { server.serve().bytes(bytes(1_000, it)).fail(FeedServer.timeout()) }
        val reader = play("1")
        eventually("failed") { journal.any { it.startsWith("failed:") } }
        assertEquals(3, server.requests.size)
        val error = runCatching { reader.available(Long.MAX_VALUE / 2) }.exceptionOrNull()
        assertTrue(error is ProgressiveDownloadException)
        assertEquals(
            "a timeout that restarts did not cure keeps its slowness code: the controller pauses (D67)",
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, (error as ProgressiveDownloadException).reason
        )
    }

    /** D67: Play after a failed download starts it anew; a reopen waiting on the written bytes sees them grow, never drop. */
    @Test
    fun theWrittenTotalGoesOnAcrossADownloadStartedAgainAfterAFailure() {
        repeat(3) { server.serve().bytes(bytes(1_000, it)).fail(FeedServer.timeout()) }
        val reader = play("1")
        eventually("failed") { journal.any { it.startsWith("failed:") } }
        reader.close()
        val before = downloads.writtenTotal(key("1"))
        assertEquals(3_000L, before)

        val feed = server.serve()
        val again = play("1")
        feed.bytes(bytes(500, 9))
        eventually("the new download's bytes count on top") { downloads.writtenTotal(key("1")) == before + 500 }
        feed.end()
        eventually("complete") { downloads.isComplete(key("1")) }
        again.close()
    }

    @Test
    fun aDeadStartGetsOneRestartThenFails() {
        repeat(2) { server.serve().fail(FeedServer.timeout()) }
        server.serve().bytes(bytes(1_000, 1)).end()
        val reader = play("1")
        eventually("failed") { journal.any { it.startsWith("failed:") } }
        Thread.sleep(200)
        assertEquals(2, server.requests.size)
        assertFalse(downloads.isComplete(key("1")))
        val error = runCatching { reader.available(0L) }.exceptionOrNull()
        assertEquals(
            "timeouts with not a byte are a dead start: RETRY → skip, not pause",
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED, (error as ProgressiveDownloadException).reason
        )
    }

    @Test
    fun noRestartWhileThereIsNoLinkAndTheWaitIsNotCounted() {
        downloads.linkChanged(false)
        server.serve().bytes(bytes(1_000, 1)).fail(FeedServer.timeout())
        play("1")
        Thread.sleep(1_200)
        assertEquals("held while the link is NONE", 1, server.requests.size)
        server.serve().bytes(bytes(2_000, 2)).end()
        downloads.linkChanged(true)
        eventually("restarted and complete") { downloads.isComplete(key("1")) }
        assertEquals(2, server.requests.size)
    }

    @Test
    fun thePartGoesOnceImportedAndNoLongerRead() {
        server.serve().bytes(bytes(5_000, 1)).end()
        val reader = play("1")
        eventually("imported") { imports.isNotEmpty() }
        assertEquals(key("1") to 5_000L, imports.single())
        assertTrue("still read: kept", reader.file.exists())
        val file = reader.file
        reader.close()
        assertFalse(file.exists())
        assertNull("in the cache now: read from there", downloads.attach(key("1"), uri("1")))
    }

    @Test
    fun aPartNotImportedServesTheTrackUntilItLeaves() {
        importResult = false // larger than the cache, or the cache is off
        server.serve().bytes(bytes(5_000, 1)).end()
        val reader = play("1")
        eventually("complete") { downloads.isComplete(key("1")) }
        reader.close()
        val again = downloads.attach(key("1"), uri("1"))!!
        assertEquals(5_000L, again.length())
        again.close()
        downloads.playing(key("2"))
        assertFalse("left: gone", downloads.isComplete(key("1")))
        eventually("its part deleted") { parts.listFiles().isNullOrEmpty() }
    }

    @Test
    fun aDownloadNeitherPlayingNorNextIsCancelledAndItsPartDeleted() {
        server.serve().bytes(bytes(1_000, 1))
        val reader = play("1")
        eventually("writing") { downloads.written(key("1")) == 1_000L }
        downloads.playing(key("2"))
        assertTrue("a reader still holds it (a crossfade's tail)", downloads.written(key("1")) == 1_000L)
        reader.close()
        assertEquals(0L, downloads.written(key("1")))
        eventually("its part deleted") { parts.listFiles().isNullOrEmpty() }
        assertTrue(journal.any { it.startsWith("cancelled") })
    }

    @Test
    fun onAMeteredNetworkTheNextTrackWaitsForThePlayingOne() {
        downloads.metered = { true }
        val playing = server.serve()
        val reader = play("1")
        server.serve().bytes(bytes(100, 2)).end()
        val next = downloads.prefetch(stream("2"))!!
        Thread.sleep(300)
        assertEquals("the playing track has the link", 1, server.requests.size)
        assertEquals(ProgressiveDownloads.State.QUEUED, next.state)

        playing.bytes(bytes(100, 1)).end()
        eventually("the next one follows") { next.state == ProgressiveDownloads.State.COMPLETE }
        assertEquals(2, server.requests.size)
        reader.close()
    }

    @Test
    fun onAnUnmeteredNetworkTheNextTrackLoadsAlongside() {
        val playing = server.serve()
        play("1")
        server.serve().bytes(bytes(100, 2)).end()
        val next = downloads.prefetch(stream("2"))!!
        eventually("in parallel (D80)") { next.state == ProgressiveDownloads.State.COMPLETE }
        playing.end()
    }

    @Test
    fun aReResolvedStreamIsWhatARestartFetches() {
        server.serve().bytes(bytes(1_000, 1)).fail(FeedServer.timeout())
        server.serve().bytes(bytes(1_000, 2)).end()
        downloads.linkChanged(false)
        play("1")
        eventually("broken") { server.requests.size == 1 && downloads.written(key("1")) == 1_000L }
        downloads.register(stream("1").copy(uri = "https://jf.example/Audio/1/universal?fresh=1"))
        downloads.linkChanged(true)
        eventually("restarted") { server.requests.size == 2 }
        assertEquals("https://jf.example/Audio/1/universal?fresh=1", server.requests[1].uri.toString())
    }

    @Test
    fun anAccountsDownloadsGoWithItsPartsOnSignOut() {
        server.serve().bytes(bytes(1_000, 1))
        val reader = play("1")
        eventually("writing") { downloads.written(key("1")) == 1_000L }
        downloads.cancelAll("acc|")
        assertFalse(downloads.isProgressive(key("1")))
        val error = runCatching { reader.available(1_000L) }.exceptionOrNull()
        assertTrue("the reader hears it", error is IOException)
        reader.close()
        assertTrue(parts.listFiles().isNullOrEmpty())
    }

    @Test
    fun partsOfAnEarlierProcessAreWipedAtStartup() {
        parts.mkdirs()
        File(parts, "stale-1").writeBytes(ByteArray(10))
        ProgressiveDownloads(parts, server, { _, _, _, _ -> false })
        assertTrue(parts.listFiles().isNullOrEmpty())
    }
}
