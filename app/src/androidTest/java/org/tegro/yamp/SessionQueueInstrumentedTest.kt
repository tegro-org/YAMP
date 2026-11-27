package org.tegro.yamp

import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tegro.yamp.data.player.SeekAwarePlayer
import org.tegro.yamp.data.player.SessionQueue
import org.tegro.yamp.data.queue.ActiveQueue
import org.tegro.yamp.domain.media.ArtistBrief
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.queue.PlayQueue
import org.tegro.yamp.domain.queue.QueueEntry
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.queue.TrackRef
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Android Auto on a projected head unit reads the platform session (`PlaybackStateCompat` + `setQueue`): on a real
 * device, Media3's legacy stub publishes the session player's queue (one `QueueItem` per row, ids = indices, the
 * cursor as the active item), and the platform's skip-to-queue-item comes back as a pick in YAMP's queue.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SessionQueueInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** The engine: only the sounding track loaded. */
    private class Engine(private val trackId: String) : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaybackState(Player.STATE_READY)
            .setPlaylist(
                listOf(
                    MediaItemData.Builder(trackId)
                        .setMediaItem(MediaItem.Builder().setMediaId(trackId).setMediaMetadata(MediaMetadata.Builder().setTitle("Song 2").build()).build())
                        .setDurationUs(180_000_000L)
                        .setIsSeekable(true)
                        .build()
                )
            )
            .build()

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = Futures.immediateVoidFuture()
    }

    private fun queueOf(size: Int, cursor: Int): ActiveQueue {
        val tracks = (0 until size).map { Track(TrackRef.yandex("t$it"), "Song $it", listOf(ArtistBrief(null, "Artist $it")), null, 180_000L, null, false) }
        return ActiveQueue(
            queue = PlayQueue(id = 1L, name = "Main", kind = QueueKind.STATIC),
            entries = tracks.mapIndexed { i, t -> QueueEntry(1000L + i, 1L, i, i, t.ref, t.title, "Artist $i") },
            tracks = tracks,
            cursorIndex = cursor
        )
    }

    @Volatile private var shown: SessionQueue? = SessionQueue.of(queueOf(5, 2), onAir = false)
    private val picks = CopyOnWriteArrayList<Long>()
    private lateinit var player: SeekAwarePlayer
    private lateinit var session: MediaSession

    @After
    fun release() {
        instrumentation.runOnMainSync { session.release() }
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > 10_000) throw AssertionError("Timeout: $message")
            Thread.sleep(50)
        }
    }

    private fun dumpsys(): String =
        instrumentation.uiAutomation.executeShellCommand("dumpsys media_session").let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }
        }

    @Test
    fun thePlatformSessionPublishesTheQueueAndATapPicks() {
        instrumentation.runOnMainSync {
            player = SeekAwarePlayer(Engine("t2"), { false }, { false }, { shown }, { picks += it })
            session = MediaSession.Builder(context, player).setId("session-queue-test").build()
        }
        val platform = MediaController(context, session.platformToken)
        waitUntil("the queue is published") { platform.queue?.size == 5 }
        assertEquals(listOf("Song 0", "Song 1", "Song 2", "Song 3", "Song 4"), platform.queue!!.map { it.description.title.toString() })
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), platform.queue!!.map { it.queueId })
        waitUntil("the cursor is the active item") { platform.playbackState?.activeQueueItemId == 2L }
        assertTrue((platform.playbackState!!.actions and PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM) != 0L)

        // What dumpsys media_session shows for this session
        val ours = dumpsys().substringAfter("session-queue-test").substringBefore("Audio playback")
        val sizeLine = ours.lineSequence().firstOrNull { it.contains("queueTitle=") }.orEmpty().trim()
        android.util.Log.i("SessionQueueTest", "dumpsys: $sizeLine")
        assertTrue(sizeLine, sizeLine.endsWith("size=5"))

        // The car's tap on a row
        platform.transportControls.skipToQueueItem(4L)
        waitUntil("the tap is a pick") { picks == listOf(1004L) }

        // A queue edit (next track, a track added): the platform queue follows
        shown = SessionQueue.of(queueOf(40, 3), onAir = false)
        instrumentation.runOnMainSync { player.queueChanged() }
        waitUntil("the edited queue is published") { platform.queue?.size == 40 }
    }
}
