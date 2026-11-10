package org.tegro.yamp

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.tegro.yamp.data.dto.ArtistDto
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.repository.RadioRepository
import org.tegro.yamp.domain.model.RadioCategory
import org.tegro.yamp.presentation.car.CarScreen
import org.tegro.yamp.presentation.main.HomeScreen
import org.tegro.yamp.presentation.main.LibraryScreen
import org.tegro.yamp.presentation.main.SearchScreen
import org.tegro.yamp.presentation.radio.RadioScreen
import org.tegro.yamp.ui.theme.YandexMusicTheme
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Edge-to-edge screens on a real window: nothing a user reads or taps may sit under the status bar
 * (or, in car mode, under the navigation bar). Replaces the old source-text checks for
 * `statusBarsPadding()`, which passed even when the padding was on the wrong element.
 */
@RunWith(AndroidJUnit4::class)
class ScreenInsetsInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val tracks = listOf(
        TrackDetailsDto(id = JsonPrimitive("1"), title = "Первая песня", artists = listOf(ArtistDto(name = "Группа"))),
        TrackDetailsDto(id = JsonPrimitive("2"), title = "Вторая песня", artists = listOf(ArtistDto(name = "Исполнитель")))
    )

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent { YandexMusicTheme { content() } }
        compose.mainClock.autoAdvance = false // infinite equalizer animations
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun systemBars(): androidx.core.graphics.Insets {
        var insets = androidx.core.graphics.Insets.NONE
        compose.runOnUiThread {
            val root = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
            insets = root.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        }
        return insets
    }

    /** Visible nodes that show text or an icon with a description: what the user reads or taps. */
    private fun visibleContent(): List<SemanticsNode> {
        val readable = SemanticsMatcher("text or description") {
            it.config.contains(SemanticsProperties.Text) || it.config.contains(SemanticsProperties.ContentDescription)
        }
        val height = compose.activity.window.decorView.height
        return compose.onAllNodes(readable, useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.boundsInWindow.height > 0 && it.boundsInWindow.bottom > 0 && it.boundsInWindow.top < height }
    }

    private fun assertBelowStatusBar(screen: String) {
        val statusBar = systemBars().top
        assertTrue("status bar must be measured", statusBar > 0)
        val nodes = visibleContent()
        assertTrue("$screen shows content", nodes.isNotEmpty())
        val top = nodes.minOf { it.boundsInWindow.top }
        assertTrue("$screen: content at y=$top is under the status bar (${statusBar}px)", top >= statusBar - 1)
    }

    @Test
    fun home() {
        show { HomeScreen(
                waveTrack = null, isPlaying = false, isLoading = false, isLiked = false, progress = { 0L },
                shelf = org.tegro.yamp.presentation.main.waveShelfOf(false, emptyList(), emptyList()), shimmer = false,
                onPlayPause = {}, onLike = {}, onDislike = {}, onOpenPlayer = {}, onShelfClick = {}
            ) }
        assertBelowStatusBar("Home")
    }

    @Test
    fun search() {
        show {
            SearchScreen(
                query = "", onQueryChange = {}, results = emptyList(), weeklyHits = tracks, trendTracks = tracks,
                isSearching = false, currentTrackId = null, isPlaying = false, likedTrackIds = emptySet(),
                onTrackClick = { _, _ -> }, onToggleLike = {}
            )
        }
        assertBelowStatusBar("Search")
    }

    @Test
    fun library() {
        show {
            LibraryScreen(
                tracks = tracks, isLoading = false, currentTrackId = null, isPlaying = false, likedTrackIds = setOf("1", "2"), downloadedTrackIds = emptySet(),
                downloadProgress = null, onTrackClick = {}, onPlayAllClick = {}, onShuffleClick = {},
                onDownloadAllClick = {}, onCancelDownload = {}, onToggleLike = {}
            )
        }
        assertBelowStatusBar("Library")
    }

    @Test
    fun radio() {
        val stations = RadioRepository(compose.activity.applicationContext).getBuiltinStations()
        show {
            RadioScreen(
                stations = stations, allStations = stations, selectedCategory = RadioCategory.ALL,
                onCategorySelect = {}, currentStationId = null, isPlaying = false, favoriteStationIds = emptySet(),
                onStationClick = {}, onToggleFavorite = {}, onPlayPause = {}
            )
        }
        assertBelowStatusBar("Radio")
    }

    @Test
    fun carStaysClearOfBothSystemBars() {
        show {
            CarScreen(
                currentTrack = tracks[0], isPlaying = true, likedTrackIds = emptySet(), onPlayPauseClick = {}, onNextClick = {},
                onPrevClick = {}, onToggleLike = {}, onDislikeClick = {}, onPlayMyVibe = {}, onPlayFavorites = {}, onPlayHits = {},
                onPlayDownloaded = {}, onPlayRadio = {}, onExit = {}, progress = { 60_000L }, durationMs = 215_000
            )
        }
        assertBelowStatusBar("Car")
        val navBar = systemBars().bottom
        val windowHeight = compose.activity.window.decorView.height
        val bottom = visibleContent().maxOf { it.boundsInWindow.bottom }
        assertTrue("Car: content down to y=$bottom is under the navigation bar", bottom <= windowHeight - navBar + 1)
    }
}
