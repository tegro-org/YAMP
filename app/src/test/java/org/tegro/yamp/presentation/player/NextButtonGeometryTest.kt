package org.tegro.yamp.presentation.player

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
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
import org.tegro.yamp.presentation.CarPlayerForTest
import org.tegro.yamp.presentation.main.components.MiniPlayer
import org.tegro.yamp.ui.theme.YandexMusicTheme

/**
 * The Next button never moves (user, car head unit): switching ready → loading → failed → ready leaves every
 * tap target of the player — Next itself, Prev, Play and the rest — at exactly the same bounds, in every layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class NextButtonGeometryTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).coroutineContext(Dispatchers.Unconfined).build())
    }

    private fun track(id: String, title: String) =
        TrackDetailsDto(id = JsonPrimitive(id), title = title, artists = listOf(ArtistDto(name = "Pink Floyd")), durationMs = 312_000)
            .toTrack()

    private val tracks = listOf(track("1", "Wish You Were Here"), track("2", "Shine On"), track("3", "Dogs"), track("4", "Time"))

    private var look by mutableStateOf(NextLook.READY)

    private fun settle() = repeat(10) {
        compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun targets(): List<Rect> =
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }

    private fun assertStill(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            YandexMusicTheme {
                CompositionLocalProvider(LocalPlaybackAux provides PlaybackAux(next = { look }, buffered = { 0L })) { content() }
            }
        }
        settle()
        val ready = targets()
        for (state in listOf(NextLook.LOADING, NextLook.FAILED, NextLook.READY)) {
            look = state
            settle()
            assertEquals("tap targets moved in $state", ready, targets())
        }
    }

    @Composable
    private fun Phone() = FullScreenPlayer(
        track = tracks[0], isPlaying = true, isLiked = true, progress = { 82_000L }, queue = tracks,
        onPlayPauseClick = {}, onToggleLike = {}, onNextClick = {}, onPrevClick = {}, onSeekTo = {},
        onTrackSelect = {}, onRemoveFromQueue = {}, onCollapse = {}, onCloseAndClear = {}, onDislikeClick = {}
    )

    @Test
    fun portrait() = assertStill { Phone() }

    @Test
    @Config(qualifiers = "w891dp-h387dp-land-420dpi")
    fun wideLandscape() = assertStill { Phone() }

    /** A head unit: 1760×720 px at the auto scale, ≈ 1173×480 dp. */
    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun carLeftHand() = assertStill { CarPlayerForTest(track = tracks[0], queue = tracks, steeringWheelOnLeft = true) }

    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun carRightHand() = assertStill { CarPlayerForTest(track = tracks[0], queue = tracks, steeringWheelOnLeft = false) }

    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun carNextOne() = assertStill { CarPlayerForTest(track = tracks[0], queue = tracks, nextThree = false) }

    /** "Large controls" on a phone on its side. */
    @Test
    @Config(qualifiers = "w891dp-h387dp-land-420dpi")
    fun largeControls() = assertStill { CarPlayerForTest(track = tracks[0], queue = tracks, nextThree = false, showClock = false) }

    @Test
    fun miniPlayer() = assertStill {
        MiniPlayer(track = tracks[0], isPlaying = true, progress = { 60_000L }, onPlayPauseClick = {}, onNextClick = {}, onClick = {}, onDismiss = {})
    }

    private companion object {
        const val HEAD_UNIT = "w1173dp-h480dp-land-hdpi"
    }
}
