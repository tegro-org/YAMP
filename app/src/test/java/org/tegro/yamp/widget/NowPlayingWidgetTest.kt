package org.tegro.yamp.widget

import android.app.Application
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NowPlayingWidgetTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun text(id: Int) = context.getString(id)

    private val playing = WidgetNow(
        title = "Группа крови", artist = "Кино", isPlaying = true, isLiked = true, isRadio = false, canLike = true, canDislike = true,
        progress = 0.4f, positionMs = 82_000, durationMs = 285_000, source = WidgetSource.WAVE, cover = null, glow = null
    )
    private val nothing = WidgetNow(null, null, false, false, false, false, false, null, 0, 0, null, null, null)
    private val noYandex = nothing.copy(canWave = false)
    private val station = playing.copy(isRadio = true, canDislike = false, progress = null, source = WidgetSource.RADIO, artist = "Live")
    private val jellyfin = playing.copy(canLike = false, canDislike = false, isLiked = false, source = null)

    @Test
    fun theLineShowsTheTrackAndTheControlsAndTheHeartWhenWide() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(320.dp, 60.dp))
        provideComposable { LineWidget(playing) }

        onNode(hasText("Группа крови")).assertExists()
        onNode(hasText("Кино")).assertExists()
        onNode(hasContentDescription(text(R.string.widget_cd_prev))).assertExists()
        onNode(hasContentDescription(text(R.string.widget_cd_play_pause))).assertExists()
        onNode(hasContentDescription(text(R.string.widget_cd_next))).assertExists()
        onNode(hasContentDescription(text(R.string.widget_cd_like))).assertExists()
    }

    @Test
    fun theNarrowLineHasNoHeart() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 60.dp))
        provideComposable { LineWidget(playing) }
        onNode(hasContentDescription(text(R.string.widget_cd_like))).assertDoesNotExist()
        onNode(hasContentDescription(text(R.string.widget_cd_play_pause))).assertExists()
    }

    @Test
    fun aNoReactionTrackHasNoHeartInTheWideLine() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(320.dp, 60.dp))
        provideComposable { LineWidget(jellyfin) }
        onNode(hasContentDescription(text(R.string.widget_cd_like))).assertDoesNotExist()
    }

    @Test
    fun withNothingPlayingTheLineInvitesToTheWave() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { LineWidget(nothing) }
        onNode(hasText(text(R.string.widget_wave_title))).assertExists()
        onNode(hasContentDescription(text(R.string.widget_wave_start))).assertExists()
    }

    @Test
    fun withoutAYandexAccountThePlayerHidesTheWaveAndOffersTheApp() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(320.dp, 180.dp))
        provideComposable { PlayerWidgetContent(noYandex) }
        onNode(hasText(text(R.string.widget_open_title))).assertExists()
        onNode(hasContentDescription(text(R.string.widget_open_start))).assertExists()
        onNode(hasContentDescription(text(R.string.widget_wave_start))).assertDoesNotExist()
        onNode(hasText(text(R.string.widget_wave_title))).assertDoesNotExist()
        onNode(hasText(text(R.string.widget_tab_music))).assertExists()
    }

    @Test
    fun withoutAYandexAccountTheLineAndTheCoverHideTheWaveToo() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(170.dp, 170.dp))
        provideComposable { CoverWidgetContent(noYandex) }
        onNode(hasContentDescription(text(R.string.widget_wave_start))).assertDoesNotExist()
        onNode(hasContentDescription(text(R.string.widget_open_start))).assertExists()
    }

    @Test
    fun withoutAYandexAccountTheLineOffersTheApp() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { LineWidget(noYandex) }
        onNode(hasContentDescription(text(R.string.widget_wave_start))).assertDoesNotExist()
        onNode(hasContentDescription(text(R.string.widget_open_start))).assertExists()
    }

    @Test
    fun thePlayerShowsEverythingWhenTall() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(320.dp, 180.dp))
        provideComposable { PlayerWidgetContent(playing) }
        onNode(hasText(text(R.string.widget_source_wave))).assertExists()
        onNode(hasText("Группа крови")).assertExists()
        onNode(hasText("1:22")).assertExists()
        onNode(hasText("−3:23")).assertExists()
        onNode(hasContentDescription(text(R.string.widget_cd_dislike))).assertExists()
        onNode(hasContentDescription(text(R.string.widget_cd_like))).assertExists()
    }

    @Test
    fun theCompactPlayerKeepsTheTransportAndDropsWhatDoesNotFit() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(280.dp, 130.dp))
        provideComposable { PlayerWidgetContent(playing) }
        onNode(hasContentDescription(text(R.string.widget_cd_prev))).assertExists()
        onNode(hasContentDescription(text(R.string.widget_cd_play_pause))).assertExists()
        onNode(hasContentDescription(text(R.string.widget_cd_next))).assertExists()
        onNode(hasContentDescription(text(R.string.widget_cd_dislike))).assertDoesNotExist()
        onNode(hasText("1:22")).assertDoesNotExist()
    }

    @Test
    fun thePlayerWithNothingPlayingOffersTheWaveAndThePages() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(320.dp, 180.dp))
        provideComposable { PlayerWidgetContent(nothing) }
        onNode(hasContentDescription(text(R.string.widget_wave_start))).assertExists()
        onNode(hasText(text(R.string.widget_tab_music))).assertExists()
        onNode(hasText(text(R.string.widget_tab_radio))).assertExists()
        onNode(hasText(text(R.string.widget_tab_search))).assertExists()
    }

    @Test
    fun theSmallestPlayerStillHasTheTransport() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(180.dp, 110.dp))
        provideComposable { PlayerWidgetContent(playing) }
        onNode(hasContentDescription(text(R.string.widget_cd_prev))).assertExists()
        onNode(hasContentDescription(text(R.string.widget_cd_play_pause))).assertExists()
        onNode(hasContentDescription(text(R.string.widget_cd_next))).assertExists()
        onNode(hasText(text(R.string.widget_source_wave))).assertDoesNotExist()
    }

    @Test
    fun aPlayerOf280By160KeepsTheChipsOfTheEmptyState() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(280.dp, 160.dp))
        provideComposable { PlayerWidgetContent(nothing) }
        onNode(hasText(text(R.string.widget_tab_radio))).assertExists()
    }

    @Test
    fun aPlayerOf280By160ShowsTheTrackWithoutTheTimes() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(280.dp, 160.dp))
        provideComposable { PlayerWidgetContent(playing) }
        onNode(hasText("Группа крови")).assertExists()
        onNode(hasText("1:22")).assertDoesNotExist()
        onNode(hasContentDescription(text(R.string.widget_cd_dislike))).assertExists()
    }

    @Test
    fun withoutAnyAccountThePlayerOffersNoPagesThatWouldOnlyAskToSignIn() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(320.dp, 180.dp))
        provideComposable { PlayerWidgetContent(noYandex.copy(hasAccount = false)) }
        onNode(hasText(text(R.string.widget_tab_radio))).assertDoesNotExist()
        onNode(hasContentDescription(text(R.string.widget_open_start))).assertExists()
    }

    @Test
    fun aStationHasNoProgressAndOnlyTheHeart() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(320.dp, 180.dp))
        provideComposable { PlayerWidgetContent(station) }
        onNode(hasText(text(R.string.widget_source_radio))).assertExists()
        onNode(hasText("1:22")).assertDoesNotExist()
        onNode(hasContentDescription(text(R.string.widget_cd_dislike))).assertDoesNotExist()
        onNode(hasContentDescription(text(R.string.widget_cd_like))).assertExists()
    }

    @Test
    fun aJellyfinTrackHasNoThumbsInThePlayer() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(320.dp, 180.dp))
        provideComposable { PlayerWidgetContent(jellyfin) }
        onNode(hasContentDescription(text(R.string.widget_cd_dislike))).assertDoesNotExist()
        onNode(hasContentDescription(text(R.string.widget_cd_like))).assertDoesNotExist()
        onNode(hasContentDescription(text(R.string.widget_cd_play_pause))).assertExists()
    }

    @Test
    fun theBigCoverShowsTheTrackAndTheButtons() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(170.dp, 170.dp))
        provideComposable { CoverWidgetContent(playing) }
        onNode(hasText("Группа крови")).assertExists()
        onNode(hasContentDescription(text(R.string.widget_cd_play_pause))).assertExists()
    }

    @Test
    fun theSmallCoverKeepsOnlyTheButtons() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(110.dp, 110.dp))
        provideComposable { CoverWidgetContent(playing) }
        onNode(hasText("Группа крови")).assertDoesNotExist()
        onNode(hasContentDescription(text(R.string.widget_cd_play_pause))).assertExists()
    }
}
