package org.tegro.yamp.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.model.UserAccount

/** Phase 2a step 7 (D62): where playback goes on a sign-out, and which downloads leave with the account. */
class SignOutPlanTest {
    private val anna = UserAccount(uid = "1001", token = "t1", lastActiveTimestamp = 10L)
    private val rock = UserAccount(uid = "2002", token = "t2", lastActiveTimestamp = 30L)
    private val jazz = UserAccount(uid = "3003", token = "t3", lastActiveTimestamp = 20L)

    @Test
    fun theDefaultAccountTakesOverWhenItStays() {
        assertEquals(AccountId.yandex("1001"), SignOutPlan.fallback(listOf(anna, rock, jazz), defaultUid = "1001"))
    }

    @Test
    fun withoutADefaultTheMostRecentlyUsedAccountTakesOver() {
        assertEquals(AccountId.yandex("2002"), SignOutPlan.fallback(listOf(anna, rock, jazz), defaultUid = null))
        // The default is the one leaving
        assertEquals(AccountId.yandex("2002"), SignOutPlan.fallback(listOf(anna, rock), defaultUid = "3003"))
    }

    @Test
    fun theLastAccountLeavingLeavesNoQueueActive() {
        assertNull(SignOutPlan.fallback(emptyList(), defaultUid = "1001"))
    }

    @Test
    fun aDownloadStaysWhileAnotherAccountLikesIt() {
        assertEquals(
            listOf("1", "3"),
            SignOutPlan.downloadsToRemove(leavingLikes = listOf("1", "2", "3", "1"), stayingLikes = setOf("2", "9"))
        )
    }
}
