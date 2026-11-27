package org.tegro.yamp.domain.provider

import org.tegro.yamp.domain.queue.TrackRef

/**
 * The quality of a stream as the provider describes it: [codec] as the service names it ("flac", "mp3",
 * "aac"), [bitrateKbps] its nominal bitrate.
 */
data class StreamQuality(val codec: String, val bitrateKbps: Int) {
    val isLossless: Boolean get() = codec.startsWith("flac", ignoreCase = true)
}

/**
 * How the bytes must be decrypted on the way to the decoder. A marker the provider's own data-source factory
 * understands (Yandex encraw stays inside the Yandex code, A3); the engine never decrypts by itself.
 */
@JvmInline
value class Decryption(val scheme: String)

/**
 * Everything the engine needs to play a track (C4, D74): it never asks a resolver. [headers] go with every
 * request for [uri] (a token in a header, never in the URL — D79); [expiresAtMs] is when the link stops working,
 * if the provider knows; [localFile] marks a copy on the device; [cacheKey] lets the bytes be kept on disk.
 */
data class ResolvedStream(
    val uri: String,
    val headers: Map<String, String> = emptyMap(),
    val quality: StreamQuality? = null,
    val decryption: Decryption? = null,
    val expiresAtMs: Long? = null,
    val localFile: Boolean = false,
    /**
     * A stable key for the on-disk stream cache (D86): the same account, track and quality always give the same
     * key, whatever the (signed, short-lived) [uri] is. Null: never cached (a copy on the device, radio).
     */
    val cacheKey: String? = null,
    /**
     * The track's own length from the catalog, for when the stream does not tell it (a server-side transcode sent
     * without a length): the engine times the end-of-track transition by it.
     */
    val durationMs: Long? = null,
    /**
     * The server converts this stream as it sends it: no length, no byte ranges (a Jellyfin transcode). The engine
     * fetches it once, whole, into a growing file the player reads (D91); seeking waits until it is complete.
     */
    val progressive: Boolean = false
)

/**
 * What the device and the link allow (D79): a provider that transcodes (Jellyfin) asks its server for no more.
 * Null means no limit. Yandex picks by the account's sound mode and ignores these.
 */
data class StreamPrefs(
    val maxBitrateKbps: Int? = null
)

/**
 * What the controller does when a stream fails mid-track, as the track's provider classifies the failure from the
 * HTTP status the server answered (null: no answer, a network error).
 */
enum class MidTrackAction {
    /** The link was refused as expired or unauthorised: ask for a fresh one and go on from the same second. */
    RENEW,

    /** The same again from the same second (a dropped connection, a broken download): the default. */
    RESTART,

    /** The track is gone from the provider (or the file from the device): no retry, the next track. */
    UNAVAILABLE,

    /** The session is dead (access refused for good): playback stops and says so, never a skip loop. */
    FATAL
}

/**
 * Turns a track into a playable stream (C4). The controller resolves; the engine only plays (D74).
 *
 * A resolver belongs to one account — the provider's own (docs/phase-3-providers.md step 10): a link resolved for
 * one account is never handed to another (docs/phase-2a-accounts.md §4). The controller picks the resolver of the
 * entry's account through the provider registry.
 */
interface StreamResolver {
    /** A link resolved earlier and still fresh — no disk, no network, safe on the main thread — or null. */
    fun cached(track: TrackRef): ResolvedStream?

    /** A copy on the device first, then a fresh link, then the network. */
    suspend fun resolve(track: TrackRef, prefs: StreamPrefs = StreamPrefs()): Outcome<ResolvedStream>

    /** The stream failed: the next [resolve] asks afresh instead of handing out the same link. */
    fun invalidate(track: TrackRef)

    /** What to do about a failure of [track]'s stream mid-track, [httpCode] being the server's answer if any. Pure, no I/O. */
    fun midTrackAction(track: TrackRef, httpCode: Int?): MidTrackAction = MidTrackAction.RESTART
}
