package org.tegro.yamp.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.domain.model.UserAccount

/** D64: the account groups keep their place — the default account first, then the order the accounts were added. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AccountOrderTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val anna = UserAccount(uid = "1", login = "anna", token = "a")
    private val boris = UserAccount(uid = "2", login = "boris", token = "b")
    private val clara = UserAccount(uid = "3", login = "clara", token = "c")

    private fun AuthRepositoryImpl.order() = allAccounts.value.map { it.login }

    @Test
    fun usingAnAccountNeverMovesIt() {
        val auth = AuthRepositoryImpl(context)
        listOf(anna, boris, clara).forEach { auth.saveOrUpdateAccount(it) }

        auth.switchAccount(clara.uid)
        auth.saveOrUpdateAccount(boris.copy(displayName = "Boris"))

        assertEquals(listOf("anna", "boris", "clara"), auth.order())
        assertEquals("after a restart too", listOf("anna", "boris", "clara"), AuthRepositoryImpl(context).order())
    }

    @Test
    fun theDefaultAccountComesFirstAndTheRestKeepTheirOrder() {
        val auth = AuthRepositoryImpl(context)
        listOf(anna, boris, clara).forEach { auth.saveOrUpdateAccount(it) }

        auth.setDefaultAccountUid(boris.uid)
        assertEquals(listOf("boris", "anna", "clara"), auth.order())

        auth.removeAccount(anna.uid)
        assertEquals(listOf("boris", "clara"), auth.order())
        assertEquals(listOf("boris", "clara"), AuthRepositoryImpl(context).order())
    }
}
