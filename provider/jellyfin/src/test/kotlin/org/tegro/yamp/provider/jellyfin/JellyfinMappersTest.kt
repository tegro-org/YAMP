package org.tegro.yamp.provider.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.domain.queue.MediaRef

/** Mappers against JSON recorded from the local dev server (scripts/jellyfin-dev, D85); ids are scrubbed. */
class JellyfinMappersTest {
    private val serverId = "0123456789abcdef0123456789abcdef"
    private val base = "http://10.0.2.2:8096/"
    private val mappers = JellyfinMappers(JellyfinRefs(serverId), base)
    private val json = JellyfinApiFactory.json

    private val tracks = json.decodeFromString<ItemsResultDto>(resource("items-audio.json"))
    private val albums = json.decodeFromString<ItemsResultDto>(resource("items-albums.json"))

    @Test
    fun aTrackRefNamesTheServer() {
        val track = mappers.track(tracks.items.first())
        assertEquals("jellyfin@$serverId", track.ref.provider)
        assertEquals("jellyfin", track.ref.ref.providerType)
        assertEquals(serverId, track.ref.ref.instance)
        assertEquals(MediaRef.KIND_TRACK, track.ref.ref.kind)
    }

    @Test
    fun aTrackCarriesTitleArtistAlbumAndDurationInMilliseconds() {
        val item = tracks.items.first { it.name == "Fluffing a Duck" }
        val track = mappers.track(item)
        assertEquals("Fluffing a Duck", track.title)
        assertEquals(listOf("Kevin MacLeod"), track.artists.map { it.name })
        assertEquals("YAMP Test FLAC", track.album?.title)
        assertEquals("album", track.album?.ref?.kind)
        val ms = checkNotNull(track.durationMs)
        assertEquals(item.runTimeTicks!! / 10_000, ms)
        assertTrue("a real track length", ms in 30_000L..600_000L)
    }

    @Test
    fun aTrackWithEmbeddedArtHasACoverSizedByTheServer() {
        val item = tracks.items.first { it.name == "Fluffing a Duck" }
        val cover = checkNotNull(mappers.track(item).cover)
        val url = cover.url(300)
        assertEquals(base + "Items/${item.id}/Images/Primary?fillWidth=300&fillHeight=300&quality=90&tag=${item.imageTags["Primary"]}", url)
        assertEquals("the image cache keys by item and tag, not size", cover.key, mappers.track(item).cover?.key)
        assertEquals("the key is the URL with the size left open (step 23)", url, cover.key.replace(JellyfinCover.SIZE, "300"))
    }

    @Test
    fun aTrackWithoutItsOwnImageFallsBackToTheAlbumCover() {
        val item = tracks.items.first { it.name == "Fluffing a Duck" }.copy(imageTags = emptyMap())
        val url = checkNotNull(mappers.track(item).cover).url(120)
        assertTrue(url, url.startsWith(base + "Items/${item.albumId}/Images/Primary"))
        assertTrue(url, url.endsWith("tag=${item.albumPrimaryImageTag}"))
    }

    @Test
    fun aTrackWithNoArtAnywhereHasNoCover() {
        assertNull(mappers.track(tracks.items.first { it.name == "Sneaky Snitch" }).cover)
    }

    @Test
    fun albumsMapWithTheirYearArtistsAndTrackOrder() {
        val flac = albums.items.first { it.name == "YAMP Test FLAC" }
        val flacTracks = tracks.items.filter { it.albumId == flac.id }.shuffled()
        val album = mappers.album(flac, flacTracks)
        assertEquals("album", album.ref.kind)
        assertEquals(listOf("Kevin MacLeod"), album.artists.map { it.name })
        assertEquals(2008, album.year)
        assertEquals(listOf("Fluffing a Duck", "Monkeys Spinning Monkeys", "Wallpaper"), album.tracks.map { it.title })
        assertEquals(2, albums.items.size)
    }

    @Test
    fun anAlbumWithoutEmbeddedArtHasNoCover() {
        val mp3 = albums.items.first { it.name == "YAMP Test MP3" }
        val album = mappers.album(mp3)
        if (mp3.imageTags["Primary"] == null) assertNull(album.cover)
    }
}
