package org.tegro.yamp.data.provider.jellyfin

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.domain.account.AccountId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class JellyfinAccountStoreTest {

    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("jellyfin_test", Context.MODE_PRIVATE)

    private fun account(server: String = "srv1", user: String = "u1", token: String = "t1") = JellyfinAccount(
        serverUrl = "https://music.example.org", serverId = server, serverName = "Home Media", serverVersion = "10.10.3",
        userId = user, userName = "alex", token = token
    )

    @Test
    fun oneServerAndUserIsOneAccountASecondSignInIsRefused() {
        val store = JellyfinAccountStore { prefs }
        assertTrue(store.add(account()))
        assertFalse(store.add(account(token = "t2")))
        assertEquals(1, store.accounts.value.size)
        assertEquals("t1", store.token(AccountId.jellyfin("srv1", "u1")))
        // The same user name on another server, or another user on this one, is another account (D78)
        assertTrue(store.add(account(server = "srv2")))
        assertTrue(store.add(account(user = "u2")))
        assertEquals(3, store.accounts.value.size)
    }

    @Test
    fun accountsSurviveARestartAndSignOutDropsTheToken() {
        JellyfinAccountStore { prefs }.add(account())
        val reopened = JellyfinAccountStore { prefs }
        val id = AccountId.jellyfin("srv1", "u1")
        assertEquals("alex · Home Media", reopened.account(id)?.label)
        assertEquals("jellyfin:srv1/u1", reopened.accounts.value.single().id.value)
        reopened.remove(id)
        assertNull(JellyfinAccountStore { prefs }.token(id))
    }
}
