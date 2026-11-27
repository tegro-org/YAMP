package org.tegro.yamp.data.account

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.dto.AccountStatusResponseDto
import org.tegro.yamp.data.repository.AuthRepositoryImpl
import org.tegro.yamp.domain.model.UserAccount

/** A late account-status answer never switches the account back or brings a signed-out one back; only a clear Plus answer counts (D101). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AccountStatusWriterTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun dto(body: String) = json.decodeFromString<AccountStatusResponseDto>(body)

    private fun status(body: String): StatusAnswer = dto(body).let {
        StatusAnswer(it.uidString, it.account?.login, it.displayUserName, if (it.plusKnown) it.hasPlus else null)
    }

    private val anna = UserAccount(uid = "1001", login = "anna", token = "t1", hasPlus = true, plusCheckedAt = 5_000L)
    private val rock = UserAccount(uid = "2002", login = "rock", token = "t2")

    private fun twoAccounts(): AuthRepositoryImpl = AuthRepositoryImpl(context).apply {
        saveOrUpdateAccount(anna)
        saveOrUpdateAccount(rock)
    }

    @Test
    fun anAnswerThatArrivesAfterASwitchDoesNotSwitchBack() {
        val auth = twoAccounts()
        auth.switchAccount("1001")
        // The request for Anna was in flight; the user switched to Rock before it came back
        auth.switchAccount("2002")

        val written = AccountStatusWriter.apply(auth, anna, status("""{"plus":{"hasPlus":false}}"""), avatar = null, nowMs = 9_000L)

        assertEquals("1001", written)
        assertEquals("the active account stays Rock", "2002", auth.getActiveAccount()?.uid)
        assertTrue("the answer itself is kept", auth.getAllAccounts().first { it.uid == "1001" }.plusMissing)
    }

    @Test
    fun anAnswerForAnAccountSignedOutMeanwhileIsDropped() {
        val auth = twoAccounts()
        auth.removeAccount("1001")

        assertNull(AccountStatusWriter.apply(auth, anna, status("""{"plus":{"hasPlus":true}}"""), avatar = null, nowMs = 9_000L))
        assertTrue("not brought back", auth.getAllAccounts().none { it.uid == "1001" })
        assertEquals("2002", auth.getActiveAccount()?.uid)
    }

    @Test
    fun aFreshSignInIsSavedAsTheActiveAccountUnderItsRealUid() {
        val auth = AuthRepositoryImpl(context)
        val provisional = UserAccount(uid = "temp_1", token = "t9")
        auth.saveOrUpdateAccount(provisional)

        val written = AccountStatusWriter.apply(
            auth, provisional, status("""{"account":{"uid":3003,"login":"new"},"plus":{"hasPlus":false}}"""), avatar = null, nowMs = 9_000L
        )

        assertEquals("3003", written)
        assertEquals("3003", auth.getActiveAccount()?.uid)
        assertTrue(auth.getActiveAccount()!!.plusMissing)
    }

    @Test
    fun aPartialOrEmptyPlusAnswerKeepsWhatWasKnown() {
        val auth = twoAccounts()
        for (body in listOf("""{}""", """{"plus":{}}""", """{"plus":{"isTutorialCompleted":true}}""")) {
            val answer = status(body)
            assertFalse("$body says nothing about Plus", dto(body).plusKnown)
            AccountStatusWriter.apply(auth, anna, answer, avatar = null, nowMs = 9_000L)
            val kept = auth.getAllAccounts().first { it.uid == "1001" }
            assertTrue("$body keeps the subscriber's Plus", kept.hasPlus)
            assertEquals(5_000L, kept.plusCheckedAt)
            assertFalse(kept.plusMissing)
        }
        assertTrue(dto("""{"plus":{"hasPlus":false}}""").plusKnown)
        assertFalse(dto("""{"plus":{"hasPlus":false}}""").hasPlus)
        assertTrue(dto("""{"plus":{"hasPlus":true}}""").hasPlus)
    }

    @Test
    fun plusIsAskedAtMostOncePerGap() {
        val throttle = PlusAskThrottle(60_000L)
        assertTrue("the first time", throttle.due(1_000L))
        throttle.asked(1_000L)
        assertFalse(throttle.due(30_000L))
        assertTrue(throttle.due(61_000L))
    }
}
