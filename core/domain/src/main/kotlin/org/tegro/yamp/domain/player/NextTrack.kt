package org.tegro.yamp.domain.player

/**
 * The state of the next track (D80, D81): what the Next button shows. [trackId] is the track it is about;
 * [Failed.reason] is a short text for the journal, not for the screen.
 */
sealed interface NextTrack {
    val trackId: String?

    /** Nothing after the current track, or nothing asked yet. */
    data object None : NextTrack {
        override val trackId: String? = null
    }

    /** Its link is being resolved, or its first seconds (or its file on disk) are on the way. */
    data class Loading(override val trackId: String) : NextTrack

    /** It can start at once: a warm player holds it, or its whole file is on disk. */
    data class Ready(override val trackId: String, val onDisk: Boolean) : NextTrack

    /** The last try failed; a tap on Next retries (load-first, D67), and a returning link retries by itself. */
    data class Failed(override val trackId: String, val reason: String) : NextTrack
}
