package org.tegro.yamp.data.account

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.repository.AuthRepositoryImpl
import org.tegro.yamp.domain.model.UserAccount

/**
 * A second account signed in (phone test, 2026-11-15): its My Wave queue never appeared (D63). The sign-in screen only
 * saves the token; "authorized" was already true, so nothing ran the account status refresh that names the account
 * and creates its queue. [freshSignIns] is what the session now watches for that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FreshSignInTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val anna = UserAccount(uid = "1001", login = "anna", token = "token-anna")

    @Test
    fun addingASecondAccountIsSeenOnceWhileAuthorizedDoesNotChange() = runTest(UnconfinedTestDispatcher()) {
        val auth = AuthRepositoryImpl(context)
        auth.saveOrUpdateAccount(anna)

        val authorized = mutableListOf<Boolean>()
        val fresh = mutableListOf<String>()
        auth.isAuthorized().onEach { authorized += it }.launchIn(backgroundScope)
        freshSignIns(auth.activeAccount).onEach { fresh += it }.launchIn(backgroundScope)
        assertEquals("a signed-in account with a real uid is not a fresh sign-in", emptyList<String>(), fresh)

        // What the sign-in screen does for the second account
        auth.saveToken("token-rock")
        val provisional = auth.getActiveAccount()!!.uid
        assertTrue(provisional.startsWith("temp_"))
        assertEquals("the old trigger: authorized stays true and emits nothing new", listOf(true), authorized)
        assertEquals("the sign-in is seen", listOf(provisional), fresh)

        // The active account follows queues back and forth, then the server names the new account
        auth.switchAccount(anna.uid)
        auth.switchAccount(provisional)
        auth.saveOrUpdateAccount(UserAccount(uid = "2002", login = "rock", token = "token-rock"))
        auth.switchAccount(anna.uid)
        assertEquals("seen once", listOf(provisional), fresh)
        assertEquals("the provisional account gave way", listOf("1001", "2002"), auth.getAllAccounts().map { it.uid }.sorted())
    }

    @Test
    fun aStartWithSeveralNamedAccountsRefreshesEachOnce() {
        val gate = StatusRefreshGate()
        val accounts = listOf(anna, UserAccount(uid = "2002", token = "b"), UserAccount(uid = "3003", token = "c"))
        val refreshed = mutableListOf<String>()
        // Startup, the profile check, the account entered and a re-entry all ask for every account
        repeat(4) {
            accounts.forEach { account ->
                if (gate.shouldRefresh(account)) {
                    refreshed += account.uid
                    gate.done(account.uid)
                }
            }
        }
        assertEquals(listOf("1001", "2002", "3003"), refreshed)

        val provisional = UserAccount(uid = "temp_1", token = "d")
        assertTrue("a fresh sign-in is always refreshed", gate.shouldRefresh(provisional) && gate.shouldRefresh(provisional))
        gate.forget("2002")
        assertTrue("signed in again after a sign-out: refreshed again", gate.shouldRefresh(accounts[1]))
    }
}
