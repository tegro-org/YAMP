package org.tegro.yamp.provider.jellyfin

import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.ProviderError
import org.tegro.yamp.domain.provider.ResolvedStream
import org.tegro.yamp.domain.provider.StreamPrefs
import org.tegro.yamp.domain.provider.StreamQuality
import org.tegro.yamp.domain.provider.StreamResolver
import org.tegro.yamp.domain.queue.TrackRef
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * What the device and the user allow for one stream (D79). [lossless] false is the "compatible" sound mode: the
 * server sends MP3 whatever the file is. [maxSampleRateHz] is the device mixer's rate, so the server never sends
 * more than the device plays.
 */
data class JellyfinStreamProfile(
    val lossless: Boolean,
    val maxSampleRateHz: Int? = null
)

/**
 * One Jellyfin account's streams (docs/phase-3-providers.md step 23, D79): `Audio/{id}/universal` with the device's
 * capabilities — the server plays the file as it is when the device can, and transcodes to MP3 otherwise. The token
 * goes in the `Authorization` header ([ResolvedStream.headers]), never in the URL; the engine sends it to this URL
 * only. Redirection is off, so the header never follows the request to another host.
 *
 * [accountKey] is the account's id (`AccountId.value`): the disk-cache key is `account|item|quality`, the same shape
 * as the Yandex one, so the same item in another sound mode is another key.
 */
class JellyfinStreamResolver(
    private val baseUrl: String,
    private val userId: String,
    private val accountKey: String,
    private val info: JellyfinClientInfo,
    private val token: () -> String?,
    private val profile: suspend () -> JellyfinStreamProfile
) : StreamResolver {

    /** Links handed out, by track ref: a universal URL does not expire, so it stays until [invalidate]. */
    private val links = ConcurrentHashMap<String, ResolvedStream>()

    override fun cached(track: TrackRef): ResolvedStream? = links[track.value]

    override suspend fun resolve(track: TrackRef, prefs: StreamPrefs): Outcome<ResolvedStream> {
        val auth = token()?.takeIf { it.isNotEmpty() } ?: return Outcome.Err(ProviderError.Unauthorized)
        val mode = profile()
        val stream = stream(track.id, mode, prefs, auth)
        links[track.value] = stream
        return Outcome.Ok(stream)
    }

    override fun invalidate(track: TrackRef) {
        links.remove(track.value)
    }

    internal fun stream(itemId: String, mode: JellyfinStreamProfile, prefs: StreamPrefs, auth: String): ResolvedStream {
        val maxKbps = prefs.maxBitrateKbps ?: if (mode.lossless) LOSSLESS_MAX_KBPS else COMPATIBLE_KBPS
        val sampleRate = listOfNotNull(prefs.maxSampleRateHz, mode.maxSampleRateHz).minOrNull()
        val quality = if (mode.lossless) null else StreamQuality(TRANSCODE_CODEC, minOf(maxKbps, COMPATIBLE_KBPS))
        return ResolvedStream(
            uri = universalUrl(itemId, mode.lossless, maxKbps, sampleRate),
            headers = mapOf(JellyfinAuthHeader.NAME to JellyfinAuthHeader.value(info, auth)),
            quality = quality,
            cacheKey = "$accountKey|$itemId|${if (mode.lossless) "direct" else "$TRANSCODE_CODEC-${quality?.bitrateKbps}"}"
        )
    }

    /** The universal endpoint's URL; no token in it (D79). */
    internal fun universalUrl(itemId: String, lossless: Boolean, maxKbps: Int, sampleRate: Int?): String = buildString {
        append(baseUrl).append("Audio/").append(enc(itemId)).append("/universal")
        append("?UserId=").append(enc(userId))
        append("&DeviceId=").append(enc(info.deviceId))
        append("&Container=").append(enc(if (lossless) DIRECT_CONTAINERS else TRANSCODE_CODEC))
        append("&MaxStreamingBitrate=").append(maxKbps * BITS_PER_KBIT)
        if (sampleRate != null) append("&MaxSampleRate=").append(sampleRate)
        append("&TranscodingContainer=").append(TRANSCODE_CODEC)
        append("&TranscodingProtocol=http")
        append("&AudioCodec=").append(TRANSCODE_CODEC)
        // A transcode is constant bitrate: the same bytes every time (the disk copy and a resumed read agree) and
        // seekable by position once its length is known
        append("&EnableAudioVbrEncoding=false")
        append("&EnableRedirection=false")
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        /** What the player plays as it is (Media3 extractors, the platform's decoders on Android 9+). */
        const val DIRECT_CONTAINERS = "flac,mp3,aac,m4a|aac,ogg|vorbis,oga|vorbis,opus,webm|opus,wav"
        const val TRANSCODE_CODEC = "mp3"
        const val COMPATIBLE_KBPS = 320
        /** No real cap for the file as it is: a FLAC of a few Mbit/s plays untouched. */
        const val LOSSLESS_MAX_KBPS = 140_000
        private const val BITS_PER_KBIT = 1000
    }
}
