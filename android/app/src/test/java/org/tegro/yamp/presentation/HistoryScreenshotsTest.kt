package org.tegro.yamp.presentation

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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
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
import org.tegro.yamp.presentation.hifi.ArtworkTint
import org.tegro.yamp.presentation.hifi.HiFiBackdrop
import org.tegro.yamp.presentation.hifi.LocalArtworkTint
import org.tegro.yamp.presentation.history.DayKey
import org.tegro.yamp.presentation.history.DaySection
import org.tegro.yamp.presentation.history.HistoryFilter
import org.tegro.yamp.presentation.history.HistoryRow
import org.tegro.yamp.presentation.history.HistoryScreen
import org.tegro.yamp.presentation.history.HistoryUiState
import org.tegro.yamp.presentation.main.components.DockBottomGap
import org.tegro.yamp.presentation.main.components.DockClearance
import org.tegro.yamp.presentation.main.components.DockScrim
import org.tegro.yamp.presentation.main.components.FloatingGlassDock
import org.tegro.yamp.presentation.main.components.LocalTrackMenu
import org.tegro.yamp.presentation.main.components.TrackMenuLauncher
import org.tegro.yamp.ui.theme.YandexMusicTheme
import java.time.LocalDate
import java.util.TimeZone

/**
 * Phase 1b History (docs/phase-1b-history.md, Design/yamp/history.jsx): the page with the queue chips and the
 * day groups, on a phone and on the head unit, and its empty state. Rendered from fixed states, no ViewModel;
 * same helpers and options as [ScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class HistoryScreenshotsTest {

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
                // The app provides the track menu, so every row gets its "⋮"
                CompositionLocalProvider(LocalArtworkTint provides tint, LocalTrackMenu provides TrackMenuLauncher { _, _ -> }) {
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
        TrackDetailsDto(id = JsonPrimitive(id), title = title, artists = listOf(ArtistDto(name = artist)), durationMs = 215_000)

    private fun row(id: Long, title: String, artist: String, queue: String = "With family") =
        HistoryRow(id, track(id.toString(), title, artist), queue)

    /** The canvas `useQueues()`: the active queue first, the others, one that lives on only here, "All". */
    private val filters = listOf(
        HistoryFilter(1L, "With family", QueueKind.STATIC),
        HistoryFilter(2L, "Solo drive", QueueKind.STATIC),
        HistoryFilter(3L, "Audiobook", QueueKind.STATIC),
        HistoryFilter(4L, "My Wave", QueueKind.WAVE),
        HistoryFilter(9L, "Road trip", null),
        HistoryFilter(null, null, null)
    )

    private val days = listOf(
        DaySection(
            DayKey.Today,
            listOf(
                row(1, "Wish You Were Here", "Pink Floyd"),
                row(2, "Shine On You Crazy Diamond", "Pink Floyd"),
                row(3, "Дороги", "Мельница")
            )
        ),
        DaySection(
            DayKey.Yesterday,
            listOf(
                row(4, "Comfortably Numb", "Pink Floyd"),
                row(5, "Du hast", "Rammstein", queue = "Solo drive")
            )
        ),
        DaySection(
            DayKey.Date(LocalDate.of(2026, 11, 3), thisYear = true),
            listOf(
                row(6, "Echoes", "Pink Floyd"),
                row(7, "Chapter 14", "Roadside Picnic", queue = "Audiobook")
            )
        )
    )

    private val state = HistoryUiState(filters = filters, selectedQueueId = 1L, days = days, enabled = true, loading = false, weekCount = 7)

    @Composable
    private fun History(modifier: Modifier = Modifier, state: HistoryUiState = this.state, contentBottom: Dp = 100.dp) {
        HistoryScreen(
            state = state, currentTrackId = "1", isPlaying = true, likedTrackIds = setOf("1", "4"), downloadedTrackIds = setOf("2"),
            contentBottom = contentBottom, onBack = {}, onOpened = {}, onSelectFilter = {}, onTrackClick = {}, onToggleLike = {}, onClear = {},
            modifier = modifier
        )
    }

    /** The page as it opens: the active queue's chip selected, the rows by day. */
    @Test
    fun historyPhone() {
        show { History() }
        snap("history")
    }

    /** "All": every row says which queue it played in. */
    @Test
    fun historyAllQueues() {
        show { History(state = state.copy(selectedQueueId = null)) }
        snap("history_all")
    }

    @Test
    fun historyEmpty() {
        show { History(state = state.copy(days = emptyList())) }
        snap("history_empty")
    }

    /** Two columns on the head unit, the tab dock along the bottom as the app lays the page out. */
    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun historyHeadUnit() {
        show {
            Box(Modifier.fillMaxSize()) {
                History(contentBottom = DockClearance)
                DockScrim(Modifier.align(Alignment.BottomCenter))
                FloatingGlassDock(
                    currentRoute = "library",
                    onNavigate = {},
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = DockBottomGap),
                    player = {}
                )
            }
        }
        snap("head_history")
    }

    private companion object {
        const val HEAD_UNIT = "w1280dp-h720dp-land-mdpi"
    }
}
