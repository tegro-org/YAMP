package org.tegro.yamp.provider.jellyfin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.domain.provider.MidTrackAction
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.ProviderError
import org.tegro.yamp.domain.provider.ResolvedStream
import org.tegro.yamp.domain.provider.StreamPrefs

class JellyfinStreamResolverTest {
    private val info = JellyfinClientInfo(client = "YAMP", device = "Head unit", deviceId = "dev-1", version = "0.5.0")
    private val refs = JellyfinRefs("srv1")
    private val track = refs.track("item42")
    private var token: String? = "t0k"
    private var lossless = true
    private var economy = false
    /** The mode now, as [JellyfinStreamResolver.cached] sees it; null: unknown. */
    private var modeNow: JellyfinStreamProfile? = null
    /** What the HEAD probe answers: the server converts the item (null: no answer). */
    private var converts: Boolean? = false
    private var probes = 0

    private val resolver = JellyfinStreamResolver(
        baseUrl = "http://10.0.2.2:8096/",
        userId = "user7",
        accountKey = "jellyfin:srv1/user7",
        info = info,
        token = { token },
        profile = { JellyfinStreamProfile(lossless = lossless, economy = economy) },
        converts = { _, _ -> probes++; converts },
        profileNow = { modeNow }
    )

    private suspend fun resolve(prefs: StreamPrefs = StreamPrefs()): ResolvedStream =
        (resolver.resolve(track, prefs) as Outcome.Ok).value

    @Test
    fun aMidTrackFailureIsClassifiedByTheServersAnswer() {
        assertEquals(MidTrackAction.FATAL, resolver.midTrackAction(track, 401))
        assertEquals(MidTrackAction.FATAL, resolver.midTrackAction(track, 403))
        assertEquals(MidTrackAction.UNAVAILABLE, resolver.midTrackAction(track, 404))
        assertEquals(MidTrackAction.UNAVAILABLE, resolver.midTrackAction(track, 410))
        assertEquals(MidTrackAction.RESTART, resolver.midTrackAction(track, null))
    }

    @Test
    fun theUniversalUrlCarriesTheDeviceCapabilitiesAndNoToken() = runTest {
        val stream = resolve()

        assertTrue(stream.uri, stream.uri.startsWith("http://10.0.2.2:8096/Audio/item42/universal?"))
        assertTrue(stream.uri.contains("UserId=user7"))
        assertTrue(stream.uri.contains("DeviceId=dev-1"))
        assertTrue("the file as it is when the device plays it", stream.uri.contains("Container=flac%2Cmp3"))
        assertFalse("the universal endpoint has no MaxSampleRate", stream.uri.contains("SampleRate"))
        assertTrue("no redirect can carry the header to another host", stream.uri.contains("EnableRedirection=false"))
        assertFalse("the token never goes in the URL (D79)", stream.uri.contains("t0k"))
        assertFalse(stream.uri.contains("api_key", ignoreCase = true))
    }

    /**
     * 2026-11-17 ride: a file the device plays as it is comes back with its length and byte ranges (seekable); a
     * transcode comes chunked with no length — asked at a constant bitrate, so its disk copy and any re-read agree
     * byte for byte and seek by position once the length is known.
     */
    @Test
    fun aTranscodeIsAskedForAtAConstantBitrate() = runTest {
        val stream = resolve()
        assertTrue(stream.uri.contains("EnableAudioVbrEncoding=false"))
    }

    @Test
    fun theTokenGoesInTheAuthorizationHeader() = runTest {
        val stream = resolve()

        assertEquals(mapOf(JellyfinAuthHeader.NAME to JellyfinAuthHeader.value(info, "t0k")), stream.headers)
    }

    @Test
    fun compressedSoundPassesLossyFilesAndIsAnotherCacheKey() = runTest {
        val asIs = resolve()
        lossless = false
        resolver.invalidate(track)
        val compressed = resolve()

        val containers = java.net.URLDecoder.decode(compressed.uri.substringAfter("Container=").substringBefore('&'), "UTF-8")
        assertEquals(JellyfinStreamResolver.LOSSY_CONTAINERS, containers)
        assertTrue(compressed.uri.contains("MaxStreamingBitrate=340000"))
        assertTrue("anything else comes as MP3", compressed.uri.contains("TranscodingContainer=mp3&TranscodingProtocol=http&AudioCodec=mp3"))
        assertNull("the server decides per file: AAC, Opus or its MP3", compressed.quality)
        assertNull("the server decides the file's own format", asIs.quality)
        assertEquals("jellyfin:srv1/user7|item42|direct", asIs.cacheKey)
        assertEquals("an MP3 cached by an older build stays apart", "jellyfin:srv1/user7|item42|lossy-320", compressed.cacheKey)
        assertNotEquals(asIs.cacheKey, compressed.cacheKey)
    }

    /** 0.6.1: a 128 kbps AAC was converted to a bigger, worse MP3 320; ALAC shares its M4A container but is lossless. */
    @Test
    fun compressedSoundAllowsOnlyLossyCodecsInEachContainer() {
        val profiles = JellyfinStreamResolver.LOSSY_CONTAINERS.split(',').associate { entry ->
            entry.substringBefore('|') to entry.split('|').drop(1).toSet()
        }

        assertEquals(setOf("aac"), profiles["m4a"])
        assertEquals(setOf("aac"), profiles["mp4"])
        assertEquals("the server names an Opus file's container ogg", setOf("vorbis", "opus"), profiles["ogg"])
        assertTrue(profiles.containsKey("mp3"))
        assertTrue(profiles.containsKey("opus"))
        for (lossless in listOf("flac", "wav", "alac", "aiff", "ape", "wv")) {
            assertFalse(lossless, profiles.containsKey(lossless))
            assertFalse(lossless, profiles.values.any { lossless in it })
        }
        assertTrue("an unconstrained M4A would pass ALAC", profiles.filterKeys { it in setOf("m4a", "mp4") }.values.none { it.isEmpty() })
    }

    /**
     * 0.6.1: the server measures a 320 kbps MP3 by the whole file (320.08 kbps with its tag, 327.8 with a cover), so a
     * 320 000 cap re-encoded it; the margin lets it pass. A lower cap asked for stays exact; the key stays 320.
     */
    @Test
    fun compressedSoundAsksWithAMarginAbove320() = runTest {
        lossless = false
        val default = resolve()
        resolver.invalidate(track)
        val lower = resolve(StreamPrefs(maxBitrateKbps = 192))

        assertTrue(default.uri, default.uri.contains("MaxStreamingBitrate=340000&"))
        assertTrue(default.cacheKey!!.endsWith("|lossy-320"))
        assertTrue(lower.uri, lower.uri.contains("MaxStreamingBitrate=192000&"))
        assertTrue(lower.cacheKey!!.endsWith("|lossy-192"))
    }

    /** 0.6.1: the server names an Opus file's container "ogg"; with `ogg|vorbis` only, the originals mode got MP3. */
    @Test
    fun originalSoundPlaysOpusAsItIs() {
        val profiles = JellyfinStreamResolver.DIRECT_CONTAINERS.split(',').associate { entry ->
            entry.substringBefore('|') to entry.split('|').drop(1).toSet()
        }

        assertEquals(setOf("vorbis", "opus"), profiles["ogg"])
        assertEquals(setOf("opus"), profiles["webm"])
        assertTrue(profiles.containsKey("opus"))
        assertEquals("ALAC is unchanged: converted, as before", setOf("aac"), profiles["m4a"])
        assertTrue(profiles.containsKey("flac"))
    }

    @Test
    fun originalSoundIsUnchanged() = runTest {
        val stream = resolve()

        assertTrue(stream.uri.contains("Container=" + java.net.URLEncoder.encode(JellyfinStreamResolver.DIRECT_CONTAINERS, "UTF-8") + "&"))
        assertTrue(stream.uri.contains("MaxStreamingBitrate=140000000"))
    }

    /**
     * 0.6.1: in the original mode a file the device can't play (ALAC) came as MP3. It comes as FLAC — lossless — in
     * Ogg, which Media3 seeks once the whole file is on disk (a transcode's bare FLAC has no sample count). No bit
     * depth cap: a 24-bit file stays 24-bit.
     */
    @Test
    fun originalSoundConvertsToFlacInOgg() = runTest {
        val stream = resolve()

        assertTrue(stream.uri, stream.uri.contains("TranscodingContainer=ogg&TranscodingProtocol=http&AudioCodec=flac&"))
        assertFalse(stream.uri.contains("MaxAudioBitDepth"))
        assertFalse(stream.uri.contains("mp3&"))
    }

    @Test
    fun aLowerBitrateAskedForWins() = runTest {
        val stream = resolve(StreamPrefs(maxBitrateKbps = 192))

        assertTrue(stream.uri.contains("MaxStreamingBitrate=192000"))
        assertFalse("no sample-rate cap: it would resample every conversion", stream.uri.contains("SampleRate"))
    }

    /** An older build's ALAC→MP3 copy sits under the plain `direct` key: today's FLAC conversion has its own. */
    @Test
    fun aConvertedOriginalHasItsOwnCacheKey() = runTest {
        converts = true

        assertEquals("jellyfin:srv1/user7|item42|direct:flac-ogg", resolve().cacheKey)
    }

    @Test
    fun anOriginalSentAsItIsKeepsItsKey() = runTest {
        converts = false
        assertEquals("jellyfin:srv1/user7|item42|direct", resolve().cacheKey)

        resolver.invalidate(track)
        converts = null
        assertEquals("the answer is kept: asked once per item", "jellyfin:srv1/user7|item42|direct", resolve().cacheKey)
        assertEquals(1, probes)
    }

    @Test
    fun noAnswerKeepsThePlainKey() = runTest {
        converts = null
        assertEquals("jellyfin:srv1/user7|item42|direct", resolve().cacheKey)
    }

    /** D91: the compressed mode asks too — a conversion (MP3) is progressive — and keeps its key either way. */
    @Test
    fun compressedSoundAsksOnceAndKeepsItsKey() = runTest {
        lossless = false
        converts = true

        val converted = resolve()
        assertEquals("jellyfin:srv1/user7|item42|lossy-320", converted.cacheKey)
        assertTrue("a conversion is fetched whole into a growing file", converted.progressive)
        resolver.invalidate(track)
        converts = false
        assertTrue("the answer is kept per mode", resolve().progressive)
        assertEquals(1, probes)
    }

    /** D91: the probe sets `progressive` in both modes; a file sent as it is, or no answer, is not. */
    @Test
    fun progressiveFollowsTheProbeInBothModes() = runTest {
        converts = true
        assertTrue("original mode, converted", resolve().progressive)
        lossless = false
        resolver.invalidate(track)
        converts = false
        assertFalse("compressed mode, as it is", resolve().progressive)
        lossless = true
        resolver.invalidate(track)
        assertTrue("the original mode's answer is its own", resolve().progressive)
        assertEquals(2, probes)
    }

    @Test
    fun aFileSentAsItIsOrNoAnswerIsNotProgressive() = runTest {
        converts = false
        assertFalse(resolve().progressive)
        lossless = false
        resolver.invalidate(track)
        converts = null
        assertFalse(resolve().progressive)
    }

    @Test
    fun aResolvedLinkIsCachedUntilInvalidated() = runTest {
        assertNull(resolver.cached(track))
        val stream = resolve()
        assertSame(stream, resolver.cached(track))

        resolver.invalidate(track)

        assertNull(resolver.cached(track))
    }

    /**
     * Economy (canvas `qs-eco-jf`): lossy files up to 128 kbps as they are, with the same 20 kbps margin (dev server: an
     * AAC 128 measures 130.2 kbps), the rest as an MP3 at 128 kbps — named, else the server picks 160 —, its own key.
     */
    @Test
    fun economyAsksFor128WithAMarginAndConvertsToMp3At128() = runTest {
        lossless = false
        economy = true
        val stream = resolve()

        assertTrue(stream.uri, stream.uri.contains("MaxStreamingBitrate=148000&"))
        assertTrue(stream.uri, stream.uri.contains("TranscodingContainer=mp3&"))
        assertTrue(stream.uri, stream.uri.contains("AudioBitRate=128000&"))
        assertEquals("jellyfin:srv1/user7|item42|lossy-128", stream.cacheKey)
        val containers = java.net.URLDecoder.decode(stream.uri.substringAfter("Container=").substringBefore('&'), "UTF-8")
        assertEquals("lossy only, as compressed", JellyfinStreamResolver.LOSSY_CONTAINERS, containers)
    }

    @Test
    fun compressedAndOriginalNameNoConversionBitrate() = runTest {
        assertFalse(resolve().uri.contains("AudioBitRate"))
        lossless = false
        resolver.invalidate(track)
        assertFalse(resolve().uri.contains("AudioBitRate"))
        economy = true
        lossless = true
        resolver.invalidate(track)
        assertFalse("economy means nothing to the original mode", resolve().uri.contains("AudioBitRate"))
    }

    /** The economy cap converts files the compressed one passes: each mode asks the server once, apart. */
    @Test
    fun economyAsksTheProbeApartFromCompressed() = runTest {
        lossless = false
        converts = false
        assertFalse(resolve().progressive)
        economy = true
        converts = true
        resolver.invalidate(track)
        val eco = resolve()

        assertEquals(2, probes)
        assertTrue("an MP3 320 converted to 128: fetched whole", eco.progressive)
    }

    /** A link of another mode (the setting or the network changed) is not handed out again. */
    @Test
    fun aLinkOfAnotherModeIsNotReused() = runTest {
        lossless = false
        modeNow = JellyfinStreamProfile(lossless = false)
        val compressed = resolve()
        assertSame(compressed, resolver.cached(track))

        modeNow = JellyfinStreamProfile(lossless = false, economy = true)

        assertNull(resolver.cached(track))
    }

    @Test
    fun withoutATokenNothingResolves() = runTest {
        token = null

        assertEquals(Outcome.Err(ProviderError.Unauthorized), resolver.resolve(track, StreamPrefs()))
        assertNull(resolver.cached(track))
    }
}
