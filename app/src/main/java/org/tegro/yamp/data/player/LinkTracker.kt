package org.tegro.yamp.data.player

import org.tegro.yamp.data.provider.yandex.network.StreamVariants
import org.tegro.yamp.data.network.TrackPreloadManager.StreamQuality

/** How well the music gets through: what the screens say and where the next track comes from. */
enum class LinkState {
    GOOD,
    /** The server answers, but the stream does not keep up. */
    WEAK,
    /** No network, or a network that carries no data (a tunnel). */
    NONE
}

/** Why the link changed: written to the playback journal, so a report can be read later. */
enum class LinkReason {
    /** The music ran dry mid-track. */
    STREAM_STALLED,
    /** A track got no link from the server. */
    REQUEST_FAILED,
    /** The service itself does not answer. */
    SERVER_SILENT,
    /** The service answers, the server of the streams does not (a network letting only some addresses through). */
    STREAMS_BLOCKED,
    /** The network went away. */
    NETWORK_GONE,
    /** The periodic check got through after the service had not answered. */
    SERVER_ANSWERED,
    /** A stream really plays. */
    STREAM_SOUNDS
}

/**
 * Tells the state of the link from what actually happens to the music; signal bars and speed tests say
 * little on the road. Short drops the player's buffer covers never get here. Pure; unit-tested.
 */
internal class LinkTracker(private val now: () -> Long) {
    private var networkPresent = true
    private var serverReachable = true
    private var weakSinceMs: Long? = null

    /** Why the link is in the state it is: the last trouble or the check that ended it. */
    var lastReason: LinkReason? = null
        private set

    private var stallSinceMs: Long? = null
    private val countedStalls = ArrayDeque<Long>()

    /** Stops mid-track that are long enough to mean anything: the last change of [onStallOver] made the link uneven. */
    var unevenByLastStop: Boolean = false
        private set

    val state: LinkState
        get() = when {
            !networkPresent || !serverReachable -> LinkState.NONE
            weakSinceMs != null -> LinkState.WEAK
            else -> LinkState.GOOD
        }

    /** Android reports the network appearing or going. A network that came back is given a clean start. */
    fun onNetwork(present: Boolean) {
        if (present && !networkPresent) {
            serverReachable = true
            weakSinceMs = null
        }
        if (!present) lastReason = LinkReason.NETWORK_GONE
        networkPresent = present
    }

    /** The stream stalled or a request failed; [reachable] is what the follow-up check of the server found. */
    fun onTrouble(reachable: Boolean, reason: LinkReason = LinkReason.STREAM_STALLED) {
        serverReachable = reachable
        lastReason = if (reachable) reason else LinkReason.SERVER_SILENT
        weakSinceMs = if (reachable) now() else null
    }

    /**
     * The service answers and the server the streams come from does not: no music over the network at all while it
     * lasts, which is not a plain weak link (a weak link still plays, just not evenly). Pure; unit-tested.
     */
    fun onStreamsBlocked() {
        serverReachable = false
        lastReason = LinkReason.STREAMS_BLOCKED
        weakSinceMs = null
    }

    /** The periodic check of the server got through after it had not. */
    fun onServerReachable() {
        serverReachable = true
        lastReason = LinkReason.SERVER_ANSWERED
    }

    /** A stream really plays: the server is there and the weak-link mode is over. */
    fun onStreamAudible() {
        serverReachable = true
        weakSinceMs = null
        lastReason = LinkReason.STREAM_SOUNDS
    }

    /** While the link is weak, the stream gets another try once every [WEAK_RETRY_MS]. */
    fun mayTryStream(): Boolean = when (state) {
        LinkState.GOOD -> true
        LinkState.WEAK -> now() - (weakSinceMs ?: 0L) >= WEAK_RETRY_MS
        LinkState.NONE -> false
    }

    /**
     * The music ran dry mid-track. Nothing is decided yet: a wait of a tenth of a second (the refill after a pause
     * or a seek) says nothing of the link, yet two of them made it "uneven" on a perfect Wi-Fi (upstream 1.14.10).
     * It is weighed when it ends ([onStallOver]) or once it lasts long ([onStallLasted]). Pure; unit-tested.
     */
    fun onStall() {
        stallSinceMs = now()
    }

    /** Asked [LONG_STALL_MS] after a stall began: one stop that long is uneven on its own. */
    fun onStallLasted(): Boolean {
        val since = stallSinceMs ?: return false
        return now() - since >= LONG_STALL_MS
    }

    /**
     * The music is back. @return how long it stood, or null when it was not standing. A stop shorter than
     * [COUNTED_STALL_MS] is not counted at all.
     */
    fun onStallOver(): Long? {
        unevenByLastStop = false
        val since = stallSinceMs ?: return null
        stallSinceMs = null
        val at = now()
        val duration = at - since
        if (duration >= COUNTED_STALL_MS) {
            countedStalls.addLast(since)
            while (countedStalls.isNotEmpty() && at - countedStalls.first() > STALL_WINDOW_MS) countedStalls.removeFirst()
            unevenByLastStop = countedStalls.size >= STALLS_FOR_UNEVEN
        }
        return duration
    }

    /** Stops long enough to count within [STALL_WINDOW_MS]: two of them tell an uneven link. */
    val stallsInWindow: Int get() = countedStalls.size

    /** A track left this link's troubles behind (a new track, another source): its waits are not its stops. */
    fun onStallForgotten() {
        stallSinceMs = null
        unevenByLastStop = false
    }

    companion object {
        const val WEAK_RETRY_MS = 5 * 60 * 1000L
        /** A stop that lasts this long is weighed while it still runs (upstream's 1.5 s). */
        const val LONG_STALL_MS = 1_500L
        /** Shorter stops are not counted: a refill after a pause or a seek, not the link. */
        const val COUNTED_STALL_MS = 500L
        /** Counted stops this close together tell an uneven link. */
        const val STALL_WINDOW_MS = 120_000L
        const val STALLS_FOR_UNEVEN = 2
    }
}

/** Where a track that is not downloaded should come from. */
enum class TrackOrigin { STREAM, COPY, UNAVAILABLE }

/** Pure; unit-tested. */
object RoadPlan {
    fun originFor(link: LinkState, mayTryStream: Boolean, hasCopy: Boolean): TrackOrigin = when {
        link == LinkState.NONE -> if (hasCopy) TrackOrigin.COPY else TrackOrigin.UNAVAILABLE
        link == LinkState.WEAK && !mayTryStream && hasCopy -> TrackOrigin.COPY
        else -> TrackOrigin.STREAM
    }

    /**
     * With a good link the stream plays whenever it is better than the copy; an equal stream would only
     * spend the traffic a second time.
     */
    fun copyIsEnough(stream: StreamQuality?, copy: StreamQuality): Boolean =
        stream == null || !StreamVariants.isBetter(stream, copy)
}
