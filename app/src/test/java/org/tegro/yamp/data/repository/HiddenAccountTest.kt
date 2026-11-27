package org.tegro.yamp.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.domain.model.UserAccount

/**
 * Debug "Hide (test sign-out)": the sign-out runs with `keepCredentials`, which stashes the account record instead of
 * losing it — the token survives a restart and "Unhide" signs the same uid in again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HiddenAccountTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val rock = UserAccount(uid = "2002", login = "rock", token = "token-rock")
    private val anna = UserAccount(uid = "1001", login = "anna", token = "token-anna")

    @Test
    fun aHiddenAccountKeepsItsTokenAcrossARestartAndComesBackOnUnhide() {
        val auth = AuthRepositoryImpl(context)
        auth.saveOrUpdateAccount(anna)
        auth.saveOrUpdateAccount(rock)

        // What the sign-out does with keepCredentials = true
        auth.stashHiddenAccount(rock)
        auth.removeAccount(rock.uid)
        assertTrue("signed out like a real sign-out", auth.getAllAccounts().none { it.uid == rock.uid })

        val restarted = AuthRepositoryImpl(context)
        assertEquals("the token is kept", "token-rock", restarted.hiddenAccounts.value.single().token)
        assertTrue(restarted.getAllAccounts().none { it.uid == rock.uid })

        val back = restarted.takeHiddenAccount(rock.uid)!!
        restarted.saveOrUpdateAccount(back)
        assertEquals("token-rock", restarted.getAllAccounts().single { it.uid == rock.uid }.token)
        assertTrue("taken out of the stash", restarted.hiddenAccounts.value.isEmpty())
        assertNull(restarted.takeHiddenAccount(rock.uid))
    }
}
