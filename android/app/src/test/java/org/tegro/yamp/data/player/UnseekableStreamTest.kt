package org.tegro.yamp.data.player

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.data.player.fakes.ScriptedExoPlayer
import org.tegro.yamp.data.repository.PlayerRepositoryImpl
import org.tegro.yamp.domain.provider.ResolvedStream

/**
 * The 2026-11-17 ride: a Jellyfin transcode is a progressive stream sent with no length. Media3 can't seek in it
 * (a seek lands at 0), restarts it from 0 when its connection breaks mid-way, and gives it no duration, so the
 * end-of-track transition never fired. The engine now times the end by the catalog's length, seeks and recovers
 * from the track's whole file on disk — and the "Fade then rise" transition (no overlap).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UnseekableStreamTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val a = ScriptedExoPlayer(SESSION)
    private val b = ScriptedExoPlayer(SESSION)

    /** What is on disk in full, by cache key. */
    private val onDisk = mutableSetOf<String>()
    private val engine = PlayerRepositoryImpl(
        a, b, TrackDownloadManager(app, OkHttpClient()), PlaybackWakeLockHelper(app),
        fullyCached = { it in onDisk }
    )
    private val collectors = mutableListOf<kotlinx.coroutines.Job>()

    @After
    fun tearDown() {
        collectors.forEach { it.cancel() }
        engine.release()
    }

    private val looper get() = shadowOf(Looper.getMainLooper())

    private fun settle(done: () -> Boolean) {
        repeat(400) {
            looper.idle()
            if (done()) return
            Thread.sleep(5)
        }
        throw AssertionError("the engine did not get there")
    }

    private fun advance(ms: Long) = looper.idleFor(Duration.ofMillis(ms))

    private fun key(id: String) = "acc|$id|mp3-320"

    private fun stream(id: String, duration: Long? = ScriptedExoPlayer.TRACK_MS) =
        ResolvedStream("https://jf.example/Audio/$id/universal", cacheKey = key(id), durationMs = duration)

    private fun play(id: String, duration: Long? = ScriptedExoPlayer.TRACK_MS) =
        engine.playTrack(id, stream(id, duration), id.uppercase(), "Artist", null, crossfade = true, startPositionMs = 0L, pickAtMs = 0L)

    /** A transcode: no length, not seekable. */
    private fun ScriptedExoPlayer.transcode() {
        seekable = false
        lengthKnown = false
    }

    /** t1 sounds on player B (the first start goes to the standby slot); the progress loop has seen it. */
    private fun firstTrackSounds(duration: Long? = ScriptedExoPlayer.TRACK_MS) {
        play("t1", duration)
        settle { b.itemId == "t1" }
        b.becomeReady()
        looper.idle()
        assertTrue("t1 sounds", b.isPlaying)
        advance(600) // the progress loop polls every 500 ms while nothing plays
    }

    private fun <T> collect(flow: kotlinx.coroutines.flow.Flow<T>): List<T> {
        val seen = java.util.concurrent.CopyOnWriteArrayList<T>()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { flow.collect { seen += it } }
            .also { collectors += it }
        return seen
    }

    // region unseekable streams

    @Test
    fun aTranscodeWithNoLengthStillEndsOnTimeByTheCatalogLength() {
        b.transcode()
        val ends = collect(engine.trackEndedEvent)
        firstTrackSounds()
        b.playedTo(ScriptedExoPlayer.TRACK_MS - 2_000)
        advance(300)
        assertEquals("the transition fires by the track's own length", 1, ends.size)
    }

    @Test
    fun withNoLengthAnywhereNoTransitionFires() {
        b.transcode()
        val ends = collect(engine.trackEndedEvent)
        firstTrackSounds(duration = null)
        b.playedTo(ScriptedExoPlayer.TRACK_MS - 2_000)
        advance(300)
        assertEquals(0, ends.size)
    }

    @Test
    fun anUnseekableStreamIsReportedOnceSoItsWholeFileGoesToDisk() {
        b.transcode()
        val reported = collect(engine.unseekableStreamEvent)
        firstTrackSounds()
        b.becomeReady()
        looper.idle()
        assertEquals(listOf("t1"), reported.toList())
    }

    @Test
    fun aSeekableStreamIsNotReported() {
        val reported = collect(engine.unseekableStreamEvent)
        firstTrackSounds()
        assertTrue(reported.isEmpty())
    }

    @Test
    fun aSeekInAnUnseekableStreamWaitsForTheDiskCopyAndGoesOnFromThere() {
        b.transcode()
        firstTrackSounds()
        advance(3_000)

        engine.seekTo(150_000)
        looper.idle()
        assertFalse("not restarted from 0 while the copy is written", b.playWhenReady)
        assertTrue("loading meanwhile", engine.isBuffering.value)
        assertEquals("the bar holds the target", 150_000L, engine.progress.value)

        onDisk += key("t1")
        advance(600)
        assertEquals("reopened from disk where the user asked", 150_000L, b.currentPosition)
        assertTrue(b.playWhenReady)
        b.becomeReady()
        looper.idle()
        assertTrue(b.isPlaying)
        assertFalse(engine.isBuffering.value)
    }

    @Test
    fun aSeekInAnUnseekableStreamWithItsCopyOnDiskGoesThereAtOnce() {
        b.transcode()
        firstTrackSounds()
        onDisk += key("t1")
        engine.seekTo(150_000)
        looper.idle()
        assertEquals(150_000L, b.currentPosition)
        assertTrue(b.playWhenReady)
    }

    @Test
    fun aCopyThatNeverArrivesEndsTheWaitWithAFailureAtTheTarget() {
        b.transcode()
        val failures = collect(engine.playbackErrorEvent)
        firstTrackSounds()
        engine.seekTo(150_000)
        advance(PlayerRepositoryImpl.COPY_WAIT_MAX_MS + 1_000)
        assertEquals(150_000L, failures.single().positionMs)
        assertFalse(engine.isBuffering.value)
    }

    @Test
    fun aJumpToTheStartThatNobodyAskedForGoesOnWhereItWasFromTheDiskCopy() {
        b.transcode()
        firstTrackSounds()
        onDisk += key("t1")
        advance(3_000) // past the grace after the start
        b.playedTo(120_000)
        advance(300)
        // The connection broke mid-way: Media3 restarted the transcode from 0
        b.playedTo(300)
        advance(300)
        assertEquals("t1", b.itemId)
        assertEquals("back where it was", 120_000L, b.currentPosition)
        assertTrue(b.playWhenReady)
    }

    @Test
    fun aJumpToTheStartOfASeekableStreamSeeksBack() {
        firstTrackSounds()
        advance(3_000)
        b.playedTo(60_000)
        advance(300)
        b.playedTo(200)
        advance(300)
        assertTrue("sought back", b.seeks.contains(60_000L))
        assertEquals(60_000L, b.currentPosition)
    }

    @Test
    fun aUserSeekToTheStartIsNotAJump() {
        firstTrackSounds()
        advance(3_000)
        b.playedTo(60_000)
        advance(300)
        engine.seekTo(0)
        advance(300)
        assertEquals(0L, b.currentPosition)
        assertEquals(listOf(0L), b.seeks.toList())
    }

    @Test
    fun aReopenFromDiskLetsTheNextTrackBeQueuedAgain() {
        engine.setTransitionFadeThenRise(true)
        looper.idle()
        b.transcode()
        val prepares = collect(engine.prepareNextTrackEvent)
        firstTrackSounds()
        engine.prepareNextTrack("t2", stream("t2"), "T2", "Artist", null)
        settle { b.playlist.contains("t2") }
        onDisk += key("t1")
        engine.seekTo(190_000)
        looper.idle()
        assertEquals("the reopened item stands alone", listOf("t1"), b.playlist)
        assertEquals(190_000L, b.currentPosition)
        b.becomeReady()
        advance(600)
        assertTrue("the next track is asked for again", prepares.isNotEmpty())
    }

    // endregion

    // region Transition: fade then rise

    @Test
    fun fadeThenRiseNeverPlaysTheTwoTracksAtOnce() {
        engine.setTransitionFadeThenRise(true)
        looper.idle()
        val ends = collect(engine.trackEndedEvent)
        firstTrackSounds()
        // The trigger comes at half the length: the fade-out ends with the track
        b.playedTo(ScriptedExoPlayer.TRACK_MS - 2_000)
        advance(300)
        assertEquals("not yet at X/2 before the end", 0, ends.size)
        b.playedTo(ScriptedExoPlayer.TRACK_MS - 1_400)
        advance(200)
        assertEquals(1, ends.size)

        val overlaps = mutableListOf<Boolean>()
        a.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) overlaps += b.isPlaying
            }
        })
        play("t2")
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(700)
        assertFalse("the next one waits for the fade-out", a.isPlaying)
        assertTrue("t1 is fading", b.volume in 0.0001f..0.999f)
        advance(1_000)
        assertFalse("t1 stopped", b.isPlaying)
        assertTrue("t2 rises", a.isPlaying && a.volume < 1f)
        advance(1_600)
        assertEquals(1f, a.volume, 0.0001f)
        assertEquals("t1 was silent when t2 started", listOf(false), overlaps)
    }

    @Test
    fun fadeThenRiseWithTheNextTrackQueuedBehindKeepsOneAudioTrack() {
        engine.setTransitionFadeThenRise(true)
        looper.idle()
        firstTrackSounds()
        engine.prepareNextTrack("t2", stream("t2"), "T2", "Artist", null)
        settle { b.playlist.contains("t2") }
        val opened = b.tracksOpened

        b.playedTo(ScriptedExoPlayer.TRACK_MS - 1_400)
        advance(1_600)
        assertTrue("t1 faded out on its own player", b.volume < 0.01f)

        b.finishItem() // gapless: t2 plays on the same player, from silence
        looper.idle()
        play("t2")
        settle { engine.currentTrackId.value == "t2" }
        advance(300)
        assertTrue("t2 rises", b.volume in 0.0001f..0.999f)
        advance(1_600)
        assertEquals(1f, b.volume, 0.0001f)
        assertEquals("t2", b.itemId)
        assertEquals("no new AudioTrack", opened, b.tracksOpened)
    }

    // endregion

    private companion object {
        const val SESSION = 9
    }
}
