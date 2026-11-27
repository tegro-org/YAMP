package org.tegro.yamp.presentation

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.dto.ArtistDto
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.provider.yandex.toTrack
import org.tegro.yamp.presentation.main.components.MiniPlayer
import org.tegro.yamp.ui.theme.YandexMusicTheme

/**
 * The mini-player is fixed chrome: its two lines keep the head unit's 24 dp boxes whatever the system font size is
 * (at scale 1.3 the two boxes alone would be 62 dp — the whole capsule). Bigger UI comes from the app's own scale,
 * which grows dp and sp together, not from the phone's font setting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class MiniPlayerScaleTest {

    @get:Rule
    val compose = createComposeRule()

    private val track = TrackDetailsDto(
        id = JsonPrimitive("1"), title = "Title", artists = listOf(ArtistDto(name = "Artist")), durationMs = 200_000
    ).toTrack()

    private fun show(fontScale: Float) {
        compose.setContent {
            YandexMusicTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    MiniPlayer(
                        track = track, isPlaying = true, progress = { 0L },
                        onPlayPauseClick = {}, onNextClick = {}, onClick = {}, onDismiss = {}
                    )
                }
            }
        }
    }

    private fun lineBoxOf(text: String): Float =
        compose.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }

    private fun assertLineBoxes() {
        assertEquals("The title keeps its 24 dp line box", 24f, lineBoxOf("Title"), 0.5f)
        assertEquals("The subtitle keeps its 24 dp line box", 24f, lineBoxOf("Artist"), 0.5f)
    }

    @Test
    fun systemFontScaleOne() {
        show(1f)
        assertLineBoxes()
    }

    @Test
    fun systemFontScaleLarge() {
        show(1.3f)
        assertLineBoxes()
    }
}
