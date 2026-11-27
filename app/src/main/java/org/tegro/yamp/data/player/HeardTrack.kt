package org.tegro.yamp.data.player

/**
 * Which track has been heard since it started: only its waits for data are stops in the middle of a track. A track
 * that starts (another one, or the same one again) has not been heard yet. Kept from the last heard track, the start
 * of a track the listener came back to counted as a stop mid-track: two of them, and the link was "uneven" on a
 * perfect Wi-Fi (upstream 1.14.9 report). Pure; unit-tested.
 */
internal class HeardTrack {
    private var id: String? = null

    /** A track starts: from the top, from a picked second, or again. */
    fun onStart() {
        id = null
    }

    /** The track sounds. */
    fun onAudible(trackId: String?) {
        id = trackId
    }

    /** Waiting for data now is a stop in the middle of this track. */
    fun isMidTrack(trackId: String?): Boolean = trackId != null && trackId == id
}
