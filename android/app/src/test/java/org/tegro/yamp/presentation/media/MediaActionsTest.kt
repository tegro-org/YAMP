package org.tegro.yamp.presentation.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.AlbumKind
import org.tegro.yamp.domain.media.Artist
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.provider.ArtistPage
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef
import org.tegro.yamp.presentation.queues.QueueSwitcherRequest

class MediaActionsTest {
    private val yandexAlbum = MediaTarget(MediaRef(MediaRef.PROVIDER_YANDEX, "album", "1"), "Дикие травы", "Album", null, isArtist = false)
    private val jellyfinAlbum = MediaTarget(MediaRef("jellyfin@srv", "album", "a1"), "Test", "Album", null, isArtist = false)

    private fun album(id: String, year: Int?, tracks: Int, kind: AlbumKind = AlbumKind.ALBUM) =
        Album(MediaRef(MediaRef.PROVIDER_YANDEX, "album", id), "A$id", emptyList(), null, year, tracks, kind = kind)

    private fun track(id: String) = Track(TrackRef.yandex(id), id, emptyList(), null, null, null, explicit = false)

    private val page = ArtistPage(
        artist = Artist(MediaRef(MediaRef.PROVIDER_YANDEX, "artist", "9"), "Мельница", null),
        popularTracks = List(20) { track("p$it") },
        albums = listOf(
            album("3", 2012, 13),
            album("1", 2005, 12),
            album("s", 2010, 1, AlbumKind.SINGLE),
            album("x", null, 9),
            album("2", 2009, 12)
        ),
        alsoAlbums = listOf(album("c", 2014, 20, AlbumKind.COMPILATION))
    )

    @Test
    fun `a Yandex item gets every row, a Jellyfin one no radio`() {
        assertEquals(
            listOf(MediaMenuRow.PLAY_NOW, MediaMenuRow.PLAY_NEXT, MediaMenuRow.ADD_TO_END, MediaMenuRow.ADD_TO_QUEUE, MediaMenuRow.START_RADIO),
            mediaMenuRows(yandexAlbum, fitsActiveQueue = true)
        )
        assertEquals(
            listOf(MediaMenuRow.PLAY_NOW, MediaMenuRow.PLAY_NEXT, MediaMenuRow.ADD_TO_END, MediaMenuRow.ADD_TO_QUEUE),
            mediaMenuRows(jellyfinAlbum, fitsActiveQueue = true)
        )
    }

    @Test
    fun `when the active queue can't take the tracks only play now and add to queue remain (D84)`() {
        assertEquals(listOf(MediaMenuRow.PLAY_NOW, MediaMenuRow.ADD_TO_QUEUE), mediaMenuRows(jellyfinAlbum, fitsActiveQueue = false))
    }

    @Test
    fun `all albums are proper albums oldest first, a release without a year last`() {
        assertEquals(listOf("1", "2", "3", "x"), artistReleases(page, withExtras = false).map { it.ref.id })
    }

    @Test
    fun `singles and compilations join in release order when asked`() {
        assertEquals(listOf("1", "2", "s", "3", "c", "x"), artistReleases(page, withExtras = true).map { it.ref.id })
    }

    @Test
    fun `the dialog counts albums, releases and popular tracks`() {
        val counts = artistCounts(page)
        assertEquals(4, counts.albums)
        assertEquals(12 + 12 + 13 + 9, counts.albumTracks)
        assertEquals(6, counts.releases)
        assertEquals(12 + 12 + 13 + 9 + 1 + 20, counts.releaseTracks)
        assertEquals(20, counts.popular)
        assertTrue(counts.hasExtras)
        assertFalse(artistCounts(page.copy(alsoAlbums = emptyList(), albums = page.albums.filter { it.kind == AlbumKind.ALBUM })).hasExtras)
    }

    @Test
    fun `the queue picker offers only queues of the album's account (D84)`() {
        val jellyfinTracks = listOf(Track(TrackRef.of("jellyfin@srv", "t1"), "t1", emptyList(), null, null, null, explicit = false))
        val request = QueueSwitcherRequest.AddTo(jellyfinTracks, "Test")
        assertTrue(request.fits(AccountId.jellyfin("srv", "u1").value))
        assertFalse(request.fits(AccountId.yandex("1").value))
        assertFalse(request.fits(AccountId.jellyfin("other", "u1").value))
    }
}
