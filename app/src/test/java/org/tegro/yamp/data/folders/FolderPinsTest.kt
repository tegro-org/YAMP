package org.tegro.yamp.data.folders

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.domain.account.AccountId

/** Pinned folders are kept per account, in pinning order, and survive a restart (a new store on the same file). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FolderPinsTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("pins_test", Context.MODE_PRIVATE)
    private val a = AccountId.jellyfin("srv", "u1")
    private val b = AccountId.jellyfin("srv", "u2")

    @Test
    fun pinsArePerAccountAndPersist() {
        val pins = FolderPins { prefs }
        assertTrue(pins.toggle(a, PinnedFolder("f1", "Pink Floyd", "http://x/Items/f1/Images/Primary?tag=t")))
        assertTrue(pins.toggle(a, PinnedFolder("f2", "RC - Sorted")))
        assertTrue(pins.toggle(b, PinnedFolder("f9", "Queen")))
        assertEquals(listOf("f1", "f2"), pins.of(a).map { it.id })
        assertFalse(pins.toggle(a, PinnedFolder("f1", "Pink Floyd")))
        assertEquals(listOf("f2"), pins.of(a).map { it.id })

        val again = FolderPins { prefs }
        assertEquals(listOf("f2"), again.of(a).map { it.id })
        assertEquals(listOf("Queen"), again.of(b).map { it.title })
        assertTrue(again.isPinned(b, "f9"))
    }

    @Test
    fun theCarTabIsRememberedPerAccount() {
        val pins = FolderPins { prefs }
        pins.rememberTab(a, "FOLDERS")
        assertEquals("FOLDERS", FolderPins { prefs }.lastTab(a))
        assertEquals(null, pins.lastTab(b))
    }
}
