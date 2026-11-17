package org.tegro.yamp.domain.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider

class QueueFitTest {
    private val yandexA = AccountId.yandex("1")
    private val yandexB = AccountId.yandex("2")
    private val home = AccountId.jellyfin("srv1", "u1")
    private val office = AccountId.jellyfin("srv2", "u1")
    private val yandexTrack = TrackRef.yandex("42")
    private val homeTrack = TrackRef.of("jellyfin@srv1", "item1")

    @Test
    fun `a Yandex track fits every Yandex account's queue and no Jellyfin one`() {
        assertTrue(yandexTrack.fitsQueueOf(yandexA))
        assertTrue(yandexTrack.fitsQueueOf(yandexB))
        assertTrue("a queue from before accounts is Yandex", yandexTrack.fitsQueueOf(null))
        assertFalse(yandexTrack.fitsQueueOf(home))
    }

    @Test
    fun `a Jellyfin track fits only the queues of an account on its own server`() {
        assertTrue(homeTrack.fitsQueueOf(home))
        assertFalse(homeTrack.fitsQueueOf(office))
        assertFalse(homeTrack.fitsQueueOf(yandexA))
        assertFalse(homeTrack.fitsQueueOf(null))
    }

    @Test
    fun `the service names the badge`() {
        assertEquals(Provider.YANDEX, yandexTrack.service)
        assertEquals(Provider.JELLYFIN, homeTrack.service)
        assertNull(TrackRef.of(MediaRef.PROVIDER_RADIO, "fm").service)
    }
}
