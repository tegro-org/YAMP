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
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tegro.yamp.data.player.LinkState
import org.tegro.yamp.data.queue.ActiveQueue
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.queue.PlayQueue
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.presentation.auth.WelcomeScreen
import org.tegro.yamp.presentation.car.LocalCarClock
import org.tegro.yamp.presentation.hifi.ArtworkTint
import org.tegro.yamp.presentation.hifi.HiFiBackdrop
import org.tegro.yamp.presentation.hifi.LocalArtworkTint
import org.tegro.yamp.presentation.hifi.LocalLinkState
import org.tegro.yamp.presentation.main.HomeScreen
import org.tegro.yamp.presentation.main.homeModeOf
import org.tegro.yamp.presentation.main.queueShelfOf
import org.tegro.yamp.provider.jellyfin.JellyfinRefs
import org.tegro.yamp.ui.theme.YandexMusicTheme

/** The first start and Jellyfin only (canvas `wl-welcome`, `wl-welcome-head`, `wl-jf-home`, `wl-jf-car`). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class WelcomeScreenshotsTest {

    @get:Rule
    val compose = createComposeRule()

    private val tint = ArtworkTint(Color(0xFF5A3E8C), Color(0xFF2A1B4B))

    private val options = RoborazziOptions(
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f)
    )

    private fun show(backdrop: Boolean = true, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            YandexMusicTheme {
                CompositionLocalProvider(LocalArtworkTint provides tint, LocalCarClock provides { FIXED_TIME }, LocalLinkState provides LinkState.GOOD) {
                    Box(Modifier.fillMaxSize()) {
                        if (backdrop) HiFiBackdrop(tint = tint)
                        content()
                    }
                }
            }
        }
        repeat(10) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun snap(name: String) = captureScreenRoboImage("src/test/screenshots/$name.png", options)

    /** A new Jellyfin account's default queue (D83), still empty. */
    private val emptyDefault = ActiveQueue(
        queue = PlayQueue(id = 1L, name = "Home Media", kind = QueueKind.STATIC, account = "jellyfin@srv1:u1"),
        entries = emptyList(),
        tracks = emptyList(),
        cursorIndex = -1
    )

    @Test
    fun welcome() {
        show(backdrop = false) { WelcomeScreen(onLocal = {}, onJellyfin = {}) }
        snap("welcome")
    }

    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun welcomeWideHeadUnit() {
        show(backdrop = false) { WelcomeScreen(onLocal = {}, onJellyfin = {}) }
        snap("welcome_wide_head")
    }

    @Test
    @Config(qualifiers = "ru")
    fun welcomeRu() {
        show(backdrop = false) { WelcomeScreen(onLocal = {}, onJellyfin = {}) }
        snap("welcome_ru")
    }

    @Test
    @Config(qualifiers = "ru-rRU-w1920dp-h720dp-land-mdpi")
    fun welcomeWideHeadUnitRu() {
        show(backdrop = false) { WelcomeScreen(onLocal = {}, onJellyfin = {}) }
        snap("welcome_wide_head_ru")
    }

    @Test
    fun homeJellyfinOnlyEmptyQueue() {
        show {
            HomeScreen(
                waveTrack = null, isPlaying = false, isLoading = false, isLiked = false, progress = { 0L },
                shelf = queueShelfOf(emptyDefault, playing = false), shimmer = false,
                onPlayPause = {}, onLike = {}, onDislike = {}, onOpenPlayer = null, onShelfClick = {}, queueName = "Home Media",
                mode = homeModeOf(emptyDefault), emptyQueue = true
            )
        }
        snap("home_jellyfin_only_empty")
    }

    @Test
    @Config(qualifiers = "+land")
    fun carJellyfinOnly() {
        val song = Track(
            ref = JellyfinRefs("srv1").track("j1"), title = "Fluffing a Duck", artists = emptyList(), album = null,
            durationMs = 180_000L, cover = null, explicit = false
        )
        show {
            CarPlayerForTest(track = song, queue = listOf(song), upcoming = emptyList(), waveActive = false, queueName = "Home Media", caption = "")
        }
        snap("car_jellyfin_only")
    }

    private companion object {
        const val WIDE_HEAD_UNIT = "w1920dp-h720dp-land-mdpi"
        const val FIXED_TIME = 1_792_567_800_000L
    }
}
