package org.tegro.yamp.data.provider.yandex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.data.dto.AlbumDto
import org.tegro.yamp.data.dto.ArtistDto
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.dto.UserPlaylistDto
import org.tegro.yamp.domain.media.Loudness
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef

/** Yandex responses (shaped like the live API, with its extra fields) → the neutral models (phase 3, step 3). */
class YandexMappersTest {

    /** The app's own JSON settings (NetworkModule). */
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private fun text(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("yandex/$name")) { "fixture $name missing" }.readText()

    @Test
    fun aTrackKeepsWhatScreensAndThePlayerUse() {
        val track = json.decodeFromString<TrackDetailsDto>(text("track.json")).toTrack()

        assertEquals(TrackRef("yandex:track:58142731"), track.ref)
        assertEquals("Storytime", track.title)
        assertEquals(330_810L, track.durationMs)
        assertTrue(track.explicit)
        assertEquals(Loudness(-9.42, 0.52), track.loudness)
        assertEquals(MediaRef("yandex", "album", "7536452"), track.album?.ref)
        assertEquals("Imaginaerum", track.album?.title)
        assertEquals(
            "https://avatars.yandex.net/get-music-content/2441215/cd.a.7536452-1/400x400",
            track.cover?.url(400)
        )
    }

    @Test
    fun aShownTrackTurnsBackIntoTheServiceTrackItCameFrom() {
        val service = json.decodeFromString<TrackDetailsDto>(text("track.json"))
        val back = service.toTrack().toYandexTrack()

        // Every field the data layer reads survives the round trip (no lookup table behind it)
        assertEquals(service.idString, back.idString)
        assertEquals(service.title, back.title)
        assertEquals(service.artists.map { it.idString to it.name }, back.artists.map { it.idString to it.name })
        assertEquals(service.coverUri, back.coverUri)
        assertEquals(service.durationMs, back.durationMs)
        assertEquals(service.albumId, back.albumId)
        assertEquals(service.albums.first().title, back.albums.first().title)
        assertEquals(service.r128, back.r128)
        assertEquals(service.isExplicit, back.isExplicit)
        assertFalse(back.isRadio)
        assertEquals(service.toTrack(), back.toTrack())
    }

    @Test
    fun aStationStaysAStationOnTheWayBack() {
        val station = TrackDetailsDto(id = JsonPrimitive("radio_7"), title = "Europa Plus", isRadio = true)

        assertTrue(station.toTrack().toYandexTrack().isRadio)
    }

    @Test
    fun anArtistWithoutAnIdIsNamedButNotAddressable() {
        val track = json.decodeFromString<TrackDetailsDto>(text("track.json")).toTrack()

        assertEquals(listOf("Nightwish", "Guest Voice"), track.artists.map { it.name })
        assertEquals(MediaRef("yandex", "artist", "61094"), track.artists[0].ref)
        assertNull(track.artists[1].ref)
    }

    @Test
    fun anAlbumPlaysItsDiscsInOrder() {
        val album = checkNotNull(json.decodeFromString<AlbumDto>(text("album-with-tracks.json")).toAlbum())

        assertEquals(MediaRef("yandex", "album", "7536452"), album.ref)
        assertEquals(2011, album.year)
        assertEquals(
            listOf("yandex:track:58142729", "yandex:track:58142730", "yandex:track:58142731:7536452"),
            album.tracks.map { it.ref.value }
        )
        assertFalse(album.tracks.any { it.explicit })
    }

    @Test
    fun aPlaylistIsAddressedByItsOwnerAndKind() {
        val playlist = checkNotNull(json.decodeFromString<UserPlaylistDto>(text("playlist.json")).toPlaylist())

        assertEquals(MediaRef("yandex", "playlist", "123456789:1003"), playlist.ref)
        assertEquals("Дорога", playlist.title)
        assertTrue(playlist.isPublic)
        assertEquals(2, playlist.trackCount)
        // The mosaic's first tile stands for the cover; an entry without its track is left out
        assertEquals("https://avatars.yandex.net/get-music-content/1/a/200x200", playlist.cover?.url(200))
        assertEquals(listOf("yandex:track:58142730"), playlist.tracks.map { it.ref.value })
    }

    @Test
    fun anArtistPageHasItsCover() {
        val artist = checkNotNull(json.decodeFromString<ArtistDto>(text("artist.json")).toArtist())

        assertEquals(MediaRef("yandex", "artist", "61094"), artist.ref)
        assertEquals(
            "https://avatars.yandex.net/get-music-content/49707/ab12.a.61094-1/1000x1000",
            artist.cover?.url(1000)
        )
    }

    @Test
    fun aRadioStationIsNotAYandexTrack() {
        val station = TrackDetailsDto(id = JsonPrimitive("radio_42"), title = "Rock FM", isRadio = true)

        assertEquals("radio", station.toTrack().ref.provider)
    }

    @Test
    fun aCoverWithoutTheSizePlaceholderIsUsedAsItIs() {
        val bundled = "android.resource://org.tegro.music.yamp/2131230900"

        assertEquals(bundled, YandexCover(bundled).url(200))
        assertNull(YandexCover.of(" "))
    }
}
