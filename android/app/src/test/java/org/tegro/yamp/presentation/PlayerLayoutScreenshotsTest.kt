package org.tegro.yamp.presentation

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tegro.yamp.data.dto.ArtistDto
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.provider.yandex.toTrack
import org.tegro.yamp.presentation.hifi.ArtworkTint
import org.tegro.yamp.presentation.hifi.HiFiBackdrop
import org.tegro.yamp.presentation.hifi.LocalArtworkTint
import org.tegro.yamp.presentation.player.FullScreenPlayer
import org.tegro.yamp.ui.theme.YandexMusicTheme
import java.util.TimeZone

/**
 * The new player (canvas `Design/yamp/player.jsx`, approved 2026-11-18): Phone with one or three next tracks, likes
 * by gestures, the phone on its side, and the Car layout on a 1920 head unit and on the user's 1760 one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class PlayerLayoutScreenshotsTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).coroutineContext(Dispatchers.Unconfined).build())
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
    }

    private val tint = ArtworkTint(Color(0xFF3F6FC4), Color(0xFF1B2A6B))

    private fun show(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            YandexMusicTheme {
                CompositionLocalProvider(LocalArtworkTint provides tint) {
                    Box(Modifier.fillMaxSize()) {
                        HiFiBackdrop(tint = tint)
                        content()
                    }
                }
            }
        }
        repeat(20) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private val options = RoborazziOptions(
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f)
    )

    private fun snap(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png", options)

    private fun track(id: String, title: String, artist: String) =
        TrackDetailsDto(id = JsonPrimitive(id), title = title, artists = listOf(ArtistDto(name = artist)), durationMs = 312_000)

    private val tracks = listOf(
        track("1", "Wish You Were Here", "Pink Floyd"),
        track("2", "Shine On You Crazy Diamond", "Pink Floyd"),
        track("3", "Дороги", "Мельница"),
        track("4", "Comfortably Numb", "Pink Floyd")
    ).map { it.toTrack() }

    @Composable
    private fun Phone(nextThree: Boolean = false, likeGestures: Boolean = false, moreOpen: Boolean = false) = FullScreenPlayer(
        track = tracks[0], isPlaying = true, isLiked = true, progress = { 106_000L }, queue = tracks,
        onPlayPauseClick = {}, onToggleLike = {}, onNextClick = {}, onPrevClick = {}, onSeekTo = {},
        onTrackSelect = {}, onRemoveFromQueue = {}, onCollapse = {}, onCloseAndClear = {}, onDislikeClick = {},
        sourceLabel = "Моя волна", nextThree = nextThree, likeGestures = likeGestures,
        caption = "Очередь · 12 из 48", queueName = "С семьёй", initialMoreOpen = moreOpen
    )

    @Test
    fun phoneNextThree() {
        show { Phone(nextThree = true) }
        snap("player_phone_next3")
    }

    @Test
    fun phoneLikeGestures() {
        show { Phone(likeGestures = true) }
        snap("player_phone_like_gestures")
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-xhdpi")
    fun phoneLandscapeNextOne() {
        show { Phone() }
        snap("player_land_next1")
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-xhdpi")
    fun phoneLandscapeNextThree() {
        show { Phone(nextThree = true) }
        snap("player_land_next3")
    }

    @Test
    @Config(qualifiers = "w1920dp-h720dp-land-mdpi")
    fun carLeftHandWheel() {
        show { CarPlayerForTest(track = tracks[0], queue = tracks, caption = "Очередь · 12 из 48", queueName = "С семьёй") }
        snap("player_car_left")
    }

    @Test
    @Config(qualifiers = "w1920dp-h720dp-land-mdpi")
    fun carRightHandWheel() {
        show { CarPlayerForTest(track = tracks[0], queue = tracks, steeringWheelOnLeft = false, caption = "Очередь · 12 из 48", queueName = "С семьёй") }
        snap("player_car_right")
    }

    /** The user's unit: 1760 × 720, the navigation panel on the left (canvas `plc-unit-1760`). */
    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carOnTheUsersUnit() {
        show { CarPlayerForTest(track = tracks[0], queue = tracks, caption = "Очередь · 12 из 48", queueName = "С семьёй") }
        snap("player_car_unit_1760")
    }

    @Test
    @Config(qualifiers = "w1920dp-h720dp-land-mdpi")
    fun carLikeGestures() {
        show { CarPlayerForTest(track = tracks[0], queue = tracks, likeGestures = true, caption = "Очередь · 12 из 48", queueName = "С семьёй") }
        snap("player_car_like_gestures")
    }

    @Test
    @Config(qualifiers = "w1920dp-h720dp-land-mdpi")
    fun carQueueOpen() {
        show { CarPlayerForTest(track = tracks[0], queue = tracks, initialQueueOpen = true, queueName = "С семьёй") }
        snap("player_car_queue_open")
    }

    /** The wide layouts' ⋮ menu (user, 2026-11-18): sleep timer, add to playlist, quality; no likes with buttons on. */
    @Test
    @Config(qualifiers = "w915dp-h412dp-land-xhdpi")
    fun phoneLandscapeMoreMenu() {
        show { Phone(moreOpen = true) }
        captureScreenRoboImage("src/test/screenshots/player_land_more_menu.png", options)
    }

    /** With likes by gestures the ⋮ menu adds Like / Dislike as a fallback; car-sized rows. */
    @Test
    @Config(qualifiers = "w1920dp-h720dp-land-mdpi")
    fun carMoreMenuWithLikeGestures() {
        show { CarPlayerForTest(track = tracks[0], queue = tracks, likeGestures = true, caption = "Очередь · 12 из 48", queueName = "С семьёй", initialMoreOpen = true) }
        captureScreenRoboImage("src/test/screenshots/player_car_more_menu.png", options)
    }
}
