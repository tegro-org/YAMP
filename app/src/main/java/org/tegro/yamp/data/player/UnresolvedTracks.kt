package org.tegro.yamp.data.player

/**
 * The server gave no link to a track: the track, or the link? A track taken out of the catalogue made the link
 * "weak" for five minutes (upstream 1.14.8 reports: one such track in a playlist, and the music went to the copies
 * with the internet in order). Now it is the track's trouble while the server answers, unless several different
 * tracks in a row get no link: that is the link after all. Pure; unit-tested.
 */
internal class UnresolvedTracks(private val now: () -> Long) {
    enum class Verdict { TRACK, LINK }

    /** Track ids with no link, and when each failed last; the window keeps only the recent ones. */
    private val failed = LinkedHashMap<String, Long>()

    /**
     * [trackId] got no link. [serverAnswers]: the service itself answered just now. [markedUnavailable]: the service
     * said the track has no stream.
     */
    fun onFailed(trackId: String, serverAnswers: Boolean, markedUnavailable: Boolean): Verdict {
        val t = now()
        failed.entries.removeAll { t - it.value > WINDOW_MS }
        failed[trackId] = t
        return when {
            !serverAnswers -> Verdict.LINK
            // The service itself said there is no stream of this track: never its link
            markedUnavailable -> Verdict.TRACK
            failed.size >= TRACKS_FOR_THE_LINK -> Verdict.LINK
            else -> Verdict.TRACK
        }
    }

    /** A link was given: whatever failed before was not the link. */
    fun onResolved() = failed.clear()

    companion object {
        const val WINDOW_MS = 2 * 60 * 1000L
        const val TRACKS_FOR_THE_LINK = 3
    }
}
