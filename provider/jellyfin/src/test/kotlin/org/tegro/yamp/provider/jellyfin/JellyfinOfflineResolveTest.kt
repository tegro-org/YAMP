package org.tegro.yamp.provider.jellyfin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.ResolvedStream

/** Gap (b): the resolver's order — the downloaded copy, then a stream all on disk when offline, then the network. */
class JellyfinOfflineResolveTest {
    private val info = JellyfinClientInfo(client = "YAMP", device = "Head unit", deviceId = "dev-1", version = "0.5.0")
    private val track = JellyfinRefs("srv1").track("item42")
    private var copy: String? = null
    private var online = true
    private var lossless = true
    private var economy = false
    private val complete = mutableSetOf<String>()

    private val resolver = JellyfinStreamResolver(
        baseUrl = "http://10.0.2.2:8096/",
        userId = "user7",
        accountKey = "jellyfin@srv1/user7",
        info = info,
        token = { "t0k" },
        profile = { JellyfinStreamProfile(lossless = lossless, economy = economy) },
        localCopy = { copy },
        isComplete = { it in complete },
        online = { online }
    )

    private suspend fun resolved(): ResolvedStream = (resolver.resolve(track) as Outcome.Ok).value

    @Test
    fun aDownloadedCopyComesFirstEvenOnline() = runTest {
        copy = "file:///data/tracks/item42.mp3"
        complete += "jellyfin@srv1/user7|item42|direct"

        val stream = resolved()
        assertEquals("file:///data/tracks/item42.mp3", stream.uri)
        assertTrue(stream.localFile)
        assertEquals("a copy is never read through the cache", null, stream.cacheKey)
    }

    @Test
    fun offlineAStreamAllOnDiskPlaysByItsKey() = runTest {
        online = false
        complete += "jellyfin@srv1/user7|item42|direct"

        val stream = resolved()
        assertEquals("jellyfin@srv1/user7|item42|direct", stream.cacheKey)
        assertFalse(stream.localFile)
    }

    @Test
    fun offlineAnEconomyAccountPlaysItsOwnStreamThenTheCompressedOne() = runTest {
        online = false
        lossless = false
        economy = true
        complete += "jellyfin@srv1/user7|item42|lossy-320"
        assertEquals("on the device it costs no traffic", "jellyfin@srv1/user7|item42|lossy-320", resolved().cacheKey)

        complete += "jellyfin@srv1/user7|item42|lossy-128"
        assertEquals("jellyfin@srv1/user7|item42|lossy-128", resolved().cacheKey)
    }

    @Test
    fun offlineAnOriginalAccountFallsBackToItsCompressedStreamOnDisk() = runTest {
        online = false
        complete += "jellyfin@srv1/user7|item42|lossy-320"

        assertEquals("jellyfin@srv1/user7|item42|lossy-320", resolved().cacheKey)
    }

    @Test
    fun offlineAConvertedOriginalOnDiskComesBeforeAnOlderCopyUnderThePlainKey() = runTest {
        online = false
        complete += "jellyfin@srv1/user7|item42|direct"
        complete += "jellyfin@srv1/user7|item42|direct:flac-ogg"

        assertEquals("jellyfin@srv1/user7|item42|direct:flac-ogg", resolved().cacheKey)
    }

    @Test
    fun offlineACompatibleAccountNeverFallsBackToFlac() = runTest {
        online = false
        lossless = false
        complete += "jellyfin@srv1/user7|item42|direct"

        assertTrue("a FLAC stream plays silent in compatible sound", resolver.resolve(track) is Outcome.Err)
    }

    @Test
    fun offlineWithNothingOnDiskIsUnavailable() = runTest {
        online = false
        assertTrue(resolver.resolve(track) is Outcome.Err)
    }

    @Test
    fun onlineWithoutACopyStreamsFromTheNetwork() = runTest {
        val stream = resolved()
        assertTrue(stream.uri.startsWith("http://10.0.2.2:8096/Audio/item42/universal?"))
        assertEquals("jellyfin@srv1/user7|item42|direct", stream.cacheKey)
    }

    @Test
    fun aDownloadFetchesTheNetworkStreamNotTheCopy() = runTest {
        copy = "file:///data/tracks/item42.mp3"
        val stream = (resolver.downloadStream(track) as Outcome.Ok).value
        assertTrue(stream.uri.startsWith("http://10.0.2.2:8096/Audio/item42/universal?"))
        assertTrue("the auth header goes with it", stream.headers.containsKey(JellyfinAuthHeader.NAME))
        assertEquals(
            "the key a played conversion sits under: the download copies it from the cache (D91)",
            "jellyfin@srv1/user7|item42|direct:flac-ogg", stream.cacheKey
        )
    }
}
