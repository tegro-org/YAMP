package org.tegro.yamp.data.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R15: the music on launch plays once for each opening of the app, and a start is fresh for the player only briefly. */
class PlayRequestsTest {

    @Test
    fun theLaunchStartIsClaimedOnceForEachOpening() {
        val requests = PlayRequests()

        assertTrue("the first opening starts the music", requests.claimLaunchStart(1L))
        assertFalse("the same opening again (a screen reopened) does not", requests.claimLaunchStart(1L))
    }

    @Test
    fun anAppOpenedAgainStartsTheMusicAgain() {
        val requests = PlayRequests()
        assertTrue(requests.claimLaunchStart(1L))

        // Back and open again while the process lives on (the music's service keeps it): a new opening
        assertTrue(requests.claimLaunchStart(2L))
    }

    @Test
    fun aStartIsFreshOnlyForTenSeconds() {
        val start = PlayRequests.Start(id = 1, atMs = 1_000_000L)

        assertTrue(PlayRequests.isFresh(start, 1_000_000L))
        assertTrue(PlayRequests.isFresh(start, 1_000_000L + PlayRequests.FRESH_MS))
        assertFalse(PlayRequests.isFresh(start, 1_000_000L + PlayRequests.FRESH_MS + 1))
    }

    @Test
    fun aStartFromTheFutureIsNotFresh() {
        // The clock went back: an opening's start is never "fresh" for another one yet to come
        val start = PlayRequests.Start(id = 1, atMs = 2_000L)
        assertFalse(PlayRequests.isFresh(start, 1_000L))
    }
}
