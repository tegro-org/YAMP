package org.tegro.yamp.data.player

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.tegro.yamp.data.queue.ActiveQueue
import org.tegro.yamp.domain.media.ArtistBrief
import org.tegro.yamp.domain.media.Cover
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.queue.PlayQueue
import org.tegro.yamp.domain.queue.QueueEntry
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.queue.TrackRef

/**
 * Android Auto's «Очередь» showed one track (b152): the session's timeline was the engine's one or two loaded items.
 * Now it is YAMP's queue around the cursor; a tap on a row is a pick in YAMP's queue, and the platform (legacy)
 * session publishes the same rows as its queue.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionQueueTest {

    /** The engine's player: only the sounding track loaded, as the engine does. */
    private class Engine(var trackId: String?) : SimpleBasePlayer(Looper.getMainLooper()) {
        private var playing = false

        override fun getState(): State = State.Builder()
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(
                listOfNotNull(trackId).map { id ->
                    MediaItemData.Builder(id)
                        .setMediaItem(MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder().setTitle("engine $id").build()).build())
                        .setDurationUs(200_000_000L)
                        .setIsSeekable(true)
                        .build()
                }
            )
            .build()

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            playing = playWhenReady
            return Futures.immediateVoidFuture()
        }

        fun changed() = invalidateState()
    }

    private fun track(i: Int) = Track(
        ref = TrackRef.yandex("t$i"),
        title = "Song $i",
        artists = listOf(ArtistBrief(null, "Artist $i")),
        album = null,
        durationMs = 180_000L,
        cover = object : Cover {
            override val key = "c$i"
            override fun url(sizePx: Int) = "https://covers.example/c$i/$sizePx"
        },
        explicit = false
    )

    private fun queueOf(size: Int, cursor: Int): ActiveQueue {
        val tracks = (0 until size).map(::track)
        return ActiveQueue(
            queue = PlayQueue(id = 1L, name = "Main", kind = QueueKind.STATIC),
            entries = tracks.mapIndexed { i, t -> QueueEntry(1000L + i, 1L, i, i, t.ref, t.title, "Artist $i") },
            tracks = tracks,
            cursorIndex = cursor
        )
    }

    private var shown: SessionQueue? = SessionQueue.of(queueOf(5, 2), onAir = false)
    private val picks = mutableListOf<Long>()
    private val seeks = mutableListOf<Long>()
    private val engine = Engine(track(2).ref.id)

    private val player = object : SeekAwarePlayer(engine, { false }, { false }, { shown }, { picks += it }) {
        override fun seekTo(positionMs: Long) {
            seeks += positionMs
        }
    }

    private var session: MediaSession? = null

    @After
    fun release() {
        session?.release()
    }

    private val Timeline.titles get() = (0 until windowCount).map { getWindow(it, Timeline.Window()).mediaItem.mediaMetadata.title.toString() }

    @Test
    fun theSessionTimelineIsTheQueueWithTheCursorCurrent() {
        val timeline = player.currentTimeline
        assertEquals(5, timeline.windowCount)
        assertEquals(2, player.currentMediaItemIndex)
        assertEquals(2, player.currentPeriodIndex)
        assertEquals(5, player.mediaItemCount)
        assertEquals(listOf("Song 0", "Song 1", "engine t2", "Song 3", "Song 4"), timeline.titles)
        assertTrue(player.hasNextMediaItem())
        assertTrue(player.hasPreviousMediaItem())
        // The car can load the cover: a content URI, never the remote URL nor a path
        val art = timeline.getWindow(0, Timeline.Window()).mediaItem.mediaMetadata.artworkUri
        assertEquals("content", art?.scheme)
        assertEquals("queue/1000", timeline.getWindow(0, Timeline.Window()).mediaItem.mediaId)
        assertTrue(player.availableCommands.contains(Player.COMMAND_SEEK_TO_MEDIA_ITEM))
        assertTrue(player.availableCommands.contains(Player.COMMAND_GET_TIMELINE))
    }

    @Test
    fun aTapOnAnotherRowIsAPickAndOnTheCurrentOneASeek() {
        player.seekTo(4, 0L)
        assertEquals(listOf(1004L), picks)
        player.seekToDefaultPosition(0)
        assertEquals(listOf(1004L, 1000L), picks)
        player.seekTo(2, 30_000L)
        assertEquals(listOf(30_000L), seeks)
        player.seekTo(99, 0L)
        assertEquals("out of range: nothing", 2, picks.size)
    }

    @Test
    fun noQueueMeansTheEngineTimelineAsBefore() {
        shown = null
        assertEquals(1, player.currentTimeline.windowCount)
        assertEquals(0, player.currentMediaItemIndex)
        player.seekTo(3, 10_000L)
        assertEquals(listOf(10_000L), seeks)
        assertTrue(picks.isEmpty())
        assertNull("a station on air: no queue", SessionQueue.of(queueOf(5, 2), onAir = true))
        assertNull("no cursor: no queue", SessionQueue.of(queueOf(5, -1), onAir = false))
    }

    @Test
    fun aLongQueueIsAWindowAroundTheCursor() {
        val long = SessionQueue.of(queueOf(300, 100), onAir = false)!!
        assertEquals(SessionQueue.BEFORE + 1 + SessionQueue.AFTER, long.entries.size)
        assertEquals(SessionQueue.BEFORE, long.currentIndex)
        assertEquals(1080L, long.entries.first().entryId)
        assertEquals(1150L, long.entries.last().entryId)
        val nearEnd = SessionQueue.of(queueOf(300, 290), onAir = false)!!
        assertEquals(SessionQueue.BEFORE + 10, nearEnd.entries.size)
        assertEquals(SessionQueue.BEFORE, nearEnd.currentIndex)
    }

    @Test
    fun theSessionHearsQueueEditsNextAndTheEngineThroughTheQueue() {
        val timelines = mutableListOf<List<String>>()
        var playing = 0
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                timelines += timeline.titles
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playing++
            }
        }
        player.addListener(listener)

        // Next: the cursor moves to 3, the engine still sounds t2 (the pick loads) — then the engine loads t3
        shown = SessionQueue.of(queueOf(5, 3), onAir = false)
        player.queueChanged()
        assertEquals(3, player.currentMediaItemIndex)
        assertEquals(listOf("Song 0", "Song 1", "Song 2", "Song 3", "Song 4"), timelines.last())
        engine.trackId = track(3).ref.id
        engine.changed()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the engine's own timeline change arrives as the queue's", listOf("Song 0", "Song 1", "Song 2", "engine t3", "Song 4"), timelines.last())

        // A queue edit: a track added at the end
        shown = SessionQueue.of(queueOf(6, 3), onAir = false)
        player.queueChanged()
        assertEquals(6, timelines.last().size)

        // Other events still pass through the wrapper untouched
        engine.setPlayWhenReady(true)
        shadowOf(Looper.getMainLooper()).idle()
        player.removeListener(listener)
        engine.setPlayWhenReady(false)
        shadowOf(Looper.getMainLooper()).idle()
        val heard = timelines.size
        player.queueChanged()
        assertEquals("removed: hears nothing", heard, timelines.size)
        assertEquals("delegated by the wrapper", 1, playing)
    }

    /**
     * A Media3 controller of the session (Android Auto's newer path) gets the queue's rows and the cursor, and its
     * `seekTo(index, …)` becomes a pick. The platform (legacy) queue is checked on a device, in
     * `AndroidAutoBrowseInstrumentedTest` — Robolectric's platform session does not carry a queue.
     */
    @Test
    fun aSessionControllerSeesTheQueueAndATapPicks() {
        val context = RuntimeEnvironment.getApplication()
        val built = MediaSession.Builder(context, player).setId("queue-test").build()
        session = built
        val future = androidx.media3.session.MediaController.Builder(context, built.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idle()
        val controller = future.get()
        try {
            assertEquals(5, controller.mediaItemCount)
            assertEquals(2, controller.currentMediaItemIndex)
            assertEquals("Song 4", controller.getMediaItemAt(4).mediaMetadata.title.toString())
            assertTrue(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM))

            controller.seekTo(4, 0L)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(1004L), picks)

            // A queue edit reaches the controller
            shown = SessionQueue.of(queueOf(7, 2), onAir = false)
            player.queueChanged()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(7, controller.mediaItemCount)
            assertFalse(controller.currentTimeline.isEmpty)
            assertNotNull(controller.currentMediaItem)
        } finally {
            controller.release()
        }
    }
}
