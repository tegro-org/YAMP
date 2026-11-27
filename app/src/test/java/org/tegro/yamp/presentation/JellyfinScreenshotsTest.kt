package org.tegro.yamp.presentation

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.account.ProviderAccount
import org.tegro.yamp.domain.account.toProviderAccount
import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.presentation.account.AccountManagementDialog
import org.tegro.yamp.presentation.account.AddAccountChooser
import org.tegro.yamp.presentation.account.JellyfinAccountRow
import org.tegro.yamp.presentation.account.JellyfinSignInActions
import org.tegro.yamp.presentation.account.JellyfinSignInSheet
import org.tegro.yamp.presentation.account.JellyfinSignInUiState
import org.tegro.yamp.presentation.account.JellyfinSignInUiState.AddressCheck
import org.tegro.yamp.presentation.account.providerAccountTags
import org.tegro.yamp.presentation.hifi.ArtworkTint
import org.tegro.yamp.presentation.hifi.HiFiBackdrop
import org.tegro.yamp.presentation.hifi.LocalArtworkTint
import org.tegro.yamp.provider.jellyfin.ServerCheck
import org.tegro.yamp.ui.theme.YandexMusicTheme

/** Phase 4 step 21: the Jellyfin sign-in and accounts, per the step-20 canvas (section 9). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class JellyfinScreenshotsTest {

    @get:Rule
    val compose = createComposeRule()

    private val tint = ArtworkTint(Color(0xFF3F6FC4), Color(0xFF1B2A6B))

    private val options = RoborazziOptions(
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f)
    )

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
        repeat(10) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun snap(name: String) = captureScreenRoboImage("src/test/screenshots/$name.png", options)

    private val found = AddressCheck.Found("Home Media", "10.10.3", quickConnect = true)

    private fun signIn(state: JellyfinSignInUiState) = show { JellyfinSignInSheet(state, JellyfinSignInActions(), onDismiss = {}) }

    @Test
    fun addAccountChooser() {
        show { AddAccountChooser(onYandex = {}, onJellyfin = {}, onDismiss = {}, onLocal = {}) }
        snap("jellyfin_add_chooser")
    }

    @Test
    fun addressChecking() {
        signIn(JellyfinSignInUiState(address = "https://music.example.org", check = AddressCheck.Checking))
        snap("jellyfin_address_checking")
    }

    @Test
    fun addressFoundWithQuickConnect() {
        signIn(JellyfinSignInUiState(address = "https://music.example.org", check = found, way = JellyfinSignInUiState.Way.QUICK_CONNECT))
        snap("jellyfin_address_found")
    }

    @Test
    fun addressUnreachable() {
        signIn(JellyfinSignInUiState(address = "https://music.example.org", check = AddressCheck.Unreachable))
        snap("jellyfin_address_unreachable")
    }

    @Test
    fun addressNotJellyfin() {
        signIn(JellyfinSignInUiState(address = "https://music.example.org", check = AddressCheck.NotJellyfin))
        snap("jellyfin_address_not_jellyfin")
    }

    @Test
    fun addressPlainHttpRefused() {
        signIn(JellyfinSignInUiState(address = "http://music.example.org", check = AddressCheck.Bad(ServerCheck.BadAddress.Reason.NOT_HTTPS)))
        snap("jellyfin_address_http")
    }

    @Test
    fun quickConnectCodeWaiting() {
        signIn(JellyfinSignInUiState(address = "https://music.example.org", check = found, way = JellyfinSignInUiState.Way.QUICK_CONNECT, code = "482915"))
        snap("jellyfin_quick_connect_code")
    }

    @Test
    fun quickConnectApproved() {
        signIn(JellyfinSignInUiState(address = "https://music.example.org", check = found, way = JellyfinSignInUiState.Way.QUICK_CONNECT, code = "482915", approved = true, busy = true))
        snap("jellyfin_quick_connect_approved")
    }

    @Test
    fun passwordWithAWrongPassword() {
        signIn(
            JellyfinSignInUiState(
                address = "https://music.example.org", check = found, way = JellyfinSignInUiState.Way.PASSWORD,
                user = "alex", password = "secret", error = JellyfinSignInUiState.Failure.WRONG_CREDENTIALS
            )
        )
        snap("jellyfin_password_wrong")
    }

    @Test
    fun duplicateRefused() {
        signIn(JellyfinSignInUiState(address = "https://music.example.org", check = found, user = "alex", duplicate = "alex · Home Media"))
        snap("jellyfin_duplicate")
    }

    @Test
    fun accountsWithJellyfin() {
        val me = UserAccount(uid = "1", login = "me", displayName = "Тестовый Аккаунт", token = "t")
        val car = UserAccount(uid = "2", login = "car", displayName = "Машина", token = "t")
        val jf = ProviderAccount(Provider.JELLYFIN, AccountId.jellyfin("srv1", "u1"), "alex · Home Media", null, false)
        val tags = providerAccountTags(listOf(me, car).map { it.toProviderAccount() } + jf)
        show {
            AccountManagementDialog(
                accounts = listOf(me, car), defaultAccountUid = "1",
                onRemoveAccount = {}, onAddNewAccount = {}, onLogoutCurrent = {}, onLogoutAll = {}, onDismiss = {},
                jellyfinAccounts = listOf(JellyfinAccountRow(tags.last(), "Jellyfin 10.10.3 · music.example.org"))
            )
        }
        snap("jellyfin_accounts")
    }

    /**
     * A head unit (1760×720 px, ≈ 1173×480 dp) with 2 Yandex + 1 Jellyfin accounts, quality and the debug
     * rows on: the dialog is one scroll, so the Jellyfin group and the buttons below are reachable — the Yandex
     * group used to scroll on its own inside a 240 dp box and the rest stayed under the screen's edge.
     */
    @Test
    @Config(qualifiers = "w1173dp-h480dp-land-hdpi")
    fun accountsOnTheHeadUnitScrolledToTheBottom() {
        val me = UserAccount(uid = "1", login = "me", displayName = "Тестовый Аккаунт", token = "t")
        val car = UserAccount(uid = "2", login = "car", displayName = "Машина", token = "t")
        val jf = ProviderAccount(Provider.JELLYFIN, AccountId.jellyfin("srv1", "u1"), "alex · Home Media", null, false)
        val tags = providerAccountTags(listOf(me, car).map { it.toProviderAccount() } + jf)
        val quality = org.tegro.yamp.presentation.account.AccountQualityUi(
            choices = emptyMap(), global = false, nowMetered = false, onSet = { _, _, _ -> }, onOpenWifi = {}
        )
        show {
            AccountManagementDialog(
                accounts = listOf(me, car), defaultAccountUid = "1",
                onRemoveAccount = {}, onAddNewAccount = {}, onLogoutCurrent = {}, onLogoutAll = {}, onDismiss = {},
                debugActions = org.tegro.yamp.presentation.account.AccountDebugActions(emptyList(), {}, {}),
                jellyfinAccounts = listOf(JellyfinAccountRow(tags.last(), "Jellyfin 10.10.3 · music.example.org")),
                quality = quality,
                // A saved order with Jellyfin last: the bottom of the scroll is where it is checked
                order = listOf(AccountId.yandex("1").value, AccountId.yandex("2").value, jf.id.value)
            )
        }
        // To the very end, as a finger would; the paused clock needs the frames advanced by hand
        val scroll = compose.onNode(hasScrollAction()).fetchSemanticsNode()
        compose.runOnUiThread { scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 10_000f) }
        repeat(5) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        compose.onNodeWithText("alex · Home Media", substring = true).assertIsDisplayed()
        compose.onNodeWithText(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
                .getString(org.tegro.yamp.R.string.account_management_vyyti_so_vseh_akkauntov)
        ).assertIsDisplayed()
        snap("jellyfin_accounts_head_unit_bottom")
    }

    // ── Account order (D90, canvas `ac-order*`): one flat list, a Y / J badge on every row, ≡ and ⋮ ──────────
    private val anna = UserAccount(uid = "1", login = "anna", displayName = "Anna", token = "t")
    private val rock = UserAccount(uid = "2", login = "rock", displayName = "Rock", token = "t")
    private val homeMedia = ProviderAccount(Provider.JELLYFIN, AccountId.jellyfin("srv1", "u1"), "dev · Home Media", null, false)
    private val homeRow = JellyfinAccountRow(
        providerAccountTags(listOf(anna, rock).map { it.toProviderAccount() } + homeMedia).last(), "Jellyfin 10.10.3 · 10.0.2.2:8096"
    )
    private val orderQuality = org.tegro.yamp.presentation.account.AccountQualityUi(
        choices = emptyMap(), global = false, nowMetered = false, onSet = { _, _, _ -> }, onOpenWifi = {}
    )

    @Composable
    private fun OrderedAccounts(wheelLeft: Boolean = true, one: Boolean = false) = AccountManagementDialog(
        accounts = if (one) listOf(anna) else listOf(anna, rock), defaultAccountUid = "1",
        onRemoveAccount = {}, onAddNewAccount = {}, onLogoutCurrent = {}, onLogoutAll = {}, onDismiss = {},
        jellyfinAccounts = if (one) emptyList() else listOf(homeRow), quality = orderQuality,
        // A mixed order: Anna · Home Media · Rock
        order = listOf(AccountId.yandex("1").value, homeMedia.id.value, AccountId.yandex("2").value),
        wheelLeft = wheelLeft
    )

    @Test
    fun accountOrderPhone() {
        show { OrderedAccounts() }
        snap("accounts_order")
    }

    @Test
    fun accountOrderOneAccount() {
        show { OrderedAccounts(one = true) }
        snap("accounts_order_one")
    }

    @Test
    fun accountOrderMenuOnTheFirstRow() {
        show { OrderedAccounts() }
        val more = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>().getString(org.tegro.yamp.R.string.cd_account_menu)
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(more))[0].performClick()
        repeat(10) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        snap("accounts_order_menu")
    }

    @Test
    @Config(qualifiers = "w1173dp-h480dp-land-hdpi")
    fun accountOrderHeadUnitLeftHandMenuOnTheLastRow() {
        show { OrderedAccounts(wheelLeft = true) }
        snap("accounts_order_head_left")
        val more = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>().getString(org.tegro.yamp.R.string.cd_account_menu)
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(more))[2].performClick()
        repeat(10) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        snap("accounts_order_head_left_menu")
    }

    @Test
    @Config(qualifiers = "w1173dp-h480dp-land-hdpi")
    fun accountOrderHeadUnitRightHand() {
        show { OrderedAccounts(wheelLeft = false) }
        snap("accounts_order_head_right")
    }

    @Test
    fun mergedSearchWithBadges() {
        // Step 24 (canvas `sr-results`): one list per type from both services, a provider badge on every row
        fun t(provider: String, id: String, title: String, artist: String) = org.tegro.yamp.domain.media.Track(
            ref = org.tegro.yamp.domain.queue.TrackRef.of(provider, id), title = title,
            artists = listOf(org.tegro.yamp.domain.media.ArtistBrief(null, artist)), album = null, durationMs = 200_000L,
            cover = null, explicit = false
        )
        val results = listOf(
            t("yandex", "1", "Группа крови", "Кино"),
            t("jellyfin@srv1", "j1", "Группа крови", "Кино"),
            t("yandex", "2", "Звезда по имени Солнце", "Кино"),
            t("jellyfin@srv1", "j2", "Кукушка", "Кино")
        )
        val artists = listOf(
            org.tegro.yamp.domain.media.Artist(org.tegro.yamp.domain.queue.MediaRef("yandex", "artist", "7"), "Кино", null),
            org.tegro.yamp.domain.media.Artist(org.tegro.yamp.domain.queue.MediaRef("jellyfin@srv1", "artist", "a1"), "Кино", null)
        )
        show {
            org.tegro.yamp.presentation.main.SearchScreen(
                query = "кино", onQueryChange = {}, results = results, weeklyHits = emptyList(), trendTracks = emptyList(),
                isSearching = false, currentTrackId = null, isPlaying = false, likedTrackIds = emptySet(),
                onTrackClick = { _, _ -> }, onToggleLike = {}, artists = artists, badges = true
            )
        }
        snap("jellyfin_search_merged")
    }
}
