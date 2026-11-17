package org.tegro.yamp.presentation.common

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.data.dto.ArtistDto
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.dto.UserPlaylistDto
import org.tegro.yamp.data.provider.yandex.toPlaylist
import org.tegro.yamp.data.provider.yandex.toTrack

/** The screens read the same values off a neutral track as they did off the service's track (phase 3 steps 12–13). */
class TrackUiTest {

    private fun dto(id: String, isRadio: Boolean = false, cover: String? = "avatars.yandex.net/get-music-content/1/%%") = TrackDetailsDto(
        id = JsonPrimitive(id), title = "Song", artists = listOf(ArtistDto(name = "A"), ArtistDto(name = "B")),
        coverUri = cover, durationMs = 1000, isRadio = isRadio
    )

    @Test
    fun aTrackReadsAsTheServiceTrackDid() {
        val service = dto("12345:678")
        val track = service.toTrack()
        assertEquals(service.idString, track.id)
        assertEquals(service.coverUri, track.coverUri)
        assertEquals(service.artists.joinToString { it.name.orEmpty() }, track.artistNames)
        assertFalse(track.isRadio)
    }

    @Test
    fun anFmStationIsRadioWithItsStationId() {
        val track = dto("station-7", isRadio = true).toTrack()
        assertTrue(track.isRadio)
        assertEquals("station-7", track.id)
    }

    @Test
    fun aTrackWithoutAnIdOrCoverDoesNotFail() {
        val track = dto("", cover = null).toTrack()
        assertEquals("", track.id)
        assertNull(track.coverUri)
    }

    @Test
    fun aPlaylistKeepsTheServiceCover() {
        val service = UserPlaylistDto(kind = JsonPrimitive(1003), title = "В дорогу", trackCount = 2, ogImage = "avatars.yandex.net/x/%%")
        assertEquals(service.coverUri, service.toPlaylist()?.coverUri)
    }
}
