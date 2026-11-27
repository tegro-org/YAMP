package org.tegro.yamp.presentation.player

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasAnyAncestor
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.presentation.CarPlayerForTest
import org.tegro.yamp.presentation.common.id
import org.tegro.yamp.presentation.hifi.HiFiBackdrop
import org.tegro.yamp.ui.theme.YandexMusicTheme

/**
 * The queue panel's three sections (canvas 5f, `Design/yamp/queueprev.jsx`): Previous · Now playing · Next. It opens
 * with Now playing at the top and the played tracks above; a previous row plays (a queue pick) or goes (✕); the list
 * only scrolls — the panel closes by its header (user, 2026-11-20). Goldens for the phone, landscape and the car.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class QueuePreviousTest {

    @get:Rule
    val compose = createComposeRule()

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).coroutineContext(Dispatchers.Unconfined).build())
    }

    private val names = listOf(
        "Speak to Me" to "Pink Floyd", "Breathe" to "Pink Floyd", "Кукушка" to "Кино", "Ветер" to "Мельница",
        "Time" to "Pink Floyd", "Группа крови" to "Кино", "Us and Them" to "Pink Floyd", "Highway Star" to "Deep Purple",
        "Ночная кобыла" to "Мельница", "Money" to "Pink Floyd", "Child in Time" to "Deep Purple", "Wish You Were Here" to "Pink Floyd",
        "Shine On You Crazy Diamond" to "Pink Floyd", "Дороги" to "Мельница", "Comfortably Numb" to "Pink Floyd",
        "Звезда по имени Солнце" to "Кино", "Smoke on the Water" to "Deep Purple", "Echoes" to "Pink Floyd"
    )

    /** 48 tracks, the canvas' names in turn (a track twice is two entries). */
    private val queue: List<Track> = List(48) { i ->
        val (title, artist) = names[i % names.size]
        TrackDetailsDto(id = JsonPrimitive("t$i"), title = title, artists = listOf(ArtistDto(name = artist)), durationMs = 215_000).toTrack()
    }
    private val entries = List(queue.size) { 100L + it }

    private var picked: Long? = null
    private var removedEntry: Long? = null

    private fun settle() = repeat(20) {
        compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun show(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            YandexMusicTheme {
                Box(Modifier.fillMaxSize()) {
                    HiFiBackdrop()
                    content()
                }
            }
        }
        settle()
    }

    @Composable
    private fun Player(cursor: Int, list: List<Track> = queue, ids: List<Long> = entries, source: String = "With family") {
        FullScreenPlayer(
            track = list[cursor], isPlaying = true, isLiked = true, progress = { 82_000L }, queue = list,
            onPlayPauseClick = {}, onToggleLike = {}, onNextClick = {}, onPrevClick = {}, onSeekTo = {},
            onTrackSelect = {}, onRemoveFromQueue = {}, onCollapse = {}, onCloseAndClear = {}, onDislikeClick = {},
            onRemoveEntry = { removedEntry = it },
            sourceLabel = source, initialQueueOpen = true,
            queueEntryIds = ids, queueCursorIndex = cursor, onEntrySelect = { picked = it }
        )
    }

    private val options = RoborazziOptions(
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f)
    )

    @OptIn(ExperimentalRoborazziApi::class)
    private fun snap(name: String) = captureScreenRoboImage("src/test/screenshots/$name.png", options)

    /** The sheet's subtitle: the panel is up. */
    private val sheetSubtitle = hasText("With family · 48", substring = true)
    private fun inSheet(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true)

    // --- pure

    @Test
    fun theSplitKeepsWhatPlayedInPlayOrder() {
        val split = queueSplitOf(queue.take(5), "t2", entries.take(5), cursorIndex = 2)
        assertEquals(listOf("t0", "t1"), split.previous.map { it.id })
        assertEquals(listOf(100L, 101L), split.previousEntryIds)
        assertEquals(listOf("t3", "t4"), split.next.map { it.id })
        assertTrue("the first track: nothing before it", queueSplitOf(queue.take(5), "t0").previous.isEmpty())
        assertTrue("not in the queue: no sections", queueSplitOf(queue.take(5), "zz").previous.isEmpty())
    }

    @Test
    fun nowPlayingFollowsThePreviousSection() {
        assertEquals(0, QueueSections.nowCaptionIndex(0))
        assertEquals("caption + 11 rows before it", 12, QueueSections.nowCaptionIndex(11))
    }

    // --- behaviour

    @Test
    fun itOpensAtNowPlayingWithTheHint() {
        show { Player(cursor = 11) }
        compose.onNodeWithText("Previous: 11", useUnmergedTree = true).assertIsDisplayed()
        assertTrue("the Previous header is above, out of view", inSheet("PREVIOUS").fetchSemanticsNodes().isEmpty())
        compose.onNode(sheetSubtitle, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theHintScrollsUpToPreviousAndAPreviousRowIsAPick() {
        show { Player(cursor = 11) }
        compose.onNodeWithText("Previous: 11", useUnmergedTree = true).performClick()
        settle()
        compose.onNodeWithText("PREVIOUS", useUnmergedTree = true).assertIsDisplayed()
        // The second track ("Breathe", entry 101) is a previous row now in view
        compose.onNodeWithText("Breathe", useUnmergedTree = true).performClick()
        settle()
        assertEquals(101L, picked)
    }

    @Test
    fun aPreviousRowHasItsRemoveButton() {
        show { Player(cursor = 3) }
        compose.onNodeWithText("Previous: 3", useUnmergedTree = true).performClick()
        settle()
        // The first ✕ in the list is the first previous row's ("Speak to Me", entry 100)
        compose.onAllNodes(hasContentDescription("Удалить из очереди"))[0].performClick()
        settle()
        assertEquals(100L, removedEntry)
        assertNull("✕ is not a pick", picked)
    }

    @Test
    fun aSwipeDownOnTheListScrollsIntoPreviousAndKeepsThePanel() {
        show { Player(cursor = 11) }
        compose.onNodeWithText("СЕЙЧАС ИГРАЕТ", useUnmergedTree = true).performTouchInput {
            down(Offset(centerX, bottom + 300f))
            repeat(10) { moveBy(Offset(0f, 60f), delayMillis = 16) }
            up()
        }
        settle()
        compose.onNode(sheetSubtitle, useUnmergedTree = true).assertIsDisplayed()
        assertTrue("previous rows came into view", inSheet("Money").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aSwipeDownOnTheHeaderClosesThePanel() {
        show { Player(cursor = 11) }
        compose.onNode(sheetSubtitle, useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, 120f), delayMillis = 16) }
            up()
        }
        settle()
        assertTrue("the panel is gone", compose.onAllNodes(sheetSubtitle, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun atTheQueueStartThereIsNoPreviousSection() {
        show { Player(cursor = 0) }
        assertTrue(inSheet("PREVIOUS").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText("previous", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    // --- goldens (canvas `qp-*`)

    @Test
    fun phoneOnOpen() {
        show { Player(cursor = 11) }
        snap("queue_prev_open")
    }

    @Test
    fun phoneScrolledToTheTop() {
        show { Player(cursor = 11) }
        compose.onNodeWithText("Previous: 11", useUnmergedTree = true).performClick()
        settle()
        snap("queue_prev_top")
    }

    @Test
    fun phoneNearTheEnd() {
        show { Player(cursor = 46) }
        snap("queue_prev_end")
    }

    @Test
    @Config(qualifiers = "ru")
    fun phoneRadioKeepsThree() {
        // A radio without Keep holds 3 behind the cursor (D52): the queue starts there
        val radio = queue.drop(11).take(9)
        show { Player(cursor = 3, list = radio, ids = entries.drop(11).take(9), source = "Радио: Time") }
        compose.onNodeWithText("Предыдущие: 3", useUnmergedTree = true).performClick()
        settle()
        snap("queue_prev_radio_ru")
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-xhdpi")
    fun landscapeOnOpen() {
        show { Player(cursor = 11) }
        snap("queue_prev_land")
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-xhdpi")
    fun landscapeNearTheEnd() {
        show { Player(cursor = 46) }
        snap("queue_prev_land_end")
    }

    @Test
    @Config(qualifiers = "ru-rRU-w1760dp-h720dp-land-mdpi")
    fun carLeftHandOnOpen() {
        show { CarPlayerForTest(track = queue[11], queue = queue, initialQueueOpen = true, queueName = "С семьёй") }
        snap("queue_prev_car_left_ru")
    }

    @Test
    @Config(qualifiers = "ru-rRU-w1760dp-h720dp-land-mdpi")
    fun carRightHandNearTheEnd() {
        show { CarPlayerForTest(track = queue[46], queue = queue, steeringWheelOnLeft = false, initialQueueOpen = true, queueName = "С семьёй") }
        snap("queue_prev_car_right_end_ru")
    }
}
