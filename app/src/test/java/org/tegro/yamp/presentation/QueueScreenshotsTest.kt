package org.tegro.yamp.presentation

import org.tegro.yamp.data.provider.yandex.toTrack
import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import org.tegro.yamp.data.queue.ActiveQueue
import org.tegro.yamp.domain.queue.PlayQueue
import org.tegro.yamp.domain.queue.QueueEntry
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.RadioSeed
import org.tegro.yamp.domain.queue.RepeatMode
import org.tegro.yamp.domain.queue.TrackRef
import org.tegro.yamp.presentation.car.LocalCarClock
import org.tegro.yamp.presentation.hifi.ArtworkTint
import org.tegro.yamp.presentation.hifi.HiFiBackdrop
import org.tegro.yamp.presentation.hifi.LocalArtworkTint
import org.tegro.yamp.presentation.hifi.PanelSide
import org.tegro.yamp.presentation.main.HomeScreen
import org.tegro.yamp.presentation.main.components.DockBottomGap
import org.tegro.yamp.presentation.main.components.DockClearance
import org.tegro.yamp.presentation.main.components.DockScrim
import org.tegro.yamp.presentation.main.components.FloatingGlassDock
import org.tegro.yamp.presentation.main.homeModeOf
import org.tegro.yamp.presentation.main.queueShelfOf
import org.tegro.yamp.presentation.main.waveShelfOf
import org.tegro.yamp.presentation.player.FullScreenPlayer
import org.tegro.yamp.presentation.player.QueueModes
import org.tegro.yamp.presentation.queues.DeleteQueueDialog
import org.tegro.yamp.presentation.account.AccountTag
import org.tegro.yamp.presentation.account.accountTags
import org.tegro.yamp.domain.model.UserAccount
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.tegro.yamp.presentation.queues.QueueMenuSheet
import org.tegro.yamp.presentation.queues.QueueNameDialog
import org.tegro.yamp.presentation.queues.QueueRowState
import org.tegro.yamp.presentation.queues.QueueSwitcherRequest
import org.tegro.yamp.presentation.queues.QueueSwitcherSheet
import org.tegro.yamp.presentation.queues.QueueSwitcherUiState
import org.tegro.yamp.presentation.queues.RemovedQueueSheet
import org.tegro.yamp.presentation.queues.RemovedQueueView
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

    /** The fixture tracks as screens show them (phase 3 step 12–15). */
    private val shown get() = tracks.map { it.toTrack() }

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
        show { Switcher(QueueSwitcherRequest.AddTo(listOf(shown[3]), "Comfortably Numb · Pink Floyd")) }
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

    // ---- Phase 2 radios (docs/phase-2-radio.md §5)

    /** Two radios, one with Keep on, among the user's queues. */
    private val withRadios = queues.take(4) + listOf(
        QueueRowState(5, "Radio: Time", QueueKind.RADIO, "Echoes", "Pink Floyd", null, 0.55f, 7, 25, isActive = false, keepHistory = true),
        QueueRowState(6, "Radio: Мельница", QueueKind.RADIO, "Дороги", "Мельница", null, 0.2f, 3, 12, isActive = false)
    )

    private val radioRequest = QueueSwitcherRequest.StartRadio(
        RadioSeed(MediaRef(MediaRef.PROVIDER_YANDEX, MediaRef.KIND_TRACK, "4"), "Comfortably Numb"), shown[3]
    )

    @Composable
    private fun RadioPicker(car: Boolean = false, side: PanelSide = PanelSide.RIGHT, keep: Boolean = false) {
        QueueSwitcherSheet(
            state = QueueSwitcherUiState(withRadios, activeId = 1, mode = radioRequest),
            car = car, side = side, onDismiss = {}, onSelect = {}, onLongPress = null, onNew = {}, keepHistory = keep
        )
    }

    @Test
    fun switcherWithRadios() {
        show { QueueSwitcherSheet(state = QueueSwitcherUiState(withRadios, 1, QueueSwitcherRequest.Switch()), car = false, side = PanelSide.RIGHT, onDismiss = {}, onSelect = {}, onLongPress = {}, onNew = {}) }
        snapScreen("radio_switcher")
    }

    @Test
    fun radioPicker() {
        show { RadioPicker(keep = true) }
        snapScreen("radio_picker")
    }

    @Test
    @Config(qualifiers = PHONE_ON_SIDE)
    fun radioPickerPhoneOnItsSide() {
        show { RadioPicker() }
        snapScreen("phone_side_radio_picker")
    }

    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun radioPickerHeadUnit() {
        show {
            Car()
            RadioPicker(car = true, side = PanelSide.LEFT)
        }
        snapScreen("head_radio_picker")
    }

    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun radioPickerWideHeadUnit() {
        show {
            Car()
            RadioPicker(car = true, side = PanelSide.LEFT)
        }
        snapScreen("wide_head_radio_picker")
    }

    @Test
    @Config(qualifiers = "ru-rRU-$WIDE_HEAD_UNIT")
    fun radioPickerWideHeadUnitRussian() {
        show {
            Car()
            RadioPicker(car = true, side = PanelSide.LEFT, keep = true)
        }
        snapScreen("wide_head_radio_picker_ru")
    }

    /** A radio's long-press menu: Keep and Delete (D52). */
    @Test
    fun radioMenu() {
        show { QueueMenuSheet(row = withRadios[4], side = PanelSide.RIGHT, onRename = {}, onDuplicate = {}, onClear = {}, onDelete = {}, onDismiss = {}) }
        snapScreen("radio_menu")
    }

    /** The player's queue sheet with the "Queues" pill and the shuffle / repeat toggles lit. */
    @Test
    fun playerQueueWithModes() {
        show {
            FullScreenPlayer(
                track = tracks[0].toTrack(), isPlaying = true, isLiked = true, progress = { 82_000L }, queue = tracks.map { it.toTrack() },
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
                waveTrack = shown[0], isPlaying = true, isLoading = false, isLiked = true, progress = { 70_000L },
                shelf = waveShelfOf(true, shown.drop(1), emptyList()), shimmer = false,
                onPlayPause = {}, onLike = {}, onDislike = {}, onOpenPlayer = {}, onShelfClick = {}, queueName = "With family"
            )
        }
        snap("wave_queue_button")
    }

    /** The queue dropdown and "Настроить волну" at the wheel side's edge: left-hand — at the left edge. */
    @Test
    fun homeControlsLeftHand() {
        show {
            HomeScreen(
                waveTrack = shown[0], isPlaying = true, isLoading = false, isLiked = true, progress = { 70_000L },
                shelf = waveShelfOf(true, shown.drop(1), emptyList()), shimmer = false,
                onPlayPause = {}, onLike = {}, onDislike = {}, onOpenPlayer = {}, onShelfClick = {}, queueName = "With family",
                controlsOnLeft = true
            )
        }
        snap("home_controls_left")
    }

    /** Right-hand: at the right edge (the default). */
    @Test
    fun homeControlsRightHand() {
        show {
            HomeScreen(
                waveTrack = shown[0], isPlaying = true, isLoading = false, isLiked = true, progress = { 70_000L },
                shelf = waveShelfOf(true, shown.drop(1), emptyList()), shimmer = false,
                onPlayPause = {}, onLike = {}, onDislike = {}, onOpenPlayer = {}, onShelfClick = {}, queueName = "With family",
                controlsOnLeft = false
            )
        }
        snap("home_controls_right")
    }

    /** Head unit, left-hand: the queue dropdown at the screen's left edge, the title on the right (user, 2026-11-18). */
    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun headHomeControlsLeftHand() {
        show {
            HomeScreen(
                waveTrack = shown[0], isPlaying = true, isLoading = false, isLiked = true, progress = { 70_000L },
                shelf = waveShelfOf(true, shown.drop(1), emptyList()), shimmer = false,
                onPlayPause = {}, onLike = {}, onDislike = {}, onOpenPlayer = {}, onShelfClick = {}, queueName = "With family",
                controlsOnLeft = true
            )
        }
        snap("head_home_controls_left")
    }

    /** Head unit, right-hand: the queue dropdown at the screen's right edge. */
    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun headHomeControlsRightHand() {
        show {
            HomeScreen(
                waveTrack = shown[0], isPlaying = true, isLoading = false, isLiked = true, progress = { 70_000L },
                shelf = waveShelfOf(true, shown.drop(1), emptyList()), shimmer = false,
                onPlayPause = {}, onLike = {}, onDislike = {}, onOpenPlayer = {}, onShelfClick = {}, queueName = "With family",
                controlsOnLeft = false
            )
        }
        snap("head_home_controls_right")
    }

    /** A foldable cover on its side (2316×904): the dropdown and «Настроить волну» in one row at the left edge. */
    @Test
    @Config(qualifiers = "ru-rRU-w926dp-h362dp-land-400dpi")
    fun foldCoverHomeRowLeftHand() {
        show {
            HomeScreen(
                waveTrack = shown[0], isPlaying = true, isLoading = false, isLiked = true, progress = { 70_000L },
                shelf = waveShelfOf(true, shown.drop(1), emptyList()), shimmer = false,
                onPlayPause = {}, onLike = {}, onDislike = {}, onOpenPlayer = {}, onShelfClick = {}, queueName = "Моя волна",
                controlsOnLeft = true
            )
        }
        snap("home_foldable_cover_row_left")
    }

    /** The "With family" queue, playing its second track. */
    private val familyQueue = ActiveQueue(
        queue = PlayQueue(id = 1L, name = "With family", kind = QueueKind.STATIC),
        entries = tracks.mapIndexed { i, t -> QueueEntry(entryId = 10L + i, queueId = 1L, position = i, originalPosition = i, ref = TrackRef.yandex(t.idString), title = t.title.orEmpty(), artist = "") },
        tracks = tracks.map { it.toTrack() },
        cursorIndex = 1
    )

    /** The first tab as a queue's page (D49): its name, no wave pills, "Up next" from the cursor. */
    @Composable
    private fun HomeQueuePage(contentBottom: androidx.compose.ui.unit.Dp = 100.dp) {
        HomeScreen(
            waveTrack = shown[1], isPlaying = true, isLoading = false, isLiked = false, progress = { 70_000L },
            shelf = queueShelfOf(familyQueue, playing = true), shimmer = false,
            onPlayPause = {}, onLike = {}, onDislike = {}, onOpenPlayer = {}, onShelfClick = {}, queueName = "With family",
            contentBottom = contentBottom, mode = homeModeOf(familyQueue)
        )
    }

    @Test
    fun homeQueuePage() {
        show { HomeQueuePage() }
        snap("home_queue")
    }

    /** A queue's page before it plays (a station is on, or nothing): the cursor track is what the knob starts with. */
    @Test
    fun homeQueuePageIdle() {
        show {
            HomeScreen(
                waveTrack = null, isPlaying = false, isLoading = false, isLiked = false, progress = { 0L },
                shelf = queueShelfOf(familyQueue, playing = false), shimmer = false,
                onPlayPause = {}, onLike = {}, onDislike = {}, onOpenPlayer = null, onShelfClick = {}, queueName = "With family",
                mode = homeModeOf(familyQueue)
            )
        }
        snap("home_queue_idle")
    }

    /** As the app lays the page out on a side: the tab dock along the bottom. */
    @Composable
    private fun DockedHomeQueuePage() {
        Box(Modifier.fillMaxSize()) {
            HomeQueuePage(contentBottom = DockClearance)
            DockScrim(Modifier.align(Alignment.BottomCenter))
            FloatingGlassDock(
                currentRoute = "home",
                onNavigate = {},
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = DockBottomGap),
                player = {}
            )
        }
    }

    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun homeQueuePageHeadUnit() {
        show { DockedHomeQueuePage() }
        snap("head_home_queue")
    }

    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun homeQueuePageWideHeadUnit() {
        show { DockedHomeQueuePage() }
        snap("wide_head_home_queue")
    }

    /** The car header with a queue of the user's own active: the queue pill alone, no wave pills (D49). */
    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun carHeadUnitQueuePage() {
        show { Car(waveActive = false) }
        snap("head_car_queue")
    }

    // ---- Phase 2a: two accounts (docs/phase-2a-accounts.md D57, D62, D64; canvas accounts.jsx)

    /** The canvas `useAcAccounts()`: Anna and Rock, each with its My Wave, a queue and a radio. */
    private val accounts = accountTags(
        listOf(
            UserAccount(uid = "1001", login = "anna", displayName = "Anna", token = "t"),
            UserAccount(uid = "2002", login = "rock", displayName = "Rock", token = "t")
        )
    )
    private val anna = accounts[0]
    private val rock = accounts[1]

    private val accountQueues = listOf(
        QueueRowState(11, "With family", QueueKind.STATIC, "Wish You Were Here", "Pink Floyd", null, 0.42f, 12, 48, isActive = true, account = anna.key),
        QueueRowState(10, "My Wave", QueueKind.WAVE, "First song", "The Band", null, 0.3f, 5, 40, isActive = false, account = anna.key),
        QueueRowState(12, "Radio: Time", QueueKind.RADIO, "Echoes", "Pink Floyd", null, 0.55f, 7, 25, isActive = false, keepHistory = true, account = anna.key),
        QueueRowState(20, "My Wave", QueueKind.WAVE, "Кукушка", "Кино", null, 0.1f, 2, 30, isActive = false, account = rock.key),
        QueueRowState(21, "Solo drive", QueueKind.STATIC, "Du hast", "Rammstein", null, 0.71f, 3, 112, isActive = false, account = rock.key),
        QueueRowState(22, "Radio: Rammstein", QueueKind.RADIO, "Sonne", "Rammstein", null, 0.2f, 4, 15, isActive = false, account = rock.key),
        // A removed account's queue (D62)
        QueueRowState(30, "Road trip", QueueKind.STATIC, "Выхода нет", "Сплин", null, 0.3f, 20, 64, isActive = false, account = "yandex:3003")
    )

    private fun accountState(mode: QueueSwitcherRequest = QueueSwitcherRequest.Switch(), grouped: Boolean = true) =
        QueueSwitcherUiState(accountQueues, activeId = 11, mode = mode, accounts = accounts, grouped = grouped, removedNames = removedNames)

    /** The account "Road trip" belonged to, signed out (D62): its name is kept for the marker. */
    private val removedNames = mapOf("yandex:3003" to "Max")
    private val max = org.tegro.yamp.presentation.account.removedAccountTag("yandex:3003", "Max")

    @Test
    fun switcherGroupedByAccount() {
        show { QueueSwitcherSheet(accountState(), car = false, side = PanelSide.RIGHT, onDismiss = {}, onSelect = {}, onLongPress = {}, onNew = {}) }
        snapScreen("accounts_switcher_grouped")
    }

    @Test
    fun switcherFlatWithMarkers() {
        show { QueueSwitcherSheet(accountState(grouped = false), car = false, side = PanelSide.RIGHT, onDismiss = {}, onSelect = {}, onLongPress = {}, onNew = {}) }
        snapScreen("accounts_switcher_flat")
    }

    /** The removed accounts' group opened: dimmed rows at the bottom (D62). */
    @Test
    @Config(qualifiers = "w411dp-h1400dp")
    fun switcherRemovedAccountsOpen() {
        show { QueueSwitcherSheet(accountState(), car = false, side = PanelSide.RIGHT, onDismiss = {}, onSelect = {}, onLongPress = {}, onNew = {}) }
        compose.onNodeWithText("Removed accounts · 1 queue").performClick()
        repeat(5) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        snapScreen("accounts_switcher_removed")
    }

    @Test
    fun removedQueueMenu() {
        show {
            QueueMenuSheet(
                row = accountQueues[6], side = PanelSide.RIGHT, onRename = {}, onDuplicate = {}, onClear = {}, onDelete = {},
                removed = true, mark = max, removedAccountName = "Max", onDismiss = {}
            )
        }
        snapScreen("accounts_menu_removed")
    }

    /** A removed account's queue opened (canvas `ac-removed-open`): its tracks unavailable, no banner (D62). */
    @Test
    fun removedQueueOpened() {
        val view = RemovedQueueView(
            next = listOf(
                RemovedQueueView.Track("Выхода нет", "Сплин"),
                RemovedQueueView.Track("Кукушка", "Кино"),
                RemovedQueueView.Track("Штиль", "Ария"),
                RemovedQueueView.Track("Группа крови", "Кино")
            ),
            position = 20,
            total = 64
        )
        show { RemovedQueueSheet(accountQueues[6], max, view, PanelSide.RIGHT, removedAccountName = "Max", onQueues = {}, onDismiss = {}) }
        snapScreen("accounts_removed_open")
    }

    @Test
    fun newQueueDialogWithAccounts() {
        show {
            QueueNameDialog(
                title = "New queue", confirmLabel = "Create", initialName = "Weekend trip",
                hint = "Starts empty. Add music with “Add to queue…”, or make it active and play something.",
                accounts = accounts, selectedAccount = anna.key, onConfirm = {}, onDismiss = {}
            )
        }
        snapScreen("accounts_dialog_new")
    }

    @Test
    fun radioPickerWithAccounts() {
        show {
            QueueSwitcherSheet(accountState(radioRequest), car = false, side = PanelSide.RIGHT, onDismiss = {}, onSelect = {}, onLongPress = null, onNew = {}, newAccount = anna.key)
        }
        snapScreen("accounts_radio_picker")
    }

    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun carPanelGroupedWideHeadUnit() {
        show {
            Car(account = anna)
            QueueSwitcherSheet(accountState(), car = true, side = PanelSide.LEFT, onDismiss = {}, onSelect = {}, onLongPress = null, onNew = {})
        }
        snapScreen("accounts_wide_head_queue_panel")
    }

    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun carPanelFlatWideHeadUnit() {
        show {
            Car(account = anna)
            QueueSwitcherSheet(accountState(grouped = false), car = true, side = PanelSide.LEFT, onDismiss = {}, onSelect = {}, onLongPress = null, onNew = {})
        }
        snapScreen("accounts_wide_head_queue_panel_flat")
    }

    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun carRadioPickerWideHeadUnit() {
        show {
            Car(account = anna)
            QueueSwitcherSheet(accountState(radioRequest.copy(car = true)), car = true, side = PanelSide.LEFT, onDismiss = {}, onSelect = {}, onLongPress = null, onNew = {}, newAccount = anna.key)
        }
        snapScreen("accounts_wide_head_radio_picker")
    }

    /** The car with the account marker in the queue pill (always with more than one account). */
    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun carPillWithMarker() {
        show { Car(account = anna) }
        snap("accounts_wide_head_car")
    }

    /** The player's queue sheet: the playing queue's account in its header. */
    @Test
    fun playerQueueWithAccount() {
        show {
            FullScreenPlayer(
                track = tracks[0].toTrack(), isPlaying = true, isLiked = true, progress = { 82_000L }, queue = tracks.map { it.toTrack() },
                onPlayPauseClick = {}, onToggleLike = {}, onNextClick = {}, onPrevClick = {}, onSeekTo = {},
                onTrackSelect = {}, onRemoveFromQueue = {}, onCollapse = {}, onCloseAndClear = {}, onDislikeClick = {},
                sourceLabel = "With family", initialQueueOpen = true, queueAccount = anna,
                queueEntryIds = listOf(10L, 11L, 12L, 13L), queueCursorIndex = 0
            )
        }
        snap("accounts_player_queue")
    }

    @Composable
    private fun Car(steeringWheelOnLeft: Boolean = true, waveActive: Boolean = true, account: AccountTag? = null) =
        CarPlayerForTest(
            track = tracks[0].toTrack(), queue = tracks.map { it.toTrack() }, steeringWheelOnLeft = steeringWheelOnLeft,
            waveActive = waveActive, account = account
        )

    private companion object {
        const val FIXED_TIME = 1_792_567_800_000L
        const val HEAD_UNIT = "w1280dp-h720dp-land-mdpi"
        const val WIDE_HEAD_UNIT = "w1920dp-h720dp-land-mdpi"
        const val PHONE_ON_SIDE = "w800dp-h360dp-land-xhdpi"
    }
}
