package org.tegro.yamp.presentation.player

import android.app.Application
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
 * Swiping the cover sideways (user, head unit): in every layout the cover follows the finger, springs back, and
 * one swipe past the threshold skips exactly one track — right for the previous, left for the next.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class CoverSwipeTest {

    @get:Rule
    val compose = createComposeRule()

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).coroutineContext(Dispatchers.Unconfined).build())
    }

    private val tracks = listOf("Wish You Were Here", "Shine On", "Dogs").mapIndexed { i, title ->
        TrackDetailsDto(id = JsonPrimitive("${i + 1}"), title = title, artists = listOf(ArtistDto(name = "Pink Floyd")), durationMs = 312_000)
            .toTrack()
    }

    private var prevs = 0
    private var nexts = 0

    private fun show(car: Boolean = false, likeGestures: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            YandexMusicTheme {
                FullScreenPlayer(
                    track = tracks[0], isPlaying = false, isLiked = false, progress = { 82_000L }, queue = tracks,
                    onPlayPauseClick = {}, onToggleLike = {}, onNextClick = { nexts++ }, onPrevClick = { prevs++ },
                    onSeekTo = {}, onTrackSelect = {}, onRemoveFromQueue = {}, onCollapse = {}, onCloseAndClear = {},
                    onDislikeClick = {}, carLayout = car, likeGestures = likeGestures
                )
            }
        }
        settle()
    }

    private fun settle() = repeat(20) {
        compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    /** The portrait cover says "Обложка"; the wide one carries the title. */
    private fun cover(): SemanticsNodeInteraction {
        val portrait = compose.onAllNodesWithContentDescription("Обложка", useUnmergedTree = true)
        return if (portrait.fetchSemanticsNodes().isNotEmpty()) portrait[0]
        else compose.onAllNodesWithContentDescription(tracks[0].title, useUnmergedTree = true)[0]
    }

    private fun left() = cover().getUnclippedBoundsInRoot().left.value

    private fun assertFollowsAndSkips() {
        val rest = left()
        // Held part-way: the cover is where the finger took it
        cover().performTouchInput {
            down(center)
            repeat(5) { moveBy(Offset(24f, 0f), delayMillis = 16) }
        }
        settle()
        assertTrue("the cover follows the finger: $rest → ${left()}", left() > rest + 20f)
        cover().performTouchInput { up() }
        settle()
        assertEquals("a short drag springs back", rest, left(), 0.5f)
        assertEquals("a short drag skips nothing", 0 to 0, prevs to nexts)

        cover().performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(-30f, 0f), delayMillis = 16) }
            up()
        }
        settle()
        assertEquals("one swipe left: exactly one next", 0 to 1, prevs to nexts)
        assertEquals(rest, left(), 0.5f)
        cover().performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(30f, 0f), delayMillis = 16) }
            up()
        }
        settle()
        assertEquals("one swipe right: exactly one previous", 1 to 1, prevs to nexts)
    }

    @Test
    fun portrait() {
        show()
        assertFollowsAndSkips()
    }

    @Test
    @Config(qualifiers = "w891dp-h387dp-land-420dpi")
    fun wideLandscape() {
        show()
        assertFollowsAndSkips()
    }

    /** "Large controls" on a phone on its side, with likes by gestures (double tap / hold on the cover). */
    @Test
    @Config(qualifiers = "w891dp-h387dp-land-420dpi")
    fun largeControlsWithLikeGestures() {
        show(car = true, likeGestures = true)
        assertFollowsAndSkips()
    }

    /** A head unit: the car's swipe anywhere on the screen must not add a second skip. */
    @Test
    @Config(qualifiers = "w1173dp-h480dp-land-hdpi")
    fun headUnit() {
        show(car = true)
        assertFollowsAndSkips()
    }
}
