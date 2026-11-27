package org.tegro.yamp.data.account

import org.junit.Assert.assertEquals
import org.junit.Test
import org.tegro.yamp.domain.model.UserAccount

/**
 * The Settings profile shows the context account (D56): the active queue's account, not whichever account the store
 * last made active — an "Unhide" or a re-sign-in sets that one while another account's queue is playing.
 */
class ContextUserTest {
    private val anna = UserAccount(uid = "1001", login = "anna", token = "a")
    private val rock = UserAccount(uid = "2002", login = "rock", token = "b")

    @Test
    fun theActiveQueuesAccountWinsOverTheStoresActiveOne() {
        // Rock was just unhidden (the store's active account) while Anna's queue is the active one
        assertEquals(anna, contextUser("yandex:1001", listOf(anna, rock), active = rock))
    }

    @Test
    fun withoutAQueueOrWithASignedOutOnesAccountItIsTheActiveAccount() {
        assertEquals(rock, contextUser(null, listOf(anna, rock), active = rock))
        assertEquals("a removed account's queue gives no context", rock, contextUser("yandex:3003", listOf(anna, rock), active = rock))
    }
}
