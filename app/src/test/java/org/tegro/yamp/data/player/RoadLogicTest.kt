package org.tegro.yamp.data.player

import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.network.TrackPreloadManager.StreamQuality
import org.tegro.yamp.data.repository.PendingReaction
import org.tegro.yamp.data.repository.PendingReactions
import org.tegro.yamp.data.provider.yandex.reserve.ReserveEntry
import org.tegro.yamp.data.provider.yandex.reserve.ReservePolicy
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The logic of music on the road: how the link is judged, where a track comes from, what the reserve keeps. */
class RoadLogicTest {

    private var clock = 1_000L
    private val link = LinkTracker { clock }

    // --- The state of the link ------------------------------------------------------------------------

    @Test
    fun theLinkIsGoodUntilSomethingHappens() {
        assertEquals(LinkState.GOOD, link.state)
        assertTrue(link.mayTryStream())
    }

    @Test
    fun noNetworkIsNoConnection() {
        link.onNetwork(false)
        assertEquals(LinkState.NONE, link.state)
        assertFalse(link.mayTryStream())
    }

    @Test
    fun aStallWithTheServerInReachIsAWeakLink() {
        link.onTrouble(reachable = true)
        assertEquals(LinkState.WEAK, link.state)
    }

    @Test
    fun aNetworkThatCarriesNoDataIsNoConnection() {
        // A tunnel: Android still reports a network, the server does not answer
        link.onNetwork(true)
        link.onTrouble(reachable = false)
        assertEquals(LinkState.NONE, link.state)
    }

    @Test
    fun theServerAnsweringAgainEndsNoConnection() {
        link.onTrouble(reachable = false)
        link.onServerReachable()
        assertEquals("the next track tries the stream at once", LinkState.GOOD, link.state)
        assertTrue(link.mayTryStream())
    }

    @Test
    fun aReturningNetworkStartsClean() {
        link.onTrouble(reachable = true)
        link.onNetwork(false)
        link.onNetwork(true)
        assertEquals(LinkState.GOOD, link.state)
    }

    @Test
    fun theSameNetworkReportedAgainDoesNotEndAWeakLink() {
        link.onNetwork(true)
        link.onTrouble(reachable = true)
        link.onNetwork(true)
        assertEquals(LinkState.WEAK, link.state)
    }

    @Test
    fun aWeakLinkRetriesTheStreamEveryFiveMinutesAlways() {
        link.onTrouble(reachable = true)
        clock += LinkTracker.WEAK_RETRY_MS - 1
        assertFalse(link.mayTryStream())
        clock += 1
        assertTrue("five minutes passed", link.mayTryStream())

        // The try failed: again five minutes, not more
        link.onTrouble(reachable = true)
        assertFalse(link.mayTryStream())
        clock += LinkTracker.WEAK_RETRY_MS
        assertTrue(link.mayTryStream())
    }

    @Test
    fun aStreamThatPlaysEndsTheWeakLink() {
        link.onTrouble(reachable = true)
        link.onStreamAudible()
        assertEquals(LinkState.GOOD, link.state)
    }

    // --- Stops mid-track: only the long ones are the link --------------------------------------------------

    @Test
    fun aTenthOfASecondRefillIsNoStop() {
        link.onStall()
        clock += 100
        assertEquals("how long it stood is still told", 100L, link.onStallOver())
        assertEquals(0, link.stallsInWindow)
        assertFalse("a refill after a pause is not the link", link.unevenByLastStop)
    }

    @Test
    fun halfASecondCountsAndTwoOfThemAreUneven() {
        link.onStall()
        clock += LinkTracker.COUNTED_STALL_MS
        assertEquals(LinkTracker.COUNTED_STALL_MS, link.onStallOver())
        assertEquals(1, link.stallsInWindow)
        assertFalse("one stop is not an uneven link", link.unevenByLastStop)

        link.onStall()
        clock += 700
        link.onStallOver()
        assertEquals(2, link.stallsInWindow)
        assertTrue("two counted stops within two minutes", link.unevenByLastStop)
    }

    @Test
    fun aCountedStopOutsideTheWindowDoesNotCount() {
        link.onStall()
        clock += 600
        link.onStallOver()
        clock += LinkTracker.STALL_WINDOW_MS
        link.onStall()
        clock += 600
        link.onStallOver()
        assertEquals("the first one is gone", 1, link.stallsInWindow)
        assertFalse(link.unevenByLastStop)
    }

    @Test
    fun aLongStopCountsWhileItStillRuns() {
        link.onStall()
        clock += LinkTracker.LONG_STALL_MS - 1
        assertFalse("not yet", link.onStallLasted())
        clock += 1
        assertTrue("a stop of 1.5 s is uneven on its own", link.onStallLasted())
        clock += 100
        assertEquals("still standing", 1_600L, link.onStallOver())
        assertEquals("it counts once, not twice", 1, link.stallsInWindow)
    }

    @Test
    fun aStopOfATrackAlreadyGoneIsForgotten() {
        link.onStall()
        link.onStallForgotten()
        clock += 5_000
        assertNull(link.onStallOver())
        assertEquals(0, link.stallsInWindow)
    }

    // --- Streams blocked -----------------------------------------------------------------------------------

    @Test
    fun aServiceThatAnswersWithSilentStreamsIsNoConnectionNotAWeakLink() {
        link.onStreamsBlocked()
        assertEquals("the music comes from the device, not a light stream", LinkState.NONE, link.state)
        assertFalse(link.mayTryStream())
        assertEquals(LinkReason.STREAMS_BLOCKED, link.lastReason)
    }

    @Test
    fun theServiceAnsweringAgainEndsBlockedStreams() {
        link.onStreamsBlocked()
        link.onServerReachable()
        assertEquals(LinkState.GOOD, link.state)
        assertEquals(LinkReason.SERVER_ANSWERED, link.lastReason)
    }

    @Test
    fun aStreamThatSoundsEndsBlockedStreams() {
        link.onStreamsBlocked()
        link.onStreamAudible()
        assertEquals(LinkState.GOOD, link.state)
        assertEquals(LinkReason.STREAM_SOUNDS, link.lastReason)
    }

    @Test
    fun troubleWithTheServerSilentRecordsWhy() {
        link.onTrouble(reachable = false, reason = LinkReason.REQUEST_FAILED)
        assertEquals("the service itself did not answer", LinkReason.SERVER_SILENT, link.lastReason)
        link.onTrouble(reachable = true, reason = LinkReason.REQUEST_FAILED)
        assertEquals(LinkReason.REQUEST_FAILED, link.lastReason)
    }

    // --- Where a track comes from ---------------------------------------------------------------------

    @Test
    fun aGoodLinkAlwaysAsksForTheStream() {
        assertEquals(TrackOrigin.STREAM, RoadPlan.originFor(LinkState.GOOD, mayTryStream = true, hasCopy = true))
        assertEquals(TrackOrigin.STREAM, RoadPlan.originFor(LinkState.GOOD, mayTryStream = true, hasCopy = false))
    }

    @Test
    fun aWeakLinkPlaysCopiesUntilTheRetry() {
        assertEquals(TrackOrigin.COPY, RoadPlan.originFor(LinkState.WEAK, mayTryStream = false, hasCopy = true))
        assertEquals("the check goes through the ordinary request", TrackOrigin.STREAM, RoadPlan.originFor(LinkState.WEAK, mayTryStream = true, hasCopy = true))
        assertEquals("no copy: the stream is all there is", TrackOrigin.STREAM, RoadPlan.originFor(LinkState.WEAK, mayTryStream = false, hasCopy = false))
    }

    @Test
    fun noConnectionPlaysCopiesOrMovesOn() {
        assertEquals(TrackOrigin.COPY, RoadPlan.originFor(LinkState.NONE, mayTryStream = false, hasCopy = true))
        assertEquals(TrackOrigin.UNAVAILABLE, RoadPlan.originFor(LinkState.NONE, mayTryStream = false, hasCopy = false))
    }

    @Test
    fun aBetterStreamBeatsTheCopyAnEqualOneDoesNot() {
        val copy = StreamQuality("mp3", 192)
        assertFalse("better bitrate", RoadPlan.copyIsEnough(StreamQuality("mp3", 320), copy))
        assertFalse("lossless", RoadPlan.copyIsEnough(StreamQuality("flac", 1000), copy))
        assertTrue("the same", RoadPlan.copyIsEnough(StreamQuality("mp3", 192), copy))
        assertTrue("worse", RoadPlan.copyIsEnough(StreamQuality("mp3", 128), copy))
        assertTrue("unknown", RoadPlan.copyIsEnough(null, copy))
    }

    // --- What to do when a stream fails ---------------------------------------------------------------

    private fun recovery(
        offline: Boolean = false,
        retried: Boolean = false,
        hasCopy: Boolean = false,
        weak: Boolean = false,
        radio: Boolean = false,
        downloaded: Boolean = false,
        slowness: Boolean = false,
        fromDownload: Boolean = false,
        network: Boolean = false,
        device: Boolean = false,
        unavailable: Boolean = false,
        failedInARow: Int = 1
    ) = PlaybackQueue.recoveryFor(offline, radio, downloaded, retried, failedInARow, hasCopy, weak, slowness, fromDownload, network, device, unavailable)

    /** A track the provider says is gone is skipped at once: no retry, no device mix on a weak link, no pause on it. */
    @Test
    fun aTrackThatIsGoneIsSkippedWhateverTheLinkIs() {
        assertEquals(Recovery.SKIP, recovery(unavailable = true, retried = true, weak = true))
        assertEquals(Recovery.SKIP, recovery(unavailable = true, retried = true, device = true))
        assertEquals("its copy plays", Recovery.RESUME_FROM_COPY, recovery(unavailable = true, retried = true, hasCopy = true))
        assertEquals("not for ever", Recovery.STOP, recovery(unavailable = true, retried = true, failedInARow = PlaybackQueue.MAX_FAILED_TRACKS))
    }

    /** A stream that did not come goes to its copy, else to the device's music, at once — no retry, no skip, no stop. */
    @Test
    fun aStreamThatDidNotComeGoesToTheCopyThenToTheDevice() {
        assertEquals(Recovery.RESUME_FROM_COPY, recovery(network = true, hasCopy = true, device = true))
        assertEquals(Recovery.CONTINUE_OFFLINE, recovery(network = true, device = true))
        assertEquals("nothing on the device: the usual ladder", Recovery.RETRY, recovery(network = true))
        assertEquals("a station has no device music", Recovery.RETRY, recovery(network = true, radio = true, device = true))
        assertEquals("a downloaded track is not a stream", Recovery.RETRY, recovery(network = true, downloaded = true, device = true))
        assertEquals("a slow download still pauses (D91)", Recovery.PAUSE, recovery(network = true, device = true, slowness = true, fromDownload = true))
    }

    @Test
    fun onlyTheLinksOwnErrorsCountAsANetworkFailure() {
        assertTrue(PlaybackQueue.isNetworkFailure("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
        assertTrue(PlaybackQueue.isNetworkFailure("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"))
        assertTrue(PlaybackQueue.isNetworkFailure("ERROR_CODE_IO_UNSPECIFIED"))
        assertTrue(PlaybackQueue.isNetworkFailure("BUFFERING_TIMEOUT"))
        assertFalse("the output stood still (R4)", PlaybackQueue.isNetworkFailure("ERROR_CODE_TIMEOUT"))
        assertFalse(PlaybackQueue.isNetworkFailure("TRACK_UNAVAILABLE"))
        assertFalse(PlaybackQueue.isNetworkFailure("ERROR_CODE_IO_BAD_HTTP_STATUS"))
    }

    /** D91 + D67: a converted stream's download restarted already — slowness pauses at once, a refusal still retries. */
    @Test
    fun aSlowDownloadPausesWithoutARetry() {
        assertEquals(Recovery.PAUSE, recovery(slowness = true, fromDownload = true))
        assertEquals("a refused download: a fresh link first", Recovery.RETRY, recovery(fromDownload = true))
        assertEquals("then skip", Recovery.SKIP, recovery(retried = true, fromDownload = true))
        assertEquals("no link at all: offline rules first", Recovery.CONTINUE_OFFLINE, recovery(offline = true, slowness = true, fromDownload = true))
    }

    @Test
    fun aSlowTrackPausesAfterItsRetryABrokenOneSkips() {
        assertEquals("first a fresh link", Recovery.RETRY, recovery(slowness = true))
        assertEquals("slowness never skips (D67)", Recovery.PAUSE, recovery(retried = true, slowness = true))
        assertEquals(Recovery.PAUSE, recovery(retried = true, slowness = true, weak = true))
        assertEquals("a broken track still skips", Recovery.SKIP, recovery(retried = true))
        assertTrue(PlaybackQueue.isSlowness("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"))
        assertFalse(PlaybackQueue.isSlowness("ERROR_CODE_IO_BAD_HTTP_STATUS"))
    }

    @Test
    fun noConnectionGoesOnFromTheCopyOfTheSameTrack() {
        assertEquals(Recovery.RESUME_FROM_COPY, recovery(offline = true, hasCopy = true))
        assertEquals(Recovery.CONTINUE_OFFLINE, recovery(offline = true))
    }

    @Test
    fun anExpiredLinkGetsAFreshOneBeforeTheCopy() {
        assertEquals(Recovery.RETRY, recovery(hasCopy = true))
        assertEquals(Recovery.RESUME_FROM_COPY, recovery(retried = true, hasCopy = true))
    }

    @Test
    fun aWeakLinkDoesNotCountAsABrokenTrack() {
        assertEquals(Recovery.CONTINUE_OFFLINE, recovery(retried = true, weak = true))
        assertEquals("a good link: the track is to blame", Recovery.SKIP, recovery(retried = true))
    }

    @Test
    fun aStationIsNeverReplacedByTheReserve() {
        assertEquals(Recovery.STOP, recovery(retried = true, hasCopy = true, weak = true, radio = true))
    }

    // --- The reserve ----------------------------------------------------------------------------------

    private fun entry(id: String, size: Long = 5, added: Long = 0, played: Long? = null) = ReserveEntry(
        track = TrackDetailsDto(id = JsonPrimitive(id)),
        sizeBytes = size,
        codec = "mp3",
        bitrateKbps = 192,
        addedAtMs = added,
        playedAtMs = played
    )

    @Test
    fun nothingLeavesWhileThereIsRoom() {
        assertEquals(emptyList<String>(), ReservePolicy.toEvict(listOf(entry("a")), limitBytes = 10, incomingBytes = 5, keep = emptySet()))
    }

    @Test
    fun whatWasPlayedLongAgoLeavesFirst() {
        val entries = listOf(entry("new", played = null, added = 1), entry("late", played = 200), entry("early", played = 100))
        assertEquals(listOf("early"), ReservePolicy.toEvict(entries, limitBytes = 15, incomingBytes = 5, keep = emptySet()))
        assertEquals(listOf("early", "late"), ReservePolicy.toEvict(entries, limitBytes = 10, incomingBytes = 5, keep = emptySet()))
        assertEquals("then what never played", listOf("early", "late", "new"), ReservePolicy.toEvict(entries, limitBytes = 5, incomingBytes = 5, keep = emptySet()))
    }

    @Test
    fun whatIsAboutToPlayStays() {
        val entries = listOf(entry("next"), entry("after"))
        assertNull("full of what comes next: no room", ReservePolicy.toEvict(entries, limitBytes = 10, incomingBytes = 5, keep = setOf("next", "after")))
        assertEquals(listOf("after"), ReservePolicy.toEvict(entries, limitBytes = 10, incomingBytes = 5, keep = setOf("next")))
    }

    @Test
    fun withNoSignalWhatWasNotHeardPlaysFirst() {
        val entries = listOf(entry("heard", played = 100), entry("older", played = 50), entry("fresh", added = 10), entry("recent", added = 5))
        val order = ReservePolicy.playOrder(entries) { it == "recent" }.map { it.id }
        assertEquals(listOf("fresh", "recent", "older", "heard").first(), order.first())
        assertEquals("heard lately goes among the heard", listOf("fresh", "recent", "older", "heard"), order)
    }

    // --- Reactions without a connection ---------------------------------------------------------------

    @Test
    fun onlyTheLastWordAboutATrackIsSent() {
        var queue = PendingReactions.add(emptyList(), PendingReaction("1", PendingReaction.Kind.LIKE))
        queue = PendingReactions.add(queue, PendingReaction("2", PendingReaction.Kind.DISLIKE))
        queue = PendingReactions.add(queue, PendingReaction("1", PendingReaction.Kind.UNLIKE))
        assertEquals(listOf(PendingReaction("2", PendingReaction.Kind.DISLIKE), PendingReaction("1", PendingReaction.Kind.UNLIKE)), queue)
    }

    @Test
    fun aSavedReactionReadsBack() {
        val reaction = PendingReaction("123:456", PendingReaction.Kind.DISLIKE)
        assertEquals(reaction, PendingReactions.parse(reaction.saved))
        assertNull(PendingReactions.parse("nonsense"))
        assertNull(PendingReactions.parse("LIKE:"))
    }
}
