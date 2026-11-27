package org.tegro.yamp.data.player

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Canvas 5e: the media session's seek command (the notification's and the car's seek bar, legacy ACTION_SEEK_TO)
 * follows the engine — gone while a converted stream waits for its disk copy, back the moment the copy is complete
 * (a seek then reopens it), and the session hears of each change mid-track.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SeekAwarePlayerTest {

    /** A player that seeks by itself or not, as the test says. */
    private class Inner(private val seekable: Boolean, private val catalogMs: Long? = CATALOG_MS) : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState(): State = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM).apply {
                    if (seekable) addAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
                }.build()
            )
            // A transcode: no duration of its own, the catalog's in its metadata
            .setPlaylist(
                listOf(
                    MediaItemData.Builder("t1")
                        .setMediaItem(
                            androidx.media3.common.MediaItem.Builder().setMediaId("t1")
                                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setDurationMs(catalogMs).build()).build()
                        )
                        .setIsSeekable(seekable)
                        // Media3 calls a progressive stream with no length live
                        .setLiveConfiguration(androidx.media3.common.MediaItem.LiveConfiguration.UNSET)
                        .build()
                )
            )
            .build()

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = Futures.immediateVoidFuture()
    }

    private var locked = false
    private var reopen = false
    private fun player(seekable: Boolean = false) = SeekAwarePlayer(Inner(seekable), { locked }, { reopen })
    private val player = player()

    private val seek get() = player.availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    private val SeekAwarePlayer.seeks get() = availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)

    @Test
    fun noSeekWhileTheCopyIsWrittenNorBeforeThePlayerCanSeek() {
        val heard = mutableListOf<Boolean>()
        player.addListener(object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                heard += availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            }
        })

        locked = true
        player.commandsChanged()
        assertFalse(seek)
        assertTrue("the transport stays", player.availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT))

        // The copy is complete but not reopened yet: the player still can't seek, so neither can the session
        locked = false
        player.commandsChanged()
        assertFalse(seek)
        assertEquals("the session heard both changes", listOf(false, false), heard)
    }

    /** Reopened from its copy: the player seeks by itself, and so does the session. */
    @Test
    fun afterTheReopenTheSessionSeeks() {
        assertTrue(player(seekable = true).seeks)
    }

    /** D91: complete but not seekable by the player — the session seeks (through the controller, a reopen). */
    @Test
    fun aCompleteDownloadAddsTheSeekAndTheCatalogDuration() {
        val heard = mutableListOf<Boolean>()
        player.addListener(object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                heard += availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            }
        })
        assertEquals("the catalog's length while the player has none", CATALOG_MS, player.duration)
        assertFalse(seek)

        reopen = true
        player.commandsChanged()
        assertTrue(seek)
        assertTrue(player.isCurrentMediaItemSeekable)
        assertFalse("seek back / forward would go straight to the player", player.availableCommands.contains(Player.COMMAND_SEEK_BACK))
        assertEquals(listOf(true), heard)

        assertFalse("a track with a catalog length is not live: the platform session keeps its position and seek", player.isCurrentMediaItemLive)
        assertTrue("radio (no catalog length) stays live", SeekAwarePlayer(Inner(false, catalogMs = null), { false }, { true }).isCurrentMediaItemLive)

        // Locked wins (a new track still downloading)
        locked = true
        assertFalse(seek)
    }

    @Test
    fun aLockHidesEvenAPlayerThatSeeksAndTheNormalCaseIsUntouched() {
        val seeking = player(seekable = true)
        assertTrue(seeking.seeks)

        locked = true
        assertFalse(seeking.seeks)
        assertFalse(seeking.availableCommands.contains(Player.COMMAND_SEEK_FORWARD))
    }

    private companion object {
        const val CATALOG_MS = 180_000L
    }
}
