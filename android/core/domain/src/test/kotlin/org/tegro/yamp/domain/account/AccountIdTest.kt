package org.tegro.yamp.domain.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccountIdTest {

    @Test
    fun aJellyfinAccountIsServerPlusUser() {
        val id = AccountId.jellyfin("srv1", "user1")
        assertEquals("jellyfin:srv1/user1", id.value)
        assertEquals(Provider.JELLYFIN, id.provider)
        assertEquals("srv1/user1", id.id)
    }

    @Test
    fun theSameUserOnTwoServersIsTwoAccounts() {
        assertNotEquals(AccountId.jellyfin("srvA", "user1"), AccountId.jellyfin("srvB", "user1"))
    }

    @Test
    fun yandexAccountsAreUnchanged() {
        assertEquals(Provider.YANDEX, AccountId.yandex("42").provider)
        assertEquals("42", AccountId.yandex("42").id)
    }
}
