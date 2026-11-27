package org.tegro.yamp.data.player

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
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
import org.tegro.yamp.domain.repository.PlaybackFailure
import kotlinx.coroutines.launch

/**
 * The two-player engine with scripted players (docs/playback-stability.md).
 *
 * F2 — on the head unit (2026-11-16, 13:17–13:18) a pick given up for a newer one left its crossfade job running:
 * the old job later timed out the newer pick ("pick failed: READY_TIMEOUT") or finished "its" crossfade on players
 * a newer start owned, so the screen showed paused over audible music.
 * F9 — both players keep the one audio session the whole time (D70).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerEngineTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val a = ScriptedExoPlayer(SESSION)
    private val b = ScriptedExoPlayer(SESSION)
    private val ga = ClockGain(a)
    private val gb = ClockGain(b)
    private val engine = PlayerRepositoryImpl(a, b, ga, gb, TrackDownloadManager(app, OkHttpClient()), PlaybackWakeLockHelper(app))

    @After
    fun tearDown() {
        collectors.forEach { it.cancel() }
        engine.release()
    }

    private val looper get() = shadowOf(Looper.getMainLooper())

    /** Runs the main thread until [done]: the engine starts its work in coroutines on the main thread. */
    private fun settle(done: () -> Boolean) {
        repeat(400) {
            looper.idle()
            if (done()) return
            Thread.sleep(5)
        }
        throw AssertionError("the engine did not get there")
    }

    private fun advance(ms: Long) = looper.idleFor(Duration.ofMillis(ms))

    private fun play(id: String, pick: Boolean = false) =
        engine.playTrack(id, org.tegro.yamp.domain.provider.ResolvedStream("https://stream.example/$id.mp3"), id.uppercase(), "Artist", null,
            crossfade = true, startPositionMs = 0L, pickAtMs = if (pick) SystemClock.elapsedRealtime() else 0L)

    /** t1 sounds on player B: the first start goes to the standby slot and plays at once (nothing audible before). */
    private fun firstTrackSounds() {
        play("t1")
        settle { b.itemId == "t1" }
        b.becomeReady()
        looper.idle()
        assertTrue("t1 sounds", b.isPlaying)
    }

    @Test
    fun aPickGivenUpForANewerOneNeverTimesTheNewerOneOut() {
        firstTrackSounds()
        play("t2", pick = true)
        settle { a.itemId == "t2" }
        advance(5_000)
        // A newer pick while t2 still loads: t2 is given up, t3 loads on the same player
        play("t3", pick = true)
        settle { a.itemId == "t3" }
        // Past t2's own 8 s limit: its job, had it survived, would give up t3 here
        advance(4_000)
        assertEquals("t3 still loading", "t3", a.itemId)
        a.becomeReady()
        advance(PlayerRepositoryImpl.CROSSFADE_DURATION_MS + 1_000)

        assertEquals("t3", engine.currentTrackId.value)
        assertTrue("t3 sounds", a.isPlaying)
        assertTrue("the screen says playing", engine.isPlaying.value)
        assertEquals("t1 faded out under it", null, b.itemId)
    }

    @Test
    fun theSoundingPlayerIsNeverStoppedByAnOldTransition() {
        firstTrackSounds()
        play("t2", pick = true)
        settle { a.itemId == "t2" }
        play("t3", pick = true)
        settle { a.itemId == "t3" }
        a.becomeReady()
        advance(PlayerRepositoryImpl.CROSSFADE_DURATION_MS + 1_000)
        // Then a plain start on the other player: t4 sounds on B
        play("t4")
        settle { b.itemId == "t4" }
        b.becomeReady()
        looper.idle()
        val stopsOfB = b.stops
        advance(15_000)

        assertEquals("t4", engine.currentTrackId.value)
        assertEquals("nothing stopped the track that sounds", stopsOfB, b.stops)
        assertTrue(b.isPlaying)
        assertTrue("the screen says playing", engine.isPlaying.value)
    }

    @Test
    fun bothPlayersKeepTheOneSessionThroughPicksSwapsAndStops() {
        firstTrackSounds()
        play("t2", pick = true)
        settle { a.itemId == "t2" }
        play("t3", pick = true)
        settle { a.itemId == "t3" }
        a.becomeReady()
        advance(PlayerRepositoryImpl.CROSSFADE_DURATION_MS + 1_000)
        play("t4")
        settle { b.itemId == "t4" }
        b.becomeReady()
        advance(4_000) // t3 crossfades out under t4
        engine.prepareNextTrack("t5", org.tegro.yamp.domain.provider.ResolvedStream("https://stream.example/t5.mp3"), "T5", "Artist", null)
        settle { a.itemId == "t5" }
        engine.dropStandby()
        looper.idle()

        assertEquals(SESSION, a.audioSessionId)
        assertEquals(SESSION, b.audioSessionId)
        assertEquals("never moved to another session", emptyList<Int>(), a.sessionChanges + b.sessionChanges)
    }

    @Test
    fun aSlowPickKeepsLoadingWhileDataArrives() {
        val failures = collectFailures()
        firstTrackSounds()
        play("t2", pick = true)
        settle { a.itemId == "t2" }
        // 30 s of a trickle: well past the old 8 s limit and the 20 s no-data window, but never 20 s without data
        repeat(15) {
            a.loaded += 500
            advance(2_000)
        }
        assertEquals("still loading t2, nothing given up", "t2", a.itemId)
        assertTrue("t1 plays on meanwhile", b.isPlaying)
        assertEquals(emptyList<PlaybackFailure>(), failures)
        a.becomeReady()
        advance(1_000)
        assertEquals("t2", engine.currentTrackId.value)
        assertTrue(a.isPlaying)
    }

    @Test
    fun aPickWithNoDataFor20sIsReportedNotRevertedOrSkipped() {
        val failures = collectFailures()
        firstTrackSounds()
        play("t2", pick = true)
        settle { a.itemId == "t2" }
        advance(PlayerRepositoryImpl.NO_DATA_TIMEOUT_MS + 500)
        assertEquals(listOf(PlaybackFailure.NO_DATA), failures.map { it.reason })
        assertEquals("the pick stays the current track (no going back)", "t2", engine.currentTrackId.value)
        assertEquals("t2", a.itemId)
    }

    // region F4 — fewer AudioTrack rebuilds (D68)

    @Test
    fun withoutCrossfadeTheNextTrackPlaysOnTheSameAudioTrack() {
        engine.setCrossfadeDuration(0L)
        val ended = collectTrackEnds()
        firstTrackSounds()
        val stopsBefore = b.stops
        engine.prepareNextTrack("t2", org.tegro.yamp.domain.provider.ResolvedStream("https://stream.example/t2.mp3"), "T2", "Artist", null)
        settle { b.playlist == listOf("t1", "t2") }

        b.finishItem() // t1 played to its end: the player moves on to t2 by itself
        looper.idle()
        assertEquals("the controller hears the track end", 1, ended.size)
        play("t2") // ...and starts t2, which the engine takes over as it is
        settle { engine.currentTrackId.value == "t2" }

        assertEquals("t2", b.itemId)
        assertEquals("the played track left the playlist", listOf("t2"), b.playlist)
        assertEquals("one AudioTrack for both tracks", 1, b.tracksOpened)
        assertEquals("the other player was never used", 0, a.tracksOpened)
        assertEquals("no stop between the two tracks", stopsBefore, b.stops)
        assertTrue(b.isPlaying)
    }

    @Test
    fun aSkipTakesTheQueuedTrackOffSoTheFadingOneCannotPlayIntoIt() {
        engine.setCrossfadeDuration(0L)
        firstTrackSounds()
        engine.prepareNextTrack("t2", org.tegro.yamp.domain.provider.ResolvedStream("https://stream.example/t2.mp3"), "T2", "Artist", null)
        settle { b.playlist == listOf("t1", "t2") }
        engine.fadeOutForSkip()
        looper.idle()
        assertEquals(listOf("t1"), b.playlist)
    }

    @Test
    fun aCrossfadeOpensOneAudioTrackAndStopsTheOutgoingPlayer() {
        firstTrackSounds()
        engine.prepareNextTrack("t2", org.tegro.yamp.domain.provider.ResolvedStream("https://stream.example/t2.mp3"), "T2", "Artist", null)
        settle { a.itemId == "t2" }
        a.becomeReady()
        play("t2")
        settle { engine.currentTrackId.value == "t2" }
        advance(PlayerRepositoryImpl.CROSSFADE_DURATION_MS + 1_000)

        assertEquals("one AudioTrack per track: t1 on B, t2 on A", 1 + 1, a.tracksOpened + b.tracksOpened)
        assertTrue("t2 sounds", a.isPlaying)
        assertEquals("t1 is stopped, not left playing or rebuilt as silence", null, b.itemId)
    }

    @Test
    fun aBurstOfSkipsBuildsOneAudioTrackPerTrack() {
        startAndSound(engine, b, "t1")
        var started = 1
        repeat(6) { n ->
            val id = "s$n"
            engine.fadeOutForSkip()
            advance(300)
            playOn(engine, id)
            settle { a.itemId == id || b.itemId == id }
            val incoming = if (a.itemId == id) a else b
            incoming.becomeReady()
            advance(400)
            started++
        }
        assertEquals("one AudioTrack per started track", started, a.tracksOpened + b.tracksOpened)
    }

    @Test
    fun theOutgoingPlayerIsNotTurnedUpWhileItsLastBufferStillDrains() {
        firstTrackSounds()
        engine.fadeOutForSkip()
        advance(520) // the 500 ms skip fade is over: t1 is cleared
        assertTrue("the faded player was stopped", b.stops > 0)
        assertTrue("…and stays silent while it drains (no pop)", gb.level < 0.05f)
        advance(600)
        // Its next track is not muted by the hold: t2 on A, then t3 back on B sounds at full volume
        play("t2")
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(4_000)
        play("t3")
        settle { b.itemId == "t3" }
        b.becomeReady()
        advance(4_000)
        assertTrue("t3 sounds", b.isPlaying)
        assertEquals("t3 at full volume", 1f, gb.level)
    }

    @Test
    fun aTrackPlayedEarlierStartsFromTheBeginningWhenSkippedBackTo() {
        firstTrackSounds()
        engine.prepareNextTrack("t2", org.tegro.yamp.domain.provider.ResolvedStream("https://stream.example/t2.mp3"), "T2", "Artist", null)
        settle { a.itemId == "t2" || b.playlist.contains("t2") }
        val holder = if (a.itemId == "t2") a else b
        holder.becomeReady()
        // The kept player still holds t2 where it was left half a minute in
        if (holder === a) a.playedTo(30_000)
        play("t2")
        settle { engine.currentTrackId.value == "t2" }
        looper.idle()
        assertEquals("t2 starts at 0:00", 0L, holder.currentPosition)
    }

    // region b83 ride — no overlap on a skip/pick (Poweramp DVC ignores a player's own volume), handover timeout

    /** Records whether [other] was still sounding at any moment [incoming] started to sound. */
    private fun overlapProbe(incoming: ScriptedExoPlayer, other: ScriptedExoPlayer): List<Boolean> {
        val seen = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        incoming.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) seen += other.isPlaying
            }
        })
        return seen
    }

    @Test
    fun aSkipNeverPlaysTheOldTrackUnderTheNewOne() {
        firstTrackSounds()
        val overlaps = overlapProbe(a, b)
        engine.fadeOutForSkip()
        advance(50) // the skip fade still runs when the next track is there
        play("t2")
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(500)

        assertTrue("t2 sounds", a.isPlaying)
        assertEquals("t1 was silent before t2 sounded", listOf(false), overlaps)
        assertTrue("t1 is not left playing", !b.isPlaying)
    }

    @Test
    fun aFadedSkipStopsTheOldTrackInsteadOfPlayingItAtVolumeZero() {
        firstTrackSounds()
        engine.fadeOutForSkip()
        advance(PlayerRepositoryImpl.CROSSFADE_DURATION_MS + 200)
        assertTrue("the faded track does not play on silently", !b.isPlaying && !b.playWhenReady)
        assertTrue("the engine still says playing, loading", engine.isPlaying.value)
    }

    @Test
    fun aReadyPickStartsOnlyAfterTheCurrentTrackStopped() {
        engine.setTransitionFadeThenRise(true)
        looper.idle()
        firstTrackSounds()
        val overlaps = overlapProbe(a, b)
        play("t2", pick = true)
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(PlayerRepositoryImpl.CROSSFADE_DURATION_MS + 1_000)

        assertTrue("t2 sounds", a.isPlaying)
        assertEquals("t1 had stopped before t2 sounded", listOf(false), overlaps)
        assertEquals("t2", engine.currentTrackId.value)
    }

    @Test
    fun aReadyPickWaitsUntilTheOldPlayerIsStoppedAndFlushed() {
        engine.setTransitionFadeThenRise(true)
        looper.idle()
        firstTrackSounds()
        val stoppedWhenStarted = mutableListOf<Boolean>()
        a.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) stoppedWhenStarted += b.playbackState == androidx.media3.common.Player.STATE_IDLE
            }
        })
        play("t2", pick = true)
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(PlayerRepositoryImpl.CROSSFADE_DURATION_MS + 1_000)
        assertTrue("t2 sounds", a.isPlaying)
        assertEquals("t1 was stopped (not only paused) when t2 started", listOf(true), stoppedWhenStarted)
    }

    /**
     * b148 car ride (2026-11-20 12:07:18, 12:08:22): between the old track's stop and the pick's start the engine
     * said "not playing", so the service abandoned audio focus and asked for it again ~100 ms later — the head unit
     * answers that with a pop in the silence of the handover.
     */
    @Test
    fun aReadyPickNeverSaysNotPlayingDuringTheHandover() {
        firstTrackSounds()
        val states = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            engine.isPlaying.collect { states += it }
        }.also { collectors += it }
        play("t2", pick = true)
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(1_000)

        assertTrue("t2 sounds", a.isPlaying)
        assertFalse("never 'not playing' on the way: $states", states.contains(false))
    }

    @Test
    fun aSkipToAReadyTrackNeverSaysNotPlayingDuringTheHandover() {
        firstTrackSounds()
        val states = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            engine.isPlaying.collect { states += it }
        }.also { collectors += it }
        engine.fadeOutForSkip()
        advance(50) // the skip fade still runs when the next track is there, ready
        play("t2")
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(1_000)

        assertTrue("t2 sounds", a.isPlaying)
        assertFalse("never 'not playing' on the way: $states", states.contains(false))
    }

    // endregion

    private fun playOn(engine: PlayerRepositoryImpl, id: String) =
        engine.playTrack(id, org.tegro.yamp.domain.provider.ResolvedStream("https://stream.example/$id.mp3"), id.uppercase(), "Artist", null,
            crossfade = true, startPositionMs = 0L, pickAtMs = 0L)

    private fun startAndSound(engine: PlayerRepositoryImpl, on: ScriptedExoPlayer, id: String) {
        playOn(engine, id)
        settle { on.itemId == id }
        on.becomeReady()
        looper.idle()
        assertTrue("$id sounds", on.isPlaying)
    }

    private fun collectTrackEnds(): List<Unit> {
        val seen = java.util.concurrent.CopyOnWriteArrayList<Unit>()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            engine.trackEndedEvent.collect { seen += it }
        }.also { collectors += it }
        return seen
    }

    // endregion

    // region D74 — the engine plays what it is handed and never resolves

    @Test
    fun aStartWithoutAStreamAndNoWarmStandbyFailsInsteadOfResolving() {
        val failures = collectFailures()

        engine.playTrack("t9", null, "T9", "Artist", null, crossfade = true, startPositionMs = 0L, pickAtMs = 0L)
        settle { failures.isNotEmpty() }

        assertEquals("EMPTY_URL", failures.single().reason)
        assertEquals(null, a.itemId)
        assertEquals(null, b.itemId)
    }

    @Test
    fun aStandbyWhoseStreamBrokeIsReportedSoItsLinkIsAskedAfresh() {
        val stale = collectStaleStreams()
        firstTrackSounds()
        engine.prepareNextTrack("t2", org.tegro.yamp.domain.provider.ResolvedStream("https://stream.example/t2.mp3"), "T2", "Artist", null)
        settle { a.itemId == "t2" }

        a.failStream()
        looper.idle()

        assertEquals(listOf("t2"), stale.toList())
        assertFalse("a broken standby is never handed to a transition", engine.isWarm("t2"))
    }

    private fun collectStaleStreams(): List<String> {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            engine.staleStreamEvent.collect { seen += it }
        }.also { collectors += it }
        return seen
    }

    // endregion

    /** Failures the engine reports to the controller, from now on. */
    private fun collectFailures(): List<PlaybackFailure> {
        val seen = java.util.concurrent.CopyOnWriteArrayList<PlaybackFailure>()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            engine.playbackErrorEvent.collect { seen += it }
        }.also { collectors += it }
        return seen
    }

    private val collectors = mutableListOf<kotlinx.coroutines.Job>()

    // region D98 — fades in the samples. b152 (head unit): Poweramp's Dynamics Processing on the session ignored the
    // player volume, so no fade was heard, the old player was paused mid-waveform and its full AudioTrack buffer
    // played out over the new track (~80 ms pop)

    /** Records, at every stop of [player], whether everything its [gain] still had for the speaker was silence. */
    private fun silenceAtStops(player: ScriptedExoPlayer, gain: ClockGain) = mutableListOf<Boolean>().also { seen ->
        player.stopListeners += { seen += gain.outputSilent || !player.isPlaying }
    }

    /** When [incoming] starts sounding: whether [old] still sounds, and [incoming]'s level then. */
    private fun startsOf(incoming: ScriptedExoPlayer, gain: ClockGain, old: ScriptedExoPlayer) = mutableListOf<Pair<Boolean, Float>>().also { seen ->
        incoming.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) seen += old.isPlaying to gain.level
            }
        })
    }

    @Test
    fun aPickStopsTheOldPlayerOnlyAfterItsSilenceWasHeard_andTheNewOneRisesFromZero() {
        engine.setTransitionFadeThenRise(true)
        looper.idle()
        firstTrackSounds()
        val stops = silenceAtStops(b, gb)
        val starts = startsOf(a, ga, b)
        play("t2", pick = true)
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(PlayerRepositoryImpl.CROSSFADE_DURATION_MS + 20)
        assertFalse("not yet: the fade's silence is still on its way to the speaker", a.isPlaying)
        assertTrue("the old one still plays out its faded buffer", b.isPlaying)
        advance(PlayerRepositoryImpl.CROSSFADE_DURATION_MS + 500)

        assertTrue("t2 sounds", a.isPlaying)
        assertTrue("t1 was stopped", stops.isNotEmpty())
        assertTrue("…only once all it had left was silence: $stops", stops.all { it })
        assertEquals("t2 started after t1 stopped, from silence", listOf(false to 0f), starts)
        assertEquals("then rose to full", 1f, ga.level)
    }

    @Test
    fun theB152Pop_aSkipToAReadyTrackNeverStartsItOverTheOldBuffer() {
        firstTrackSounds()
        val stops = silenceAtStops(b, gb)
        val starts = startsOf(a, ga, b)
        engine.fadeOutForSkip()
        advance(50) // the skip fade still runs when the next track is there, ready
        play("t2")
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(2 * PlayerRepositoryImpl.CROSSFADE_DURATION_MS + 1_000) // the old one fades out over the crossfade length, then the new one rises over it

        assertTrue("t2 sounds", a.isPlaying)
        assertTrue("t1 stopped only when silent: $stops", stops.isNotEmpty() && stops.all { it })
        assertEquals("t2 started after t1, from silence", listOf(false to 0f), starts)
        assertEquals(1f, ga.level)
    }

    @Test
    fun aSkipWithTheCrossfadeOnOverlapsTheTwoOverTheCrossfadeLength() {
        firstTrackSounds()
        engine.fadeOutForSkip(overlap = true)
        advance(100)
        assertTrue("t1 plays on while the next one comes", b.isPlaying && gb.level > 0.99f)
        play("t2")
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(PlayerRepositoryImpl.CROSSFADE_DURATION_MS / 2 + ClockGain.LATENCY_MS)
        assertTrue("both sound: an overlap", a.isPlaying && b.isPlaying)
        advance(PlayerRepositoryImpl.CROSSFADE_DURATION_MS)
        assertFalse("t1 stopped at the end", b.isPlaying)
        assertEquals(1f, ga.level)
    }

    @Test
    fun aSkipDuringALongFadeCutsItShortInsteadOfWaitingForItsEnd() {
        engine.setTransitionFadeThenRise(true)
        engine.setCrossfadeDuration(15_000L)
        looper.idle()
        firstTrackSounds()
        advance(100) // its start rise is over: a full level to fade from
        engine.fadeOutForSkip()
        advance(2_000)
        engine.fadeOutForSkip() // skipped again, 13 s of the first fade left
        play("t2")
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(PlayerRepositoryImpl.RAPID_RAMP_MS + ClockGain.LATENCY_MS + 500)
        assertTrue("the latest target starts at once", a.isPlaying)
        assertFalse("the interrupted fade was cut", b.isPlaying)
    }

    @Test
    fun aPauseDuringASkipsFadeOutStopsTheOldTrackAndDropsTheStart() {
        engine.setTransitionFadeThenRise(true)
        engine.setCrossfadeDuration(15_000L)
        looper.idle()
        firstTrackSounds()
        advance(100) // its start rise is over: a full level to fade from
        engine.fadeOutForSkip()
        play("t2")
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(1_000)
        assertTrue("the old track is fading out", b.isPlaying)
        engine.pause()
        looper.idle()
        advance(20_000)

        assertFalse("the old track did not play on", b.isPlaying)
        assertFalse("the new track did not start after the pause", a.isPlaying || a.playWhenReady)
    }

    @Test
    fun aSkipDuringTheRiseAfterASkipIsRapidToo() {
        engine.setTransitionFadeThenRise(true)
        engine.setCrossfadeDuration(4_000L)
        looper.idle()
        firstTrackSounds()
        advance(100) // its start rise is over: a full level to fade from
        engine.fadeOutForSkip()
        play("t2")
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(4_000 + ClockGain.LATENCY_MS + 3_000) // t2 is three quarters up its rise
        assertTrue(a.isPlaying && ga.level in 0.05f..0.9f)
        engine.fadeOutForSkip()
        play("t3")
        settle { b.itemId == "t3" }
        b.becomeReady()
        advance(PlayerRepositoryImpl.RAPID_RAMP_MS + ClockGain.LATENCY_MS + 300)

        assertTrue("the latest target starts at once, not after the rise's own fade-out", b.isPlaying)
    }

    @Test
    fun aSkipWhileTheOldTrackStillFadesOutCutsItShortAndNeverStopsItMidWaveform() {
        engine.setTransitionFadeThenRise(true)
        engine.setCrossfadeDuration(15_000L)
        looper.idle()
        firstTrackSounds()
        advance(100)
        val stops = silenceAtStops(b, gb)
        engine.fadeOutForSkip()
        play("t2")
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(1_000) // t1 is a second into its 15 s fade-out, t2 waits for it
        engine.fadeOutForSkip()
        play("t3")
        var waited = 0
        while (!(a.itemId == "t3" && a.isPlaying) && waited < 1_500) { advance(50); waited += 50; a.becomeReady() }

        assertTrue("t3 sounds within a moment, not after t1's 15 s fade", a.isPlaying && a.itemId == "t3")
        assertTrue("t1 was stopped only when silent: $stops", stops.isNotEmpty() && stops.all { it })
        assertFalse(b.isPlaying)
    }

    @Test
    fun aHardCutStillFadesTheOldTrackToSilenceBeforeTheNewOne() {
        engine.setCrossfadeDuration(0L) // transitions off, or the Audio Router's hard cut
        firstTrackSounds()
        val stops = silenceAtStops(b, gb)
        val starts = startsOf(a, ga, b)
        play("t2")
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(PlayerRepositoryImpl.CUT_FADE_MS + ClockGain.LATENCY_MS + 100)

        assertTrue(a.isPlaying)
        assertTrue("t1 stopped only when silent: $stops", stops.isNotEmpty() && stops.all { it })
        assertEquals(listOf(false to 0f), starts)
        advance(PlayerRepositoryImpl.HANDOVER_RISE_MS + 10)
        assertEquals("a cut: full level at once", 1f, ga.level)
    }

    @Test
    fun aCrossfadeOverlapsTheTwoAsHeard_theIncomingStartsOneOutputBufferLater() {
        firstTrackSounds()
        val stops = silenceAtStops(b, gb)
        play("t2")
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(ClockGain.LATENCY_MS / 2)
        assertFalse("waits while t1's fade is still on its way to the speaker", a.isPlaying)
        assertTrue("still playing", engine.isPlaying.value)
        assertTrue(gb.level < 1f)
        advance(ClockGain.LATENCY_MS)
        assertTrue("then starts", a.isPlaying)
        advance(1_450) // half the crossfade into t2's rise
        assertTrue("both sound: an overlap", a.isPlaying && b.isPlaying)
        assertEquals("t2 at sin 45°", 0.707f, ga.level, 0.03f)
        assertTrue("t1 a buffer further down", gb.level in 0.6f..0.7f)
        advance(2_000)

        assertFalse("t1 stopped at the end", b.isPlaying)
        assertTrue("…once silent: $stops", stops.isNotEmpty() && stops.all { it })
        assertEquals(1f, ga.level)
    }

    @Test
    fun aPlayersOwnVolumeIsNeverTouched() {
        firstTrackSounds()
        play("t2", pick = true)
        settle { a.itemId == "t2" }
        a.becomeReady()
        advance(PlayerRepositoryImpl.CROSSFADE_DURATION_MS + 1_000)
        play("t3")
        settle { b.itemId == "t3" }
        b.becomeReady()
        advance(4_000) // a crossfade
        engine.pauseSmoothly()
        advance(2_000)
        engine.resumeSmoothly()
        advance(2_000)
        engine.seekSmoothly(30_000L)
        advance(4_000)
        engine.setDucked(true)
        looper.idle()
        engine.fadeOutForSkip()
        advance(1_000)

        assertEquals("no volume set on A", emptyList<Float>(), a.volumes)
        assertEquals("no volume set on B", emptyList<Float>(), b.volumes)
        assertEquals(1f, a.volume)
        assertEquals(1f, b.volume)
        assertEquals("ducking is in the samples", PlayerRepositoryImpl.DUCK_VOLUME, gb.scale, 0f)
    }

    @Test
    fun aSmoothPauseStopsTheSoundOnlyAfterItsSilenceWasHeard() {
        firstTrackSounds()
        val pausedSilent = mutableListOf<Boolean>()
        b.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) pausedSilent += gb.outputSilent
            }
        })
        engine.pauseSmoothly()
        advance(1_000 + ClockGain.LATENCY_MS / 2)
        assertTrue("faded, its silence not heard yet", b.isPlaying && gb.level == 0f)
        advance(ClockGain.LATENCY_MS)
        assertFalse(b.isPlaying)
        assertEquals(listOf(true), pausedSilent)
    }

    // endregion

    // Smooth pause / play / seek: the play/pause length P (1 s by default) and the seek length D (0.5 s)

    @Test
    fun aPauseFadesOutOverThePlayPauseLengthAndSaysPausedAtOnce() {
        firstTrackSounds()
        engine.pauseSmoothly()
        looper.idle()
        assertFalse("the screen says paused at once", engine.isPlaying.value)
        assertTrue("the sound still fades", b.isPlaying)
        advance(500)
        assertTrue("half way down (dB curve: -30 dB at half time)", gb.level in 0.02f..0.05f)
        advance(700)
        assertFalse("paused after P", b.isPlaying)
        assertEquals(0f, gb.level)
        assertFalse(engine.isPlaying.value)
    }

    @Test
    fun aPlayDuringThePauseFadeRisesFromWhereTheSoundIs() {
        firstTrackSounds()
        engine.pauseSmoothly()
        advance(750)
        val level = gb.level
        assertTrue(engine.resumeSmoothly())
        looper.idle()
        assertTrue("playing again at once", engine.isPlaying.value)
        assertTrue("never stopped", b.isPlaying)
        advance(40)
        assertTrue("goes up from the level it had, no restart from silence", gb.level >= level)
        advance(2_000)
        assertEquals(1f, gb.level)
        assertTrue(b.isPlaying)
    }

    @Test
    fun aResumeRisesFromSilenceOverThePlayPauseLength() {
        firstTrackSounds()
        engine.pauseSmoothly()
        advance(2_000)
        assertFalse(b.isPlaying)
        assertTrue(engine.resumeSmoothly())
        looper.idle()
        assertTrue(b.isPlaying)
        assertTrue("starts silent", gb.level < 0.1f)
        advance(500)
        assertTrue("half way up (dB curve: -30 dB at half time)", gb.level in 0.02f..0.05f)
        advance(700)
        assertEquals(1f, gb.level)
    }

    @Test
    fun aSeekFadesOutHalfJumpsAndRisesHalf() {
        engine.setSeekDuration(2_000L)
        firstTrackSounds()
        engine.seekSmoothly(60_000L)
        looper.idle()
        assertEquals("the bar jumps at once", 60_000L, engine.progress.value)
        advance(500)
        assertTrue("fading out (dB curve: -30 dB at half time)", gb.level in 0.02f..0.05f)
        assertEquals("no jump yet", emptyList<Long>(), b.seeks)
        advance(700)
        assertEquals("jumped after D/2", listOf(60_000L), b.seeks)
        advance(500)
        assertTrue("rising (dB curve, about -27 dB just past half)", gb.level in 0.02f..0.08f)
        advance(700)
        assertEquals(1f, gb.level)
        assertTrue(b.isPlaying)
    }

    @Test
    fun aSecondSeekWhileFadingOutJumpsOnceToTheNewestPosition() {
        engine.setSeekDuration(2_000L)
        firstTrackSounds()
        engine.seekSmoothly(60_000L)
        advance(300)
        engine.seekSmoothly(90_000L)
        advance(1_000)
        assertEquals("one jump, to the newest position", listOf(90_000L), b.seeks)
        advance(2_500)
        assertEquals(1f, gb.level)
    }

    @Test
    fun theBarStaysOnTheSeekTargetWhileTheOldPositionFadesOut() {
        engine.setSeekDuration(2_000L)
        firstTrackSounds()
        b.playedTo(5_000L)
        engine.seekSmoothly(60_000L)
        advance(900) // past the old 600 ms stale-position window, before the jump at D/2
        assertEquals("no jump yet", emptyList<Long>(), b.seeks)
        assertEquals("the bar stays on the target, not on the old position", 60_000L, engine.progress.value)
        advance(300)
        assertEquals(listOf(60_000L), b.seeks)
        assertEquals(60_000L, engine.progress.value)
    }

    @Test
    fun aSecondSeekWhileFadingOutMovesTheBarToTheNewTarget() {
        engine.setSeekDuration(2_000L)
        firstTrackSounds()
        b.playedTo(5_000L)
        engine.seekSmoothly(60_000L)
        advance(300)
        engine.seekSmoothly(90_000L)
        advance(500)
        assertEquals(90_000L, engine.progress.value)
    }

    @Test
    fun aSkipDuringTheSeekFadeDropsThePendingJump() {
        engine.setSeekDuration(2_000L)
        firstTrackSounds()
        engine.seekSmoothly(60_000L)
        advance(300)
        engine.fadeOutForSkip()
        advance(2_000)
        assertEquals("the skipped track never jumps", emptyList<Long>(), b.seeks)
    }

    @Test
    fun aPauseDuringTheSeekFadeKeepsTheSeek() {
        engine.setSeekDuration(2_000L)
        firstTrackSounds()
        engine.seekSmoothly(60_000L)
        advance(300)
        engine.pauseSmoothly()
        looper.idle()
        assertEquals("the jump happens now instead of being lost", listOf(60_000L), b.seeks)
        advance(2_000)
        assertFalse(b.isPlaying)
    }

    @Test
    fun withTheLengthsAtZeroNothingFades() {
        engine.setCrossfadeDuration(0L)
        engine.setPlayPauseDuration(0L)
        engine.setSeekDuration(0L)
        firstTrackSounds()
        engine.pauseSmoothly()
        looper.idle()
        assertFalse("paused at once", b.isPlaying)
        assertTrue(engine.resumeSmoothly())
        looper.idle()
        assertEquals("full volume at once", 1f, gb.level)
        engine.seekSmoothly(30_000L)
        looper.idle()
        assertEquals("jumped at once", listOf(30_000L), b.seeks)
        assertEquals(1f, gb.level)
    }

    @Test
    fun aFocusLossPausesAtOnceEvenDuringARise() {
        firstTrackSounds()
        engine.pauseSmoothly()
        advance(2_000)
        engine.resumeSmoothly()
        advance(300) // rising
        engine.pause() // a focus loss or a call: the plain pause
        looper.idle()
        assertFalse("stopped at once", b.isPlaying)
        val after = gb.level
        advance(2_000)
        assertEquals("the rise did not go on", after, gb.level)
    }

    private companion object {
        const val SESSION = 77
    }
}
