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
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
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
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.queue.RepeatMode
import org.tegro.yamp.presentation.car.CarScreen
import org.tegro.yamp.presentation.car.LocalCarClock
import org.tegro.yamp.presentation.hifi.ArtworkTint
import org.tegro.yamp.presentation.hifi.HiFiBackdrop
import org.tegro.yamp.presentation.hifi.LocalArtworkTint
import org.tegro.yamp.presentation.hifi.PanelSide
import org.tegro.yamp.presentation.main.HomeScreen
import org.tegro.yamp.presentation.main.waveShelfOf
import org.tegro.yamp.presentation.player.FullScreenPlayer
import org.tegro.yamp.presentation.player.QueueModes
import org.tegro.yamp.presentation.queues.DeleteQueueDialog
import org.tegro.yamp.presentation.queues.QueueMenuSheet
import org.tegro.yamp.presentation.queues.QueueNameDialog
import org.tegro.yamp.presentation.queues.QueueRowState
import org.tegro.yamp.presentation.queues.QueueSwitcherRequest
import org.tegro.yamp.presentation.queues.QueueSwitcherSheet
import org.tegro.yamp.presentation.queues.QueueSwitcherUiState
import org.tegro.yamp.ui.theme.YandexMusicTheme
import java.util.TimeZone

/**
 * Phase 1 queues (docs/phase-1-queues.md §5, Design/yamp/queues.jsx): the switcher sheet and panel, the queue
 * menu and dialogs, the "Add to queue…" picker, the car panel, and the entry points. Rendered from fixed
 * states, no ViewModel. Same helpers and options as [ScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class QueueScreenshotsTest {

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

    @OptIn(ExperimentalRoborazziApi::class)
    private fun snapScreen(name: String) = captureScreenRoboImage("src/test/screenshots/$name.png", options)

    private fun track(id: String, title: String, artist: String) =
        TrackDetailsDto(id = JsonPrimitive(id), title = title, artists = listOf(ArtistDto(name = artist)), durationMs = 215_000)

    private val tracks = listOf(
        track("1", "Wish You Were Here", "Pink Floyd"),
        track("2", "Shine On You Crazy Diamond", "Pink Floyd"),
        track("3", "Дороги", "Мельница"),
        track("4", "Comfortably Numb", "Pink Floyd")
    )

    /** The canvas `useQueues()`: three of the user's own, My Wave, a radio. */
    private val queues = listOf(
        QueueRowState(1, "With family", QueueKind.STATIC, "Wish You Were Here", "Pink Floyd", null, 0.42f, 12, 48, isActive = true),
        QueueRowState(2, "Solo drive", QueueKind.STATIC, "Du hast", "Rammstein", null, 0.71f, 3, 112, isActive = false),
        QueueRowState(3, "Audiobook", QueueKind.STATIC, "Chapter 14", "Roadside Picnic", null, 0.18f, 14, 31, isActive = false),
        QueueRowState(4, "My Wave", QueueKind.WAVE, "First song", "The Band", null, 0.3f, 5, 40, isActive = false),
        QueueRowState(5, "Radio: Time", QueueKind.RADIO, "Echoes", "Pink Floyd", null, 0.55f, 7, 25, isActive = false)
    )

    private fun state(mode: QueueSwitcherRequest = QueueSwitcherRequest.Switch()) = QueueSwitcherUiState(queues, activeId = 1, mode = mode)

    @Composable
    private fun Switcher(mode: QueueSwitcherRequest = QueueSwitcherRequest.Switch(), car: Boolean = false, side: PanelSide = PanelSide.RIGHT) {
        QueueSwitcherSheet(state = state(mode), car = car, side = side, onDismiss = {}, onSelect = {}, onLongPress = {}, onNew = {})
    }

    @Test
    fun switcherSheet() {
        show { Switcher() }
        snapScreen("queue_switcher")
    }

    @Test
    fun queueMenu() {
        show { QueueMenuSheet(row = queues[1], side = PanelSide.RIGHT, onRename = {}, onDuplicate = {}, onClear = {}, onDelete = {}, onDismiss = {}) }
        snapScreen("queue_menu")
    }

    @Test
    fun newQueueDialog() {
        show { QueueNameDialog(title = "New queue", confirmLabel = "Create", initialName = "Weekend trip", hint = "Starts empty. Add music with “Add to queue…”, or make it active and play something.", onConfirm = {}, onDismiss = {}) }
        snapScreen("queue_dialog_new")
    }

    @Test
    fun deleteQueueDialog() {
        show { DeleteQueueDialog(name = "Solo drive", trackCount = 112, onConfirm = {}, onDismiss = {}) }
        snapScreen("queue_dialog_delete")
    }

    @Test
    fun addToQueuePicker() {
        show { Switcher(QueueSwitcherRequest.AddTo(listOf(tracks[3]), "Comfortably Numb · Pink Floyd")) }
        snapScreen("queue_add_to")
    }

    @Test
    @Config(qualifiers = PHONE_ON_SIDE)
    fun switcherPanelPhoneOnItsSide() {
        show { Switcher() }
        snapScreen("phone_side_queue_switcher")
    }

    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun carPanelHeadUnit() {
        show {
            Car()
            Switcher(car = true, side = PanelSide.LEFT)
        }
        snapScreen("head_queue_panel")
    }

    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun carPanelWideHeadUnit() {
        show {
            Car()
            Switcher(car = true, side = PanelSide.LEFT)
        }
        snapScreen("wide_head_queue_panel")
    }

    /** The car panel in Russian: the longer labels must still fit the big rows (DESIGN_GUIDE §7). */
    @Test
    @Config(qualifiers = "ru-rRU-$WIDE_HEAD_UNIT")
    fun carPanelWideHeadUnitRussian() {
        show {
            Car()
            Switcher(car = true, side = PanelSide.LEFT)
        }
        snapScreen("wide_head_queue_panel_ru")
    }

    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun carPanelWideHeadUnitRightHandDrive() {
        show {
            Car(steeringWheelOnLeft = false)
            Switcher(car = true, side = PanelSide.RIGHT)
        }
        snapScreen("wide_head_queue_panel_right_wheel")
    }

    /** The player's queue sheet with the "Queues" pill and the shuffle / repeat toggles lit. */
    @Test
    fun playerQueueWithModes() {
        show {
            FullScreenPlayer(
                track = tracks[0], isPlaying = true, isLiked = true, progress = { 82_000L }, queue = tracks,
                onPlayPauseClick = {}, onToggleLike = {}, onNextClick = {}, onPrevClick = {}, onSeekTo = {},
                onTrackSelect = {}, onRemoveFromQueue = {}, onCollapse = {}, onCloseAndClear = {}, onDislikeClick = {},
                sourceLabel = "With family", initialQueueOpen = true,
                queueEntryIds = listOf(10L, 11L, 12L, 13L), queueCursorIndex = 0,
                queueModes = QueueModes(shuffle = true, repeat = RepeatMode.ONE, shown = true)
            )
        }
        snap("player_queue_modes")
    }

    /** Home with the queue button's count badge. */
    @Test
    fun homeQueueButton() {
        show {
            HomeScreen(
                waveTrack = tracks[0], isPlaying = true, isLoading = false, isLiked = true, progress = { 70_000L },
                shelf = waveShelfOf(true, tracks.drop(1), emptyList()), shimmer = false,
                onPlayPause = {}, onLike = {}, onDislike = {}, onOpenPlayer = {}, onShelfClick = {}, queueCount = 5
            )
        }
        snap("wave_queue_button")
    }

    @Composable
    private fun Car(steeringWheelOnLeft: Boolean = true) = CompositionLocalProvider(LocalCarClock provides { FIXED_TIME }) {
        CarScreen(
            currentTrack = tracks[0], isPlaying = true, likedTrackIds = setOf("1"), onPlayPauseClick = {}, onNextClick = {},
            onPrevClick = {}, onToggleLike = {}, onDislikeClick = {}, onPlayMyVibe = {}, onPlayFavorites = {}, onPlayHits = {},
            onPlayDownloaded = {}, onPlayRadio = {}, onExit = {}, isLandscape = true, steeringWheelOnLeft = steeringWheelOnLeft,
            progress = { 82_000L }, durationMs = 286_000, upcoming = tracks.drop(1), source = org.tegro.yamp.data.player.PlaylistSource.LIBRARY,
            activeQueueName = "With family"
        )
    }

    private companion object {
        const val FIXED_TIME = 1_792_567_800_000L
        const val HEAD_UNIT = "w1280dp-h720dp-land-mdpi"
        const val WIDE_HEAD_UNIT = "w1920dp-h720dp-land-mdpi"
        const val PHONE_ON_SIDE = "w800dp-h360dp-land-xhdpi"
    }
}
