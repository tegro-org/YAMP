package org.tegro.yamp.presentation

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.repository.SettingsRepository
import org.tegro.yamp.presentation.account.AccountQualityContent
import org.tegro.yamp.presentation.account.AccountQualityUi
import org.tegro.yamp.presentation.hifi.HiFiBackdrop
import org.tegro.yamp.presentation.hifi.HiFiDialogDefaults
import org.tegro.yamp.ui.theme.YandexMusicTheme

/** Quality per account (canvas `qa-sheet-yandex`, `qa-sheet-jellyfin`, `qa-head-*`): the account sheet's content. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class AccountQualityScreenshotsTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f)
    )

    private val yandex = AccountId.yandex("42").value
    private val jellyfin = AccountId.jellyfin("srv", "user").value

    /** Wi-Fi: lossless / original; metered: compatible / converted; the car is on a metered network now. */
    private fun quality(account: String) = AccountQualityUi(
        choices = mapOf(
            SettingsRepository.accountQualityKey(account, metered = false) to false,
            SettingsRepository.accountQualityKey(account, metered = true) to true
        ),
        global = false,
        nowMetered = true,
        onSet = { _, _, _ -> },
        onOpenWifi = {}
    )

    private fun show(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            YandexMusicTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    HiFiBackdrop()
                    content()
                }
            }
        }
        repeat(10) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Composable
    private fun Sheet(account: String, jellyfinAccount: Boolean, big: Boolean) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = if (big) 760.dp else 560.dp)
                .clip(HiFiDialogDefaults.Shape)
                .background(HiFiDialogDefaults.Container)
                .verticalScroll(rememberScrollState())
                .padding(if (big) 28.dp else 22.dp)
        ) { AccountQualityContent(account, jellyfinAccount, quality(account), big) }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun snap(name: String) = captureScreenRoboImage("src/test/screenshots/$name.png", options)

    @Test
    fun yandexAccount() {
        show { Sheet(yandex, jellyfinAccount = false, big = false) }
        snap("quality_sheet_yandex")
    }

    @Test
    fun jellyfinAccount() {
        show { Sheet(jellyfin, jellyfinAccount = true, big = false) }
        snap("quality_sheet_jellyfin")
    }

    @Test
    @Config(qualifiers = "ru-rRU-w1920dp-h720dp-land-mdpi")
    fun headUnitBiggerSwitches() {
        show { Sheet(yandex, jellyfinAccount = false, big = true) }
        snap("quality_sheet_head_unit_ru")
    }
}
