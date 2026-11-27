package org.tegro.yamp.provider.jellyfin

import org.tegro.yamp.domain.provider.MidTrackAction
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.ProviderError
import org.tegro.yamp.domain.provider.ResolvedStream
import org.tegro.yamp.domain.provider.StreamPrefs
import org.tegro.yamp.domain.provider.StreamResolver
import org.tegro.yamp.domain.queue.TrackRef
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * What the device and the user allow for one stream (D79). [lossless] true is the "original" sound mode: what the
 * device plays comes as it is, anything else (ALAC, APE, WavPack…) as the server's lossless FLAC. [lossless] false is
 * the "compressed" mode: a lossy file of about 320 kbps or less (MP3, AAC, Opus, Vorbis) plays as it is, anything
 * else (lossless, higher bitrate) comes as the server's MP3 (256 kbps). [economy] (with [lossless] false) is the
 * "economy" mode: the same at 128 kbps, the conversion an MP3 128.
 */
data class JellyfinStreamProfile(
    val lossless: Boolean,
    val economy: Boolean = false
)

/**
 * One Jellyfin account's streams (docs/phase-3-providers.md step 23, D79): `Audio/{id}/universal` with the device's
 * capabilities — the server plays the file as it is when the device can, and transcodes otherwise (FLAC in the
 * original mode, MP3 in the compressed one). The token
 * goes in the `Authorization` header ([ResolvedStream.headers]), never in the URL; the engine sends it to this URL
 * only. Redirection is off, so the header never follows the request to another host.
 *
 * [accountKey] is the account's id (`AccountId.value`): the disk-cache key is `account|item|quality`, the same shape
 * as the Yandex one, so the same item in another sound mode is another key. In the original mode a file the server
 * converts gets its own key ([CONVERTED_KEY]): an older build's conversion (MP3) under the plain one is never
 * played for today's FLAC; the files that come as they are keep theirs.
 */
class JellyfinStreamResolver(
    private val baseUrl: String,
    private val userId: String,
    private val accountKey: String,
    private val info: JellyfinClientInfo,
    private val token: () -> String?,
    private val profile: suspend () -> JellyfinStreamProfile,
    /** The track's downloaded copy (a file URI) this account may play; null: none, or not playable now. */
    private val localCopy: suspend (itemId: String) -> String? = { null },
    /** Every byte of the stream under this cache key is on disk (D86). */
    private val isComplete: (cacheKey: String) -> Boolean = { false },
    /** A connection is there; without one only what is on the device plays. */
    private val online: () -> Boolean = { true },
    /**
     * The server converts this stream: it answers with no length (a conversion is sent as it is made) — asked once
     * per item ([uri], [headers]: the stream's own). Null: no answer (no network, an error).
     */
    private val converts: suspend (uri: String, headers: Map<String, String>) -> Boolean? = { _, _ -> null },
    /**
     * The account's sound mode now, from memory (a link handed out in another mode is not reused: the setting or the
     * network changed). Null: not known, any link handed out is reused.
     */
    private val profileNow: () -> JellyfinStreamProfile? = { null }
) : StreamResolver {

    /** Links handed out, by track ref, with the mode each is for: a universal URL does not expire, so it stays until [invalidate]. */
    private val links = ConcurrentHashMap<String, Pair<JellyfinStreamProfile, ResolvedStream>>()

    /** Which items the server converts, per mode ([conversionKey]), as it answered; it stays for the process. */
    private val conversions = ConcurrentHashMap<String, Boolean>()

    override fun cached(track: TrackRef): ResolvedStream? =
        links[track.value]?.takeIf { (mode, _) -> profileNow().let { it == null || it == mode } }?.second

    /**
     * Order (gap b): the downloaded copy, then the network stream — which the disk cache serves whole when all of it
     * is there. Offline, a stream plays only when it is all on disk: the one of the account's quality now, else its
     * compressed one (a FLAC stream is never the fallback of a compressed-sound account: it would play silent).
     */
    override suspend fun resolve(track: TrackRef, prefs: StreamPrefs): Outcome<ResolvedStream> {
        localCopy(track.id)?.let { return Outcome.Ok(ResolvedStream(it, localFile = true)) }
        val auth = token()?.takeIf { it.isNotEmpty() } ?: return Outcome.Err(ProviderError.Unauthorized)
        val mode = profile()
        val stream = stream(track.id, mode, prefs, auth)
        if (!online()) {
            // A converted original under its own key first, unless the server is known to send this file as it is
            val converted = stream.converted().takeIf { mode.lossless && conversions[conversionKey(track.id, true)] != false }
            // Then the compressed one (an economy account's too: on the device it costs no traffic)
            val compressed = stream(track.id, JellyfinStreamProfile(lossless = false), prefs, auth)
            val onDisk = listOfNotNull(converted, stream, compressed)
                .firstOrNull { it.cacheKey?.let(isComplete) == true }
                ?: return Outcome.Err(ProviderError.Unavailable("offline, not on the device"))
            links[track.value] = mode to onDisk
            return Outcome.Ok(onDisk)
        }
        // A conversion comes with no length: the engine fetches it whole into a growing file (D91). Its key: the
        // original mode's own one (CONVERTED_KEY), the compressed mode's unchanged
        val converted = isConverted(track.id, mode, stream)
        val keyed = when {
            !converted -> stream
            mode.lossless -> stream.converted()
            else -> stream.copy(progressive = true)
        }
        links[track.value] = mode to keyed
        return Outcome.Ok(keyed)
    }



    /** The server converts [itemId] in this mode: asked once per mode; no answer counts as not (the plain key). */
    private suspend fun isConverted(itemId: String, mode: JellyfinStreamProfile, stream: ResolvedStream): Boolean {
        val key = conversionKey(itemId, mode.lossless, mode.economy)
        return conversions[key] ?: converts(stream.uri, stream.headers)?.also { conversions[key] = it } ?: false
    }

    /** Per mode: the economy cap converts more files than the compressed one does. */
    private fun conversionKey(itemId: String, lossless: Boolean, economy: Boolean = false) = when {
        lossless -> itemId
        economy -> "$itemId$ECONOMY_CONVERSION"
        else -> "$itemId$LOSSY_CONVERSION"
    }

    private fun ResolvedStream.converted(): ResolvedStream = copy(cacheKey = cacheKey?.let { it + CONVERTED_KEY }, progressive = true)

    /**
     * What a download fetches (gap b): the account's quality now through the universal endpoint — the file as it is
     * when the device plays it (a static stream with its length), else the server's conversion. Never the local copy. What
     * the bytes are is known only from the response, so the stream claims no quality. Its [ResolvedStream.cacheKey] is
     * the key a played conversion of it sits under in the stream cache.
     */
    suspend fun downloadStream(track: TrackRef): Outcome<ResolvedStream> {
        val auth = token()?.takeIf { it.isNotEmpty() } ?: return Outcome.Err(ProviderError.Unauthorized)
        val mode = profile()
        val stream = stream(track.id, mode, StreamPrefs(), auth)
        // Its key: where this URL's conversion is cached once played whole (D91), so the download copies it from there —
        // the original mode's converted key unless the server is known to send the file as it is, the compressed one's own
        val converted = mode.lossless && conversions[conversionKey(track.id, true)] != false
        return Outcome.Ok(if (converted) stream.copy(cacheKey = stream.cacheKey?.let { it + CONVERTED_KEY }) else stream)
    }

    override fun invalidate(track: TrackRef) {
        links.remove(track.value)
    }

    /** A refused token is the session's end, not a link to renew: playback stops and says so. */
    override fun midTrackAction(track: TrackRef, httpCode: Int?): MidTrackAction = when (httpCode) {
        401, 403 -> MidTrackAction.FATAL
        404, 410 -> MidTrackAction.UNAVAILABLE
        else -> MidTrackAction.RESTART
    }

    internal fun stream(itemId: String, mode: JellyfinStreamProfile, prefs: StreamPrefs, auth: String): ResolvedStream {
        val economy = !mode.lossless && mode.economy
        val maxKbps = prefs.maxBitrateKbps ?: when {
            mode.lossless -> LOSSLESS_MAX_KBPS
            economy -> ECONOMY_KBPS
            else -> COMPATIBLE_KBPS
        }
        // No quality: in either mode the server decides per file (as it is, or its conversion), and Media3 reads the format
        // from the bytes. A compressed stream has its own key: one cached as MP3 by an older build stays apart.
        val askKbps = when {
            mode.lossless -> maxKbps
            maxKbps == COMPATIBLE_KBPS -> COMPRESSED_PASSTHROUGH_KBPS
            maxKbps == ECONOMY_KBPS -> ECONOMY_PASSTHROUGH_KBPS
            else -> maxKbps
        }
        return ResolvedStream(
            // Economy names the conversion's bitrate: left to the server, it picks more than the cap (160 kbps, dev server)
            uri = universalUrl(itemId, mode.lossless, askKbps, conversionKbps = if (economy) minOf(maxKbps, ECONOMY_KBPS) else null),
            headers = mapOf(JellyfinAuthHeader.NAME to JellyfinAuthHeader.value(info, auth)),
            cacheKey = "$accountKey|$itemId|${if (mode.lossless) "direct" else "lossy-${minOf(maxKbps, COMPATIBLE_KBPS)}"}"
        )
    }

    /**
     * The universal endpoint's URL; no token in it (D79). No sample-rate cap: the endpoint has no `MaxSampleRate`, and
     * its `MaxAudioSampleRate` would resample every conversion (a lossless one would no longer be bit-exact).
     */
    internal fun universalUrl(itemId: String, lossless: Boolean, maxKbps: Int, conversionKbps: Int? = null): String = buildString {
        append(baseUrl).append("Audio/").append(enc(itemId)).append("/universal")
        append("?UserId=").append(enc(userId))
        append("&DeviceId=").append(enc(info.deviceId))
        append("&Container=").append(enc(if (lossless) DIRECT_CONTAINERS else LOSSY_CONTAINERS))
        append("&MaxStreamingBitrate=").append(maxKbps * BITS_PER_KBIT)
        append("&TranscodingContainer=").append(if (lossless) LOSSLESS_TRANSCODE_CONTAINER else TRANSCODE_CODEC)
        append("&TranscodingProtocol=http")
        append("&AudioCodec=").append(if (lossless) LOSSLESS_TRANSCODE_CODEC else TRANSCODE_CODEC)
        conversionKbps?.let { append("&AudioBitRate=").append(it * BITS_PER_KBIT) }
        // A transcode is constant bitrate: the same bytes every time (the disk copy and a resumed read agree) and
        // seekable by position once its length is known
        append("&EnableAudioVbrEncoding=false")
        append("&EnableRedirection=false")
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        /**
         * What the player plays as it is (Media3 extractors, the platform's decoders on Android 9+). The server names
         * an Opus file's container "ogg", so `ogg` lists Opus too (without it an Opus file came as MP3, 0.6.1).
         */
        const val DIRECT_CONTAINERS = "flac,mp3,aac,m4a|aac,ogg|vorbis|opus,oga|vorbis|opus,opus,webm|opus,wav"
        /**
         * What the compressed mode plays as it is: lossy formats only. Jellyfin's `container|codec|codec` names the
         * codecs allowed in a container, so ALAC in M4A (lossless) is converted while AAC in it plays as it is; the
         * server names an Opus or Vorbis file's container "ogg". Anything above [COMPRESSED_PASSTHROUGH_KBPS] is converted too.
         */
        const val LOSSY_CONTAINERS = "mp3,aac,m4a|aac,mp4|aac,ogg|vorbis|opus,oga|vorbis|opus,opus,webm|opus"
        const val TRANSCODE_CODEC = "mp3"
        /**
         * What the original mode converts a file the device can't play (ALAC: Android has no decoder) to: FLAC,
         * bit-exact, at the file's own rate and bit depth. In Ogg, not a bare `.flac`: a transcode's FLAC header is
         * written before the length is known (no sample count, no seek table), so Media3 can't seek it even from the
         * whole file on disk; an Ogg one seeks by its pages once the length is known (dev server, 0.6.1).
         */
        const val LOSSLESS_TRANSCODE_CONTAINER = "ogg"
        const val LOSSLESS_TRANSCODE_CODEC = "flac"
        /** What a converted original's cache key ends with: what it is converted to (`account|item|direct:flac-ogg`). */
        const val CONVERTED_KEY = ":$LOSSLESS_TRANSCODE_CODEC-$LOSSLESS_TRANSCODE_CONTAINER"
        const val COMPATIBLE_KBPS = 320
        /**
         * The cap the compressed mode asks the server for. The server compares the whole file's bitrate (size over
         * duration), so a 320 kbps MP3 measures above 320: 320.05–320.17 kbps for the ID3 tag alone, 327.8 kbps with
         * a 200 KB cover on a 3:25 track (dev server, 0.6.1) — and at 320 it was re-encoded. 340 lets a typical
         * 320 file with its cover (up to ~500 KB on a 3.5-minute track) pass as it is. A conversion stays the
         * server's 256 kbps MP3 either way; the cache key keeps [COMPATIBLE_KBPS].
         */
        const val COMPRESSED_PASSTHROUGH_KBPS = 340
        /** The economy mode's cap: a lossy file up to it plays as it is, the rest comes as an MP3 at it. */
        const val ECONOMY_KBPS = 128
        /**
         * The cap the economy mode asks for: [ECONOMY_KBPS] with the same 20 kbps margin as [COMPRESSED_PASSTHROUGH_KBPS]
         * (cover art and tags count). On the dev server an AAC 128 measures 130.2 kbps and was converted at 128, came as
         * it is at 131; an Opus 96 measures 106.9; the next common MP3 rate, 160, stays above it.
         */
        const val ECONOMY_PASSTHROUGH_KBPS = 148
        /** No real cap for the file as it is: a FLAC of a few Mbit/s plays untouched. */
        const val LOSSLESS_MAX_KBPS = 140_000
        private const val BITS_PER_KBIT = 1000
        /** What a compressed-mode answer is kept under in [conversions], beside the original mode's (the item id). */
        private const val LOSSY_CONVERSION = "#lossy"
        private const val ECONOMY_CONVERSION = "#lossy128"
    }
}
