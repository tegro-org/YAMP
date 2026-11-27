package org.tegro.yamp.data.account

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.dto.AccountStatusResponseDto
import org.tegro.yamp.data.repository.AuthRepositoryImpl
import org.tegro.yamp.domain.model.UserAccount

/**
 * Plus from the fresher answer only: an answer that says nothing about it (or a save for a new token) keeps what the
 * server said, the later answer wins, and an account never asked about is not "missing Plus" — a paying subscriber is
 * not locked out by a failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlusStatusTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun anAnswerWithoutPlusSaysNothingAboutIt() {
        val status = AccountStatusResponseDto()
        assertFalse(status.plusKnown)
        assertFalse(status.hasPlus)
    }

    @Test
    fun aSaveThatKnowsNothingAboutPlusKeepsWhatTheServerSaid() {
        val auth = AuthRepositoryImpl(context)
        auth.saveOrUpdateAccount(UserAccount(uid = "1001", login = "anna", token = "token", hasPlus = true, plusCheckedAt = 5_000L))
        // A token save that omits Plus: the account it updates has no word about Plus of its own
        auth.saveOrUpdateAccount(UserAccount(uid = "1001", login = "anna", token = "token", hasPlus = false, plusCheckedAt = 0L))

        val kept = auth.getAllAccounts().single()
        assertTrue("a save that knows nothing does not take back a Plus", kept.hasPlus)
        assertEquals(5_000L, kept.plusCheckedAt)
    }

    @Test
    fun theFresherWordAboutPlusWins() {
        val auth = AuthRepositoryImpl(context)
        auth.saveOrUpdateAccount(UserAccount(uid = "2002", login = "rock", token = "token", hasPlus = true, plusCheckedAt = 5_000L))
        // A stale answer (asked before the one saved) does not undo it
        auth.saveOrUpdateAccount(UserAccount(uid = "2002", login = "rock", token = "token", hasPlus = false, plusCheckedAt = 4_000L))
        assertTrue(auth.getAllAccounts().single().hasPlus)

        // The server asked again and said no: an expired Plus clears
        auth.saveOrUpdateAccount(UserAccount(uid = "2002", login = "rock", token = "token", hasPlus = false, plusCheckedAt = 6_000L))
        val expired = auth.getAllAccounts().single()
        assertFalse(expired.hasPlus)
        assertEquals(6_000L, expired.plusCheckedAt)
        assertTrue(expired.plusMissing)
    }

    @Test
    fun anAnswerForAnotherAccountDoesNotMakeItTheActiveOne() {
        val auth = AuthRepositoryImpl(context)
        auth.saveOrUpdateAccount(UserAccount(uid = "2002", login = "rock", token = "t2"))
        auth.saveOrUpdateAccount(UserAccount(uid = "1001", login = "anna", token = "t1", hasPlus = true, plusCheckedAt = 5_000L))
        auth.updatePlus("2002", hasPlus = false, checkedAt = 6_000L)
        auth.updatePlus("1001", hasPlus = false, checkedAt = 4_000L)

        assertEquals("1001", auth.getActiveAccount()?.uid)
        assertTrue(auth.getAllAccounts().first { it.uid == "2002" }.plusMissing)
        assertTrue("an older answer is ignored", auth.getAllAccounts().first { it.uid == "1001" }.hasPlus)
    }

    @Test
    fun theGateRefusesOnlyAClearNoPlusAndTheDeveloperOptionLiftsIt() {
        val account = UserAccount(uid = "4004", token = "token")
        // blocked = a clear "no Plus" and the developer option off
        assertFalse("never asked", account.blocked(allowWithoutPlus = false))
        assertFalse("has Plus", account.copy(hasPlus = true, plusCheckedAt = 1L).blocked(allowWithoutPlus = false))
        assertTrue("no Plus", account.copy(plusCheckedAt = 1L).blocked(allowWithoutPlus = false))
        assertFalse("no Plus, developer option on", account.copy(plusCheckedAt = 1L).blocked(allowWithoutPlus = true))
    }

    @Test
    fun neverCheckedIsNotMissing() {
        val account = UserAccount(uid = "3003", token = "token")
        assertFalse("no answer about Plus yet: nothing is missing", account.plusMissing)
        assertTrue(account.copy(plusCheckedAt = 1L).plusMissing)
        assertFalse(account.copy(hasPlus = true, plusCheckedAt = 1L).plusMissing)
    }
}
