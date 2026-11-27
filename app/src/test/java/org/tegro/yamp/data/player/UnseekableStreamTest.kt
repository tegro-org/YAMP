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
import org.tegro.yamp.data.player.fakes.ClockGain
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
    /** The share of the download on disk the cache reports. */
    private var share = 0.634f

    /** The downloads of converted streams as the engine sees them (D91). */
    private class Downloads : ProgressiveStreams {
        val registered = mutableSetOf<String>()
        val played = mutableListOf<String?>()
        var written = 0L
        var parked = false
        override fun register(stream: ResolvedStream) {
            stream.cacheKey?.takeIf { stream.progressive }?.let { registered += it }
        }
        override fun isProgressive(key: String) = key in registered
        override fun playing(key: String?) {
            played += key
        }
        override fun writtenTotal(key: String) = written
        override fun parkedAtBrokenGeneration(key: String) = parked
    }

    private val downloads = Downloads()
    private val ga = ClockGain(a)
    private val gb = ClockGain(b)
    private val engine = PlayerRepositoryImpl(
        a, b, ga, gb, TrackDownloadManager(app, OkHttpClient()), PlaybackWakeLockHelper(app),
        fullyCached = { it in onDisk },
        downloadShare = { _, _, _ -> share },
        progressive = downloads
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
        ResolvedStream("https://jf.example/Audio/$id/universal", cacheKey = key(id), durationMs = duration, progressive = true)

    private fun play(id: String, duration: Long? = ScriptedExoPlayer.TRACK_MS, at: Long = 0L, crossfade: Boolean = true) =
        engine.playTrack(id, stream(id, duration), id.uppercase(), "Artist", null, crossfade = crossfade, startPositionMs = at, pickAtMs = 0L)

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

    /**
     * Canvas 5e, D91: seeking waits for the whole download — the bars show its share, in whole percents. Complete
     * mid-track, nothing switches (no gap): the lock goes, the first seek reopens the item there, later seeks are plain.
     */
    @Test
    fun seekingUnlocksOnCompleteAndTheFirstSeekReopensThenSeeksArePlain() {
        b.transcode()
        firstTrackSounds()
        b.becomeReady()
        advance(600)
        assertEquals(0.63f, engine.seekLock.value!!, 0.0001f)
        assertFalse(engine.seekByReopen.value)

        share = 0.81f
        advance(600)
        assertEquals("it follows the download", 0.81f, engine.seekLock.value!!, 0.0001f)

        b.playedTo(40_000)
        val opened = b.tracksOpened
        onDisk += key("t1")
        advance(600)
        assertEquals(null, engine.seekLock.value)
        assertTrue("the session may seek now", engine.seekByReopen.value)
        assertEquals("no reopen at completion: it plays on", 40_000L, b.currentPosition)
        assertEquals("the same AudioTrack", opened, b.tracksOpened)
        assertTrue(b.seeks.isEmpty())

        engine.seekTo(150_000)
        looper.idle()
        assertEquals("the first seek reopens there", 150_000L, b.currentPosition)
        assertTrue("not a plain seek", b.seeks.isEmpty())
        b.seekable = true // reopened with its length
        b.becomeReady()
        advance(600)
        assertFalse(engine.seekByReopen.value)

        engine.seekTo(20_000)
        looper.idle()
        assertEquals("then it seeks by itself", listOf(20_000L), b.seeks.toList())
    }

    /** Paused when the download completes: nothing happens by itself; a seek reopens it. */
    @Test
    fun aPausedTracksCompleteCopyWaitsForTheSeek() {
        b.transcode()
        firstTrackSounds()
        b.becomeReady()
        advance(600)
        engine.pause()
        advance(600)
        onDisk += key("t1")
        advance(600)
        assertEquals(null, engine.seekLock.value)
        assertTrue(b.seeks.isEmpty())
    }

    @Test
    fun aSeekableStreamNeverLocksSeeking() {
        firstTrackSounds()
        advance(600)
        assertEquals(null, engine.seekLock.value)
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

    /** D67 on the download: a wait that brings no bytes for 20 s ends with "no data" at the target (a pause). */
    @Test
    fun aDownloadThatBringsNothingEndsTheWaitWithNoDataAtTheTarget() {
        b.transcode()
        val failures = collect(engine.playbackErrorEvent)
        firstTrackSounds()
        engine.seekTo(150_000)
        advance(PlayerRepositoryImpl.NO_DATA_TIMEOUT_MS + 1_000)
        assertEquals(150_000L, failures.single().positionMs)
        assertEquals(org.tegro.yamp.domain.repository.PlaybackFailure.NO_DATA, failures.single().reason)
        assertFalse(engine.isBuffering.value)
    }

    /** D67: a download that keeps bringing bytes never ends the wait, however long it takes. */
    @Test
    fun aMovingDownloadNeverEndsTheWait() {
        b.transcode()
        val failures = collect(engine.playbackErrorEvent)
        firstTrackSounds()
        engine.seekTo(150_000)
        repeat(60) {
            downloads.written += 10_000
            advance(1_000)
        }
        assertTrue(failures.isEmpty())
        assertTrue("still loading", engine.isBuffering.value)
        assertTrue("the written bytes count as loading progress", engine.loadingProgress() >= 600_000L)
        onDisk += key("t1")
        advance(600)
        assertEquals(150_000L, b.currentPosition)
        assertTrue(b.playWhenReady)
    }

    /** "Play continues the same wait": after the no-data pause, Play waits again at the same position, not from 0. */
    @Test
    fun playAfterTheNoDataPauseGoesOnWithTheSameWait() {
        b.transcode()
        firstTrackSounds()
        engine.seekTo(150_000)
        advance(PlayerRepositoryImpl.NO_DATA_TIMEOUT_MS + 1_000)
        engine.pause()
        assertFalse(engine.isPlaying.value)

        assertTrue(engine.resume())
        looper.idle()
        assertTrue("waiting again", engine.isBuffering.value)
        assertFalse("not sounding from 0", b.playWhenReady)
        assertEquals(150_000L, engine.progress.value)
        onDisk += key("t1")
        advance(600)
        assertEquals(150_000L, b.currentPosition)
        assertTrue(b.playWhenReady)
    }

    /**
     * A restart (D91): the player played on from the broken generation and has read all of it — it runs dry there.
     * Once the new generation is complete it reopens where it stopped: the one switch, only in this failure case.
     */
    @Test
    fun aPlayerParkedAtTheEndOfABrokenGenerationReopensThereOnceTheNextIsComplete() {
        b.transcode()
        firstTrackSounds()
        b.becomeReady()
        advance(600)
        b.playedTo(90_000)
        downloads.parked = true
        advance(600)
        assertEquals("still playing what it has: no early switch", "t1", b.itemId)
        assertTrue(b.isPlaying)

        b.prepare() // ran dry at the edge
        advance(600)
        assertFalse("waits, held at its position", b.playWhenReady)
        assertEquals(90_000L, engine.progress.value)
        onDisk += key("t1")
        advance(600)
        assertEquals("reopened where it stopped", 90_000L, b.currentPosition)
        assertTrue(b.playWhenReady)
    }

    /** A restored session or a retry at a position on an incomplete converted stream waits, then plays there. */
    @Test
    fun aStartAtAPositionWaitsForTheWholeDownloadThenPlaysThere() {
        b.transcode()
        play("t1", at = 120_000L, crossfade = false)
        settle { b.itemId == "t1" }
        assertFalse("not from 0", b.playWhenReady)
        assertTrue("loading meanwhile", engine.isBuffering.value)
        assertEquals(120_000L, engine.progress.value)
        assertEquals(listOf<String?>(key("t1")), downloads.played)

        onDisk += key("t1")
        advance(600)
        assertEquals(120_000L, b.currentPosition)
        assertTrue(b.playWhenReady)
    }

    @Test
    fun theDownloadsHearWhatIsHandedOutAndWhatPlays() {
        firstTrackSounds()
        assertTrue(key("t1") in downloads.registered)
        engine.stop()
        assertEquals("a stop leaves nothing playing", listOf(key("t1"), null), downloads.played)
    }

    @Test
    fun aJumpToTheStartThatNobodyAskedForGoesOnWhereItWasFromTheDiskCopy() {
        b.transcode()
        firstTrackSounds()
        onDisk += key("t1")
        advance(150)
        b.becomeReady()
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
        // The trigger comes one length before the end: the fade-out ends with the track
        b.playedTo(ScriptedExoPlayer.TRACK_MS - 4_000)
        advance(300)
        assertEquals("not yet at X before the end", 0, ends.size)
        b.playedTo(ScriptedExoPlayer.TRACK_MS - 2_900)
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
        assertTrue("t1 is fading", gb.level in 0.0001f..0.999f)
        advance(2_600)
        assertFalse("t1 stopped", b.isPlaying)
        assertTrue("t2 rises", a.isPlaying && ga.level < 1f)
        advance(3_100)
        assertEquals(1f, ga.level, 0.0001f)
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
        assertTrue("t1 faded out on its own player", gb.level < 0.01f)

        b.finishItem() // gapless: t2 plays on the same player, from silence
        looper.idle()
        play("t2")
        settle { engine.currentTrackId.value == "t2" }
        advance(300)
        assertTrue("t2 rises", gb.level in 0.0001f..0.999f)
        advance(3_000)
        assertEquals(1f, gb.level, 0.0001f)
        assertEquals("t2", b.itemId)
        assertEquals("no new AudioTrack", opened, b.tracksOpened)
    }

    /**
     * The b109 ride: with "Fade then rise" the next track always waits behind the playing one, so it never counted as
     * warm and every manual Next became a pick — the old player paused, the new one started in the same mixer cycle,
     * and under Poweramp's DVC the old track's last buffer popped up over the new one.
     */
    @Test
    fun aManualNextToTheTrackQueuedBehindTakesTheSkipPathAndNeverOverlaps() {
        engine.setTransitionFadeThenRise(true)
        looper.idle()
        firstTrackSounds()
        engine.prepareNextTrack("t2", stream("t2"), "T2", "Artist", null)
        settle { b.playlist.contains("t2") }
        assertTrue("the queued-behind track is ready for Next", engine.isWarm("t2"))

        val stopsBefore = b.stops
        engine.fadeOutForSkip()
        advance(PlayerRepositoryImpl.CROSSFADE_DURATION_MS + 100)
        assertFalse("t1 faded and stopped", b.isPlaying)
        assertTrue("t1's player was stopped (its buffer flushed)", b.stops > stopsBefore)
        assertTrue("its volume stays down until it has a new item", gb.level < 0.01f)

        val overlaps = mutableListOf<Boolean>()
        a.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) overlaps += b.isPlaying || b.playbackState != androidx.media3.common.Player.STATE_IDLE
            }
        })
        play("t2")
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(500)
        assertTrue("t2 sounds", a.isPlaying)
        assertEquals("t1 had fully stopped before t2 sounded", listOf(false), overlaps)
    }

    @Test
    fun fadeThenRiseStopsTheOldPlayerBeforeTheNewOneSounds() {
        engine.setTransitionFadeThenRise(true)
        looper.idle()
        firstTrackSounds()
        b.playedTo(ScriptedExoPlayer.TRACK_MS - 2_900)
        advance(200)
        val stoppedWhenStarted = mutableListOf<Boolean>()
        a.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) stoppedWhenStarted += b.playbackState == androidx.media3.common.Player.STATE_IDLE
            }
        })
        play("t2")
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(3_600)
        assertTrue("t2 sounds", a.isPlaying)
        assertEquals("t1 was stopped, not only paused, when t2 started", listOf(true), stoppedWhenStarted)
    }

    // endregion

    private companion object {
        const val SESSION = 9
    }
}
