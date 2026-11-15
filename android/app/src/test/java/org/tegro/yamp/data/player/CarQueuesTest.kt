package org.tegro.yamp.data.player

import org.junit.Assert.assertEquals
import org.junit.Test
import org.tegro.yamp.domain.queue.PlayQueue
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.queue.QueueSummary

/** Phase 2a step 7 (D62): Android Auto never lists nor plays a removed account's queue. */
class CarQueuesTest {
    private fun summary(id: Long, account: String?) =
        QueueSummary(PlayQueue(id = id, name = "Q$id", kind = QueueKind.STATIC, account = account), current = null, size = 0, isActive = false)

    @Test
    fun aRemovedAccountsQueuesAreLeftOut() {
        val queues = listOf(summary(1, "yandex:1001"), summary(2, "yandex:3003"), summary(3, null), summary(4, "yandex:3003"))

        assertEquals(listOf(1L, 3L), carQueues(queues, signedIn = setOf("yandex:1001")).map { it.queue.id })
    }
}
