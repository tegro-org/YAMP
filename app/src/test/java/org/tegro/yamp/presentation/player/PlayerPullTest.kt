package org.tegro.yamp.presentation.player

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.tegro.yamp.data.dto.ArtistDto
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.provider.yandex.toTrack
import org.tegro.yamp.ui.theme.YandexMusicTheme

/**
 * The pull-down that closes the full player (user, head unit: it once stayed stuck 40 % down): every end of the
 * gesture — a slow release, a quick flick, a cancel, a track change in the middle, the player shown again while it
 * was closing — leaves it at an anchor: back at the top, or closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class PlayerPullTest {

    // The v2 rule queues coroutines like the device's main thread: the old per-step `launch { snapTo }` ran after
    // the release and cancelled the spring back, which the immediate (v1) dispatcher never showed
    @get:Rule
    val compose = createComposeRule()

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).coroutineContext(Dispatchers.Unconfined).build())
    }

    private fun track(id: String, title: String) =
        TrackDetailsDto(id = JsonPrimitive(id), title = title, artists = listOf(ArtistDto(name = "Pink Floyd")), durationMs = 312_000)
            .toTrack()

    private val tracks = listOf(track("1", "Wish You Were Here"), track("2", "Shine On"), track("3", "Dogs"))

    private var current by mutableStateOf(tracks[0])
    private var shown by mutableStateOf(true)
    private var closes = 0

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            YandexMusicTheme {
                FullScreenPlayer(
                    track = current, isPlaying = true, isLiked = true, progress = { 82_000L }, queue = tracks,
                    onPlayPauseClick = {}, onToggleLike = {}, onNextClick = {}, onPrevClick = {}, onSeekTo = {},
                    onTrackSelect = {}, onRemoveFromQueue = {}, onCollapse = { closes++ }, onCloseAndClear = {},
                    onDislikeClick = {}, shown = shown
                )
            }
        }
        settle()
    }

    private fun settle() = repeat(20) {
        compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    /** Where the player's top is: the root's height share the current title sits below its resting place. */
    private fun titleTop(title: String = current.title): Float =
        compose.onAllNodesWithText(title, useUnmergedTree = true)[0].getUnclippedBoundsInRoot().top.value

    private fun height(): Float = compose.onRoot().getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }

    /** Start well above the queue zone (the bottom edge and the next track), on the cover's side. */
    private fun startAt(width: Float, h: Float) = Offset(width * 0.1f, h * 0.25f)

    private fun assertAtTop(rest: Float, what: String) {
        assertEquals("$what: the player is back at the top", rest, titleTop(), 0.5f)
        assertEquals("$what: nothing closed it", 0, closes)
    }

    @Test
    fun aPartialDragReleasedSlowlyGoesBack() {
        show()
        val rest = titleTop()
        compose.onRoot().performTouchInput {
            down(startAt(width.toFloat(), height.toFloat()))
            repeat(10) { moveBy(Offset(0f, height * 0.015f), delayMillis = 32) }
            up()
        }
        settle()
        assertAtTop(rest, "slow release")
    }

    @Test
    fun aQuickShortFlickBackUpGoesBack() {
        show()
        val rest = titleTop()
        compose.onRoot().performTouchInput {
            down(startAt(width.toFloat(), height.toFloat()))
            // Down 40 %, then quickly back up a little: an upward fling over a long pull keeps it open
            repeat(8) { moveBy(Offset(0f, height * 0.05f), delayMillis = 40) }
            repeat(6) { moveBy(Offset(0f, -height * 0.02f), delayMillis = 8) }
            up()
        }
        settle()
        assertAtTop(rest, "fling up")
    }

    @Test
    fun aLongPullCloses() {
        show()
        compose.onRoot().performTouchInput {
            down(startAt(width.toFloat(), height.toFloat()))
            repeat(20) { moveBy(Offset(0f, height * 0.02f), delayMillis = 40) }
            up()
        }
        settle()
        assertEquals(1, closes)
    }

    @Test
    fun aCancelledDragGoesBack() {
        show()
        val rest = titleTop()
        compose.onRoot().performTouchInput {
            down(startAt(width.toFloat(), height.toFloat()))
            repeat(5) { moveBy(Offset(0f, height * 0.02f), delayMillis = 32) }
            cancel()
        }
        settle()
        assertAtTop(rest, "cancel")
    }

    @Test
    fun aTrackChangeInTheMiddleOfADragStillSettles() {
        show()
        val rest = titleTop()
        compose.onRoot().performTouchInput {
            down(startAt(width.toFloat(), height.toFloat()))
            repeat(5) { moveBy(Offset(0f, height * 0.02f), delayMillis = 32) }
        }
        settle()
        assertTrue("the player follows the finger", titleTop() > rest + height() * 0.05f)
        current = tracks[1]
        settle()
        compose.onRoot().performTouchInput {
            repeat(2) { moveBy(Offset(0f, height * 0.01f), delayMillis = 32) }
            up()
        }
        settle()
        assertAtTop(rest, "track change")
    }

    @Test
    fun shownAgainWhileClosingComesBackToTheTop() {
        show()
        val rest = titleTop()
        compose.onRoot().performTouchInput {
            down(startAt(width.toFloat(), height.toFloat()))
            repeat(20) { moveBy(Offset(0f, height * 0.02f), delayMillis = 40) }
            up()
        }
        settle()
        assertEquals(1, closes)
        // MainScreen hides it (the exit animation keeps it composed) and something opens it again at once
        shown = false
        settle()
        shown = true
        settle()
        assertEquals("reopened: back at the top", rest, titleTop(), 0.5f)
    }
}
