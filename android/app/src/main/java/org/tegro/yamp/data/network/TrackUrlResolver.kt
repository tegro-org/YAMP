package org.tegro.yamp.data.network

import java.io.File

/**
 * What the player needs from the stream resolver: a playable link for a Yandex track id, what it is, and
 * whether a copy on the device beats it. [TrackPreloadManager] is the only implementation; the interface is
 * the seam that lets the controller and the engine be tested with a fake (ARCHITECTURE E6) and the first
 * step toward the per-provider `StreamResolver` (C4). Upstream-hot code stays in [TrackPreloadManager] (A3).
 */
interface TrackUrlResolver {
    /** The quality of the link last resolved for the track, if any. */
    fun qualityOf(trackId: String): TrackPreloadManager.StreamQuality?

    /** What the service offered for the track ("FLAC · 16/44"), for the quality details. */
    fun offeredOf(trackId: String): String?

    /** A link resolved earlier and still fresh, without a network round trip. */
    fun getResolvedUrl(trackId: String): String?

    /** Resolves or waits for the link in flight; null when the service has nothing. */
    suspend fun getOrFetchUrl(trackId: String): String?

    /** The ordinary (MP3/AAC) stream, for copies that must play on every decoder. */
    suspend fun resolveOrdinaryStream(trackId: String): TrackPreloadManager.ResolvedStream?

    /** The downloaded or reserve file that can play, if one exists. */
    fun playableCopy(trackId: String): File?

    /** Main-thread friendly: a playable copy is known to exist. */
    fun hasPlayableCopy(trackId: String): Boolean

    /** Forgets a link that failed, so the next request resolves afresh. */
    fun evictUrl(trackId: String)

    /** Warms links for the tracks about to play. */
    fun preloadTracks(trackIds: List<String>)

    /** Forgets every link (account change, sound mode change). */
    fun clearCache()
}
