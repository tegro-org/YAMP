package org.tegro.yamp.provider.jellyfin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
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

    private val resolver = JellyfinStreamResolver(
        baseUrl = "http://10.0.2.2:8096/",
        userId = "user7",
        accountKey = "jellyfin:srv1/user7",
        info = info,
        token = { token },
        profile = { JellyfinStreamProfile(lossless = lossless, maxSampleRateHz = 48_000) }
    )

    private suspend fun resolve(prefs: StreamPrefs = StreamPrefs()): ResolvedStream =
        (resolver.resolve(track, prefs) as Outcome.Ok).value

    @Test
    fun theUniversalUrlCarriesTheDeviceCapabilitiesAndNoToken() = runTest {
        val stream = resolve()

        assertTrue(stream.uri, stream.uri.startsWith("http://10.0.2.2:8096/Audio/item42/universal?"))
        assertTrue(stream.uri.contains("UserId=user7"))
        assertTrue(stream.uri.contains("DeviceId=dev-1"))
        assertTrue("the file as it is when the device plays it", stream.uri.contains("Container=flac%2Cmp3"))
        assertTrue("never more than the mixer plays", stream.uri.contains("MaxSampleRate=48000"))
        assertTrue(stream.uri.contains("TranscodingContainer=mp3&TranscodingProtocol=http&AudioCodec=mp3"))
        assertTrue("no redirect can carry the header to another host", stream.uri.contains("EnableRedirection=false"))
        assertFalse("the token never goes in the URL (D79)", stream.uri.contains("t0k"))
        assertFalse(stream.uri.contains("api_key", ignoreCase = true))
    }

    @Test
    fun theTokenGoesInTheAuthorizationHeader() = runTest {
        val stream = resolve()

        assertEquals(mapOf(JellyfinAuthHeader.NAME to JellyfinAuthHeader.value(info, "t0k")), stream.headers)
    }

    @Test
    fun compatibleSoundAsksForMp3AndIsAnotherCacheKey() = runTest {
        val asIs = resolve()
        lossless = false
        resolver.invalidate(track)
        val compatible = resolve()

        assertTrue(compatible.uri.contains("Container=mp3&"))
        assertTrue(compatible.uri.contains("MaxStreamingBitrate=320000"))
        assertEquals("mp3", compatible.quality?.codec)
        assertEquals(320, compatible.quality?.bitrateKbps)
        assertNull("the server decides the file's own format", asIs.quality)
        assertEquals("jellyfin:srv1/user7|item42|direct", asIs.cacheKey)
        assertEquals("jellyfin:srv1/user7|item42|mp3-320", compatible.cacheKey)
        assertNotEquals(asIs.cacheKey, compatible.cacheKey)
    }

    @Test
    fun aLowerBitrateOrRateAskedForWins() = runTest {
        val stream = resolve(StreamPrefs(maxBitrateKbps = 192, maxSampleRateHz = 44_100))

        assertTrue(stream.uri.contains("MaxStreamingBitrate=192000"))
        assertTrue(stream.uri.contains("MaxSampleRate=44100"))
    }

    @Test
    fun aResolvedLinkIsCachedUntilInvalidated() = runTest {
        assertNull(resolver.cached(track))
        val stream = resolve()
        assertSame(stream, resolver.cached(track))

        resolver.invalidate(track)

        assertNull(resolver.cached(track))
    }

    @Test
    fun withoutATokenNothingResolves() = runTest {
        token = null

        assertEquals(Outcome.Err(ProviderError.Unauthorized), resolver.resolve(track, StreamPrefs()))
        assertNull(resolver.cached(track))
    }
}
