package org.tegro.yamp.presentation

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.MainActivity
import org.tegro.yamp.presentation.main.PlayerOpenRule
import org.tegro.yamp.presentation.player.QueueSheetState
import org.tegro.yamp.presentation.player.rememberQueueSheetState

/** Coming back to YAMP shows the screen the user left: the player, its queue sheet, the tab. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReopenStateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theOpenQueueSheetAndTheOpenPlayerSurviveRecreation() {
        val restoration = StateRestorationTester(compose)
        lateinit var sheet: QueueSheetState
        var playerOpen = false
        restoration.setContent {
            sheet = rememberQueueSheetState()
            var expanded by rememberSaveable { mutableStateOf(false) }
            playerOpen = expanded
            if (!expanded) expanded = true
        }
        compose.runOnIdle {
            sheet.heightPx = 1000f
            sheet.dragBy(-2000f) // dragged fully up
        }
        compose.runOnIdle { assertEquals(1f, sheet.fraction) }

        restoration.emulateSavedInstanceStateRestore()

        compose.runOnIdle {
            assertTrue(playerOpen)
            assertTrue(sheet.isVisible)
            assertEquals(1f, sheet.fraction)
        }
    }

    @Test
    fun aClosedQueueSheetStaysClosed() {
        val restoration = StateRestorationTester(compose)
        lateinit var sheet: QueueSheetState
        restoration.setContent { sheet = rememberQueueSheetState() }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertFalse(sheet.isVisible) }
    }

    @Test
    fun theOpenPlayerClosesOnlyWhenATrackWentAway() {
        // A restored session still loading: no track yet, the player stays open
        assertFalse(PlayerOpenRule.closesPlayer(hadTrack = false, hasTrack = false))
        assertFalse(PlayerOpenRule.closesPlayer(hadTrack = true, hasTrack = true))
        // The queue ran empty while the player was open
        assertTrue(PlayerOpenRule.closesPlayer(hadTrack = true, hasTrack = false))
    }

    @Test
    fun theLauncherIconOverARunningTaskIsADuplicate() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        assertTrue(MainActivity.isLauncherReentry(isTaskRoot = false, intent = launcher))
        // The first start from the icon is the task's root
        assertFalse(MainActivity.isLauncherReentry(isTaskRoot = true, intent = launcher))
        // A notification / widget / Android Auto intent is never dropped
        val openPlayer = Intent().putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
        assertFalse(MainActivity.isLauncherReentry(isTaskRoot = false, intent = openPlayer))
        assertFalse(MainActivity.isLauncherReentry(isTaskRoot = false, intent = null))
    }
}
