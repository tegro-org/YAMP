package org.tegro.yamp.presentation.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.presentation.queues.QueueRowState
import org.tegro.yamp.presentation.queues.QueueSwitcherUiState

/** Account markers and the switcher's account rules (docs/phase-2a-accounts.md D58, D62, D64). */
class AccountTagsTest {

    private val anna = UserAccount(uid = "1001", login = "anna", displayName = "Анна", token = "t")
    private val rock = UserAccount(uid = "2002", login = "rock", token = "t")

    private fun row(id: Long, account: String?, active: Boolean = false) =
        QueueRowState(id, "Q$id", QueueKind.STATIC, null, null, null, 0f, null, 0, isActive = active, account = account)

    @Test
    fun aTagCarriesTheKeyTheNameAndTheInitial() {
        val tags = accountTags(listOf(anna, rock))
        assertEquals(listOf("yandex:1001", "yandex:2002"), tags.map { it.key })
        assertEquals("Анна", tags[0].name)
        assertEquals("А", tags[0].initial)
        assertEquals("rock", tags[1].name)
        assertEquals("R", tags[1].initial)
    }

    @Test
    fun accountsGetDifferentColoursThatDoNotDependOnTheirOrder() {
        val many = (1..6).map { UserAccount(uid = "$it", login = "u$it", token = "t") }
        val tags = accountTags(many)
        assertEquals("six accounts, six colours", 6, tags.map { it.color }.toSet().size)
        assertEquals(tags.associate { it.key to it.color }, accountTags(many.reversed()).associate { it.key to it.color })
    }

    @Test
    fun oneAccountIsNotMultiAccount() {
        val state = QueueSwitcherUiState(listOf(row(1, "yandex:1001", active = true)), 1, null, accountTags(listOf(anna)))
        assertFalse(state.multiAccount)
        assertEquals("yandex:1001", state.contextAccount)
    }

    @Test
    fun theSelectorDefaultsToTheActiveQueuesAccount() {
        val state = QueueSwitcherUiState(
            listOf(row(1, "yandex:1001"), row(2, "yandex:2002", active = true)), 2, null, accountTags(listOf(anna, rock))
        )
        assertTrue(state.multiAccount)
        assertEquals("yandex:2002", state.contextAccount)
    }

    @Test
    fun aQueueOfASignedOutAccountIsRemovedAndANeutralOneIsNot() {
        val state = QueueSwitcherUiState(listOf(row(1, "yandex:9999"), row(2, null)), null, null, accountTags(listOf(anna, rock)))
        assertTrue(state.isRemoved(state.queues[0]))
        assertFalse(state.isRemoved(state.queues[1]))
        assertNull(state.tagOf("yandex:9999"))
        assertEquals("with no active queue, the first account", "yandex:1001", state.contextAccount)
    }
}
