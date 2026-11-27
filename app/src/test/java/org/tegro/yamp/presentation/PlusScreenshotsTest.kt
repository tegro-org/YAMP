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
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
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
import org.tegro.yamp.data.repository.RadioRepository
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.model.RadioCategory
import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.presentation.account.AccountManagementDialog
import org.tegro.yamp.presentation.account.AccountQualitySheet
import org.tegro.yamp.presentation.account.AccountQualityUi
import org.tegro.yamp.presentation.account.accountTags
import org.tegro.yamp.presentation.common.LocalPlusBlock
import org.tegro.yamp.presentation.common.PlusBlock
import org.tegro.yamp.presentation.hifi.ArtworkTint
import org.tegro.yamp.presentation.hifi.HiFiBackdrop
import org.tegro.yamp.presentation.hifi.LocalArtworkTint
import org.tegro.yamp.presentation.hifi.PanelSide
import org.tegro.yamp.presentation.main.PlusNoticeDialog
import org.tegro.yamp.presentation.main.components.LocalAppVersion
import org.tegro.yamp.presentation.main.components.SettingsActions
import org.tegro.yamp.presentation.main.components.SettingsSheet
import org.tegro.yamp.presentation.main.components.SettingsUiState
import org.tegro.yamp.presentation.player.FullScreenPlayer
import org.tegro.yamp.presentation.queues.QueueRowState
import org.tegro.yamp.presentation.queues.QueueSwitcherRequest
import org.tegro.yamp.presentation.queues.QueueSwitcherSheet
import org.tegro.yamp.presentation.queues.QueueSwitcherUiState
import org.tegro.yamp.presentation.radio.RadioScreen
import org.tegro.yamp.ui.theme.YandexMusicTheme
import java.util.TimeZone

/**
 * The Yandex Plus gate's states (D101, canvas `px-*`): the account's card in Settings, Account management and its sheet,
 * the player and the radio with the panel in place of the cover / the hero, the queues, and the sign-in notice.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class PlusScreenshotsTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).coroutineContext(Dispatchers.Unconfined).build())
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
    }

    private val tint = ArtworkTint(Color(0xFF3F6FC4), Color(0xFF1B2A6B))

    private val options = RoborazziOptions(
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f)
    )

    private fun show(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            YandexMusicTheme {
                CompositionLocalProvider(LocalArtworkTint provides tint, LocalAppVersion provides "0.8.0") {
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

    private fun snap(name: String) = captureScreenRoboImage("src/test/screenshots/$name.png", options)

    private val anna = UserAccount(uid = "1001", login = "anna", displayName = "Anna", token = "t", hasPlus = true, plusCheckedAt = 1L)
    private val rock = UserAccount(uid = "2002", login = "rock", displayName = "Rock", token = "t", hasPlus = false, plusCheckedAt = 1L)
    private val rockKey = AccountId.yandex("2002").value
    private val tags = accountTags(listOf(anna, rock))

    private val block = PlusBlock("Rock", canSwitch = true, onSubscribe = {}, onQueues = {}, onAddSource = {})

    private fun track(id: String, title: String, artist: String) =
        TrackDetailsDto(id = JsonPrimitive(id), title = title, artists = listOf(ArtistDto(name = artist)), durationMs = 261_000)

    private val tracks = listOf(track("1", "Copper Sky", "Hollow Pines"), track("2", "Low Orbit", "Static Bloom")).map { it.toTrack() }

    // ---- 1 · cards

    private val settingsState = SettingsUiState(accountName = "Rock", hasPlus = false, plusMissing = true)

    @Composable
    private fun Settings(state: SettingsUiState) =
        SettingsSheet(state = state, actions = SettingsActions(), onDismiss = {})

    @Test
    fun settingsCardSaysThereIsNoPlus() {
        show { Settings(settingsState) }
        snap("settings_plus_missing")
    }

    @Test
    fun settingsCardWithTheDeveloperOptionOn() {
        show { Settings(settingsState.copy(developerSection = true, allowYandexWithoutPlus = true)) }
        snap("settings_plus_developer_tag")
    }

    @Composable
    private fun Accounts(allow: Boolean) = AccountManagementDialog(
        accounts = listOf(anna, rock), defaultAccountUid = "1001",
        onRemoveAccount = {}, onAddNewAccount = {}, onLogoutCurrent = {}, onLogoutAll = {}, onDismiss = {},
        quality = quality(), allowWithoutPlus = allow
    )

    private fun quality(blocked: Set<String> = emptySet()) = AccountQualityUi(
        choices = emptyMap(), global = false, nowMetered = false, onSet = { _, _, _ -> }, onOpenWifi = {}, blocked = blocked
    )

    @Test
    fun accountManagementShowsTheMissingPlusOnTheCard() {
        show { Accounts(allow = false) }
        snap("accounts_plus_missing")
    }

    @Test
    fun accountManagementShowsTheDeveloperTag() {
        show { Accounts(allow = true) }
        snap("accounts_plus_developer_tag")
    }

    @Test
    fun theAccountSheetOpensWithTheSubscriptionCard() {
        show { AccountQualitySheet("Rock", rockKey, jellyfin = false, quality = quality(setOf(rockKey)), big = false, onDismiss = {}) }
        snap("quality_sheet_plus_missing")
    }

    @Test
    @Config(qualifiers = "w1920dp-h720dp-land-mdpi")
    fun theAccountSheetOnTheHeadUnit() {
        show { AccountQualitySheet("Rock", rockKey, jellyfin = false, quality = quality(setOf(rockKey)), big = true, onDismiss = {}) }
        snap("quality_sheet_head_unit_plus_missing")
    }

    // ---- 2 · player

    @Composable
    private fun Player(block: PlusBlock) = CompositionLocalProvider(LocalPlusBlock provides block) {
        FullScreenPlayer(
            track = tracks[0], isPlaying = false, isLiked = true, progress = { 0L }, queue = tracks,
            onPlayPauseClick = {}, onToggleLike = {}, onNextClick = {}, onPrevClick = {}, onSeekTo = {},
            onTrackSelect = {}, onRemoveFromQueue = {}, onCollapse = {}, onCloseAndClear = {}, onDislikeClick = {},
            caption = "Queue · 3 of 112", queueName = "Solo drive", upcoming = tracks.drop(1)
        )
    }

    @Test
    fun thePlayerShowsThePanelInTheCoversPlace() {
        show { Player(block) }
        snap("player_plus_missing")
    }

    @Test
    @Config(qualifiers = "+ru-rRU")
    fun thePlayerPanelInRussianWithNothingElseToPlay() {
        show { Player(PlusBlock("Rock", canSwitch = false, onSubscribe = {}, onQueues = {}, onAddSource = {})) }
        snap("player_plus_missing_alone_ru")
    }

    /** A small phone: the dimmed controls give their room away and the panel keeps both actions in view. */
    @Test
    @Config(qualifiers = "ru-rRU-w320dp-h470dp-port-mdpi")
    fun thePlayerPanelOnAShortScreenKeepsItsActions() {
        show { Player(PlusBlock("Rock", canSwitch = true, onSubscribe = {}, onQueues = {}, onAddSource = {})) }
        snap("player_plus_missing_short_ru")
    }

    @Test
    @Config(qualifiers = "w1920dp-h720dp-land-mdpi")
    fun theCarPlayerShowsThePanel() {
        show { CompositionLocalProvider(LocalPlusBlock provides block) { CarPlayerForTest(track = tracks[0], queue = tracks, caption = "Queue · 3 of 112", queueName = "Solo drive") } }
        snap("player_car_plus_missing")
    }

    // ---- 2 · queues

    private val queueRows = listOf(
        QueueRowState(11, "Evening", QueueKind.STATIC, "Night Drive", "Lumen Lane", null, 0.42f, 12, 48, isActive = true, account = tags[0].key),
        QueueRowState(20, "My Wave", QueueKind.WAVE, "Low Orbit", "Static Bloom", null, 0.1f, 2, 30, isActive = false, account = tags[1].key),
        QueueRowState(21, "Solo drive", QueueKind.STATIC, "Copper Sky", "Hollow Pines", null, 0.03f, 3, 112, isActive = false, account = tags[1].key)
    )

    @Test
    fun theQueuesOfAnAccountWithoutPlusStayListedAndDimmed() {
        show {
            QueueSwitcherSheet(
                QueueSwitcherUiState(queueRows, activeId = 11, mode = QueueSwitcherRequest.Switch(), accounts = tags, plusBlocked = setOf(tags[1].key)),
                car = false, side = PanelSide.RIGHT, onDismiss = {}, onSelect = {}, onLongPress = {}, onNew = {}
            )
        }
        snap("queues_plus_missing")
    }

    // ---- 3 · the notice after a sign-in

    @Test
    fun theSignInNoticeSaysWhatWillNotPlay() {
        show { PlusNoticeDialog("Rock", onSubscribe = {}, onDismiss = {}) }
        snap("plus_notice")
    }

    @Test
    @Config(qualifiers = "ru-rRU-w1920dp-h720dp-land-mdpi")
    fun theSignInNoticeOnTheHeadUnitInRussian() {
        show { PlusNoticeDialog("Rock", onSubscribe = {}, onDismiss = {}) }
        snap("plus_notice_head_ru")
    }

    // ---- 4 · FM radio

    private val stations get() = RadioRepository(ApplicationProvider.getApplicationContext()).getBuiltinStations()

    @Composable
    private fun Radio() = CompositionLocalProvider(LocalPlusBlock provides block) {
        RadioScreen(
            stations = stations, allStations = stations, selectedCategory = RadioCategory.ALL,
            onCategorySelect = {}, currentStationId = null, isPlaying = false,
            favoriteStationIds = setOf(stations[1].id, stations[2].id), onStationClick = {}, onToggleFavorite = {}, onPlayPause = {}
        )
    }

    @Test
    fun theRadioHeroBecomesThePanel() {
        show { Radio() }
        snap("radio_plus_missing")
    }

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun theRadioHeroOnTheHeadUnit() {
        show { Radio() }
        snap("head_radio_plus_missing")
    }
}
