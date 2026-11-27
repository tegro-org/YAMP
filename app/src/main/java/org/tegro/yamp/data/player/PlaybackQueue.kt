package org.tegro.yamp.data.player

/** Where the current queue came from. */
enum class PlaylistSource {
    LIBRARY, VIBE, SEARCH, TRENDS, RADIO, NONE
}

/** Sleep timer: either a moment in [android.os.SystemClock.elapsedRealtime] time or "at the end of this track". */
data class SleepTimerState(val endsAtElapsedMs: Long?, val atEndOfTrack: Boolean)

/** What to do when the stream of the current track fails. */
enum class Recovery {
    /** The stream does not get through, but the road reserve has this track: go on from the same second. */
    RESUME_FROM_COPY,
    /** No connection and the track is not on the device: go on with what is. */
    CONTINUE_OFFLINE,
    /** First failure of this track: fetch a fresh link and resume where it stopped (links expire). */
    RETRY,
    /** A station, or several tracks in a row: stop and tell the user. */
    STOP,
    /** This track is broken: the next one. */
    SKIP,
    /** The track is only slow (a network timeout, D67): pause on it, never skip — Play loads it again. */
    PAUSE
}

/** What "next" should do for a queue position. Pure logic, unit-tested in PlaybackQueueTest. */
sealed interface NextAction {
    data class PlayIndex(val index: Int) : NextAction
    /** End of My Wave: fetch more tracks and continue. */
    data object FetchMoreWave : NextAction
    /** Nothing to continue from: fall back to the liked tracks. */
    data object FallbackLibrary : NextAction
}

object PlaybackQueue {
    /** Apple Music behaviour: "previous" after this much playback restarts the current track. */
    const val RESTART_ON_PREV_THRESHOLD_MS = 3_000L
    /** Queue window persisted around the current track to keep the session file small. */
    const val SNAPSHOT_BEFORE = 50
    const val SNAPSHOT_AFTER = 150

    /**
     * Like [nextAction], passing over the tracks [skip] rejects. When every track would be passed over,
     * the filter gives way: a queue of only such tracks still plays rather than stopping dead.
     */
    fun <T> nextAction(queue: List<T>, currentIndex: Int, source: PlaylistSource, skip: (T) -> Boolean): NextAction {
        if (queue.none { !skip(it) }) return nextAction(queue.size, currentIndex, source)
        for (index in (currentIndex + 1) until queue.size) {
            if (!skip(queue[index])) return NextAction.PlayIndex(index)
        }
        if (source == PlaylistSource.VIBE) return NextAction.FetchMoreWave
        val first = queue.indexOfFirst { !skip(it) }
        return if (first >= 0) NextAction.PlayIndex(first) else NextAction.FallbackLibrary
    }

    fun nextAction(queueSize: Int, currentIndex: Int, source: PlaylistSource): NextAction = when {
        currentIndex != -1 && currentIndex + 1 < queueSize -> NextAction.PlayIndex(currentIndex + 1)
        source == PlaylistSource.VIBE -> NextAction.FetchMoreWave
        // Regular playlists loop back to the start so playback never silently stops.
        queueSize > 0 -> NextAction.PlayIndex(0)
        else -> NextAction.FallbackLibrary
    }

    /**
     * What will really play after [currentIndex], by the same rule as "next": tracks [skip] rejects are
     * passed over, a playlist goes on from its start, the wave stops at what it has fetched. At most
     * [limit] tracks, never the current one, never one twice.
     */
    fun <T> upcoming(queue: List<T>, currentIndex: Int, source: PlaylistSource, limit: Int, skip: (T) -> Boolean): List<T> {
        if (currentIndex !in queue.indices) return emptyList()
        val result = mutableListOf<T>()
        val seen = mutableSetOf(currentIndex)
        var index = currentIndex
        while (result.size < limit) {
            val next = nextAction(queue, index, source, skip) as? NextAction.PlayIndex ?: break
            if (!seen.add(next.index)) break
            result += queue[next.index]
            index = next.index
        }
        return result
    }

    /** Index of the previous track, or null when "previous" should restart the current one. */
    fun previousIndex(currentIndex: Int, progressMs: Long): Int? =
        if (progressMs > RESTART_ON_PREV_THRESHOLD_MS || currentIndex <= 0) null else currentIndex - 1

    /**
     * Index of the first item after [currentIndex] that satisfies [accept], or null. Used without a
     * connection to jump to the next track on the device; what was already heard is not gone back to.
     */
    fun <T> nextMatching(queue: List<T>, currentIndex: Int, accept: (T) -> Boolean): Int? {
        for (index in (currentIndex + 1) until queue.size) {
            if (accept(queue[index])) return index
        }
        return null
    }

    /** After the wave fetched more at the end of its queue: what plays next. */
    sealed interface AfterWaveFetch {
        data class Play(val index: Int) : AfterWaveFetch
        /** Nothing new: the wave does not start over (what is on the device plays meanwhile). */
        data object Stuck : AfterWaveFetch
        /** No wave at all: the liked tracks. */
        data object Nothing : AfterWaveFetch
    }

    /** [following] — the next track to play after the fetch, if any. Never the queue's first track again. Pure. */
    fun afterWaveFetch(following: Int?, queueSize: Int): AfterWaveFetch = when {
        following != null -> AfterWaveFetch.Play(following)
        queueSize > 0 -> AfterWaveFetch.Stuck
        else -> AfterWaveFetch.Nothing
    }

    /**
     * Player errors that mean "too slow", not "broken" (D67): a network that did not answer in time. Hard errors
     * — HTTP 403/404/410, a decoder failure, no link at all — still retry once and then skip.
     */
    fun isSlowness(reason: String): Boolean = reason in SLOWNESS_ERRORS

    private val SLOWNESS_ERRORS = setOf(
        "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
        "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
        "ERROR_CODE_TIMEOUT"
    )

    /**
     * A failure of the link, not of the track: the player's network errors and the app's own wait for data that never
     * came. Not ERROR_CODE_TIMEOUT (the output stood still, R4) nor a track the service marks unavailable. Pure.
     */
    fun isNetworkFailure(reason: String): Boolean =
        reason.startsWith("ERROR_CODE_IO_NETWORK") || reason == "ERROR_CODE_IO_UNSPECIFIED" || reason == "BUFFERING_TIMEOUT"

    /** Broken tracks in a row after which playback stops instead of skipping on. */
    const val MAX_FAILED_TRACKS = 3

    /**
     * Pure; unit-tested. [failedTracksInARow] counts this failure too. [fromDownload]: the failure came from a converted
     * stream's download (D91), which restarted already.
     */
    fun recoveryFor(
        offline: Boolean,
        isRadio: Boolean,
        isDownloaded: Boolean,
        alreadyRetried: Boolean,
        failedTracksInARow: Int,
        hasCopy: Boolean = false,
        weakLink: Boolean = false,
        slowness: Boolean = false,
        fromDownload: Boolean = false,
        /** The stream did not come (a network error, buffering that never ended): not the track's fault. */
        networkFailure: Boolean = false,
        /** There is music on the device to go on with. */
        deviceHasMusic: Boolean = false,
        /** The provider says the track is gone (HTTP 404/410, a service mark): the next track, whatever the link is. */
        unavailable: Boolean = false
    ): Recovery = when {
        offline && !isRadio && !isDownloaded -> if (hasCopy) Recovery.RESUME_FROM_COPY else Recovery.CONTINUE_OFFLINE
        unavailable && !isRadio && !isDownloaded && hasCopy -> Recovery.RESUME_FROM_COPY
        unavailable -> if (isRadio || failedTracksInARow >= MAX_FAILED_TRACKS) Recovery.STOP else Recovery.SKIP
        // A converted stream's download already restarted what it could (D91): a slow one pauses now, Play loads it anew
        slowness && fromDownload && !isRadio -> Recovery.PAUSE
        // A stream that did not come: its own copy, else the device's music, at once. Retrying, skipping and at last
        // stopping, track after silent track, left the music standing for hours under a hard block
        networkFailure && !isRadio && !isDownloaded && hasCopy -> Recovery.RESUME_FROM_COPY
        networkFailure && !isRadio && !isDownloaded && deviceHasMusic -> Recovery.CONTINUE_OFFLINE
        !alreadyRetried -> Recovery.RETRY
        // A fresh link did not help, and this is not the track's fault when the link is weak
        hasCopy && !isRadio && !isDownloaded -> Recovery.RESUME_FROM_COPY
        // Slowness never skips (D67): the track waits, paused, for the user
        slowness && !isRadio -> Recovery.PAUSE
        weakLink && !isRadio && !isDownloaded -> Recovery.CONTINUE_OFFLINE
        isRadio || failedTracksInARow >= MAX_FAILED_TRACKS -> Recovery.STOP
        else -> Recovery.SKIP
    }

    /** [list] with the item at [from] moved to [to]; out-of-range positions leave it unchanged. */
    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** The wave page wants at least this many tracks in its "next up" shelf. */
    const val WAVE_SHELF_MIN = 3

    /**
     * Is the wave page's "next up" shelf short of tracks? While the wave plays it shows the queue after the
     * current track; while it is idle, the saved wave without its first track (that one starts the wave).
     */
    fun waveShelfIsShort(waveActive: Boolean, currentId: String?, queue: List<String>, wave: List<String>): Boolean =
        if (waveActive) {
            val index = queue.indexOf(currentId)
            index < 0 || queue.size - index - 1 < WAVE_SHELF_MIN
        } else {
            wave.size - 1 < WAVE_SHELF_MIN
        }

    fun <T> snapshotWindow(queue: List<T>, currentIndex: Int): List<T> {
        if (currentIndex < 0) return queue.take(SNAPSHOT_AFTER)
        val from = (currentIndex - SNAPSHOT_BEFORE).coerceAtLeast(0)
        val to = (currentIndex + SNAPSHOT_AFTER).coerceAtMost(queue.size)
        return queue.subList(from, to)
    }
}
