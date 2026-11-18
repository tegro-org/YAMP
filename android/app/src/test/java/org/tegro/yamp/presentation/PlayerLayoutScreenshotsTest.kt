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

    /** The whole screen, dialogs included (the tune panel is a dialog, as on the main screen). */
    private fun snapScreen(name: String) = captureScreenRoboImage("src/test/screenshots/$name.png", options)

    private fun track(id: String, title: String, artist: String) =
        TrackDetailsDto(id = JsonPrimitive(id), title = title, artists = listOf(ArtistDto(name = artist)), durationMs = 312_000)

    private val tracks = listOf(
        track("1", "Wish You Were Here", "Pink Floyd"),
        track("2", "Shine On You Crazy Diamond", "Pink Floyd"),
        track("3", "Дороги", "Мельница"),
        track("4", "Comfortably Numb", "Pink Floyd")
    ).map { it.toTrack() }

    @Composable
    private fun Phone(nextThree: Boolean = false, likeGestures: Boolean = false, moreOpen: Boolean = false, tuneOpen: Boolean = false) = FullScreenPlayer(
        track = tracks[0], isPlaying = true, isLiked = true, progress = { 106_000L }, queue = tracks,
        onPlayPauseClick = {}, onToggleLike = {}, onNextClick = {}, onPrevClick = {}, onSeekTo = {},
        onTrackSelect = {}, onRemoveFromQueue = {}, onCollapse = {}, onCloseAndClear = {}, onDislikeClick = {},
        sourceLabel = "Моя волна", nextThree = nextThree, likeGestures = likeGestures,
        caption = "Очередь · 12 из 48", queueName = "С семьёй", initialMoreOpen = moreOpen, initialTuneOpen = tuneOpen
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

    /** The portrait ⋮ menu on My Wave (canvas `pl-player-more`): tune, search, sleep timer, add to playlist, quality. */
    @Test
    fun phoneMoreMenu() {
        show { Phone(moreOpen = true) }
        captureScreenRoboImage("src/test/screenshots/player_phone_more_menu.png", options)
    }

    /** A taller car screen (4:3): the cover block at the far padding, the clock inside its corner (canvas `plc-tall-1280`). */
    @Test
    @Config(qualifiers = "w1024dp-h768dp-land-mdpi")
    fun carOnATallerScreen() {
        show { CarPlayerForTest(track = tracks[0], queue = tracks, caption = "Очередь · 12 из 48", queueName = "С семьёй") }
        snap("player_car_tall_4x3")
    }

    /** A very wide car screen: the controls at their share, the block centred beside them (canvas `plc-wide-2400`). */
    @Test
    @Config(qualifiers = "w2400dp-h720dp-land-mdpi")
    fun carOnAVeryWideScreen() {
        show { CarPlayerForTest(track = tracks[0], queue = tracks, caption = "Очередь · 12 из 48", queueName = "С семьёй") }
        snap("player_car_wide_2400")
    }

    /** Galaxy Z Fold 5, the cover screen (904×2316 px, ~360 dp wide): the portrait player fits, three cards with titles. */
    @Test
    @Config(qualifiers = "w360dp-h922dp-port-xhdpi")
    fun foldCoverScreen() {
        show { Phone(nextThree = true) }
        snap("player_fold_cover_next3")
    }

    /** Galaxy Z Fold 5, the inner screen standing (~690×830 dp): the content keeps its width, centred. */
    @Test
    @Config(qualifiers = "w690dp-h830dp-port-xhdpi")
    fun foldInnerScreenPortrait() {
        show { Phone(nextThree = true) }
        snap("player_fold_inner_port")
    }

    /** Galaxy Z Fold 5, the inner screen on its side (~830×690 dp, near-square): the wide rule's taller branch. */
    @Test
    @Config(qualifiers = "w830dp-h690dp-land-xhdpi")
    fun foldInnerScreenLandscape() {
        show { Phone(nextThree = true) }
        snap("player_fold_inner_land")
    }

    /** Z Fold 5 cover screen on its side (2316×904 px, ~926×362 dp): the single «Далее» card never clips its title. */
    @Test
    @Config(qualifiers = "w926dp-h362dp-land-400dpi")
    fun foldCoverLandscapeNextOne() {
        show { Phone() }
        snap("player_fold_cover_land_next1")
    }

    @Test
    @Config(qualifiers = "w926dp-h362dp-land-400dpi")
    fun foldCoverLandscapeNextThree() {
        show { Phone(nextThree = true) }
        snap("player_fold_cover_land_next3")
    }

    /** Z Fold 5 inner screen on its side (2176×1812 px, ~829×690 dp): the card's label and title centred together. */
    @Test
    @Config(qualifiers = "w829dp-h690dp-land-420dpi")
    fun foldInnerLandscapeNextOne() {
        show { Phone() }
        snap("player_fold_inner_land_next1")
    }

    @Test
    @Config(qualifiers = "w829dp-h690dp-land-420dpi")
    fun foldInnerLandscapeNextThree() {
        show { Phone(nextThree = true) }
        snap("player_fold_inner_land_next3")
    }

    /** Z Fold 5 inner on its side: «Настроить волну» is the main screen's panel, on the controls' side — the cover block stays in view. */
    @Test
    @Config(qualifiers = "ru-rRU-w829dp-h690dp-land-420dpi")
    fun foldInnerLandscapeTuneOpen() {
        show { Phone(tuneOpen = true) }
        snapScreen("player_fold_inner_land_tune")
    }

    /**
     * "Large controls" on a phone in landscape (2340×1080 px, ~891×387 dp after the bars): the ⋮ · queue row keeps a
     * 48 dp touch target instead of shrinking with the car scale (user, 2026-11-18).
     */
    @Test
    @Config(qualifiers = "w891dp-h387dp-land-420dpi")
    fun largeControlsOnAPhoneInLandscape() {
        show { CarPlayerForTest(track = tracks[0], queue = tracks, nextThree = false, showClock = false, queueName = "YouTube") }
        snap("player_large_phone_land")
    }

    /**
     * "Large controls" on the Fold 5 inner screen on its side: the controls block centred vertically, Prev · Play · Next
     * scaled together so Next is never squeezed (user, 2026-11-18).
     */
    @Test
    @Config(qualifiers = "w829dp-h690dp-land-420dpi")
    fun largeControlsOnTheFoldInnerNextOne() {
        show { CarPlayerForTest(track = tracks[0], queue = tracks, nextThree = false, queueName = "Clayderman") }
        snap("player_large_fold_inner_land_next1")
    }

    @Test
    @Config(qualifiers = "w829dp-h690dp-land-420dpi")
    fun largeControlsOnTheFoldInnerNextThree() {
        show { CarPlayerForTest(track = tracks[0], queue = tracks, queueName = "Clayderman") }
        snap("player_large_fold_inner_land_next3")
    }

    /** A 16:10 car screen (1280×800). */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun carOn1280x800() {
        show { CarPlayerForTest(track = tracks[0], queue = tracks, caption = "Очередь · 12 из 48", queueName = "С семьёй") }
        snap("player_car_1280x800")
    }
}
