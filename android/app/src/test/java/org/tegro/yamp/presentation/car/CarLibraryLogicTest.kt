package org.tegro.yamp.presentation.car

import org.junit.Assert.assertEquals
import org.junit.Test
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.Folder
import org.tegro.yamp.domain.media.Playlist
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.presentation.catalog.AccountLibrary
import org.tegro.yamp.presentation.catalog.LibraryRow
import org.tegro.yamp.presentation.catalog.libraryRows
import org.tegro.yamp.presentation.catalog.librarySections
import org.tegro.yamp.presentation.catalog.showsAccountHeaders
import org.tegro.yamp.presentation.media.MediaMenuRow

/** Gap (f) and (e): the car Library's tabs and A–Z rail; the Library's rows per account. */
class CarLibraryLogicTest {

    @Test
    fun tabsPerService() {
        assertEquals(listOf(CarTab.FOLDERS, CarTab.ALBUMS, CarTab.PLAYLISTS, CarTab.LIKED), carTabs(Provider.JELLYFIN))
        assertEquals(listOf(CarTab.ALBUMS, CarTab.PLAYLISTS, CarTab.LIKED), carTabs(Provider.YANDEX))
    }

    private fun album(title: String) = CarTile.OfAlbum(Album(MediaRef("yandex", "album", title), title, emptyList(), null, null, 1))
    private fun folder(title: String, pinned: Boolean) = CarTile.OfFolder(Folder(MediaRef("jellyfin@s", "folder", title), title, null), pinned)

    @Test
    fun theRailHasTheLettersPresentInGridOrderWithoutPins() {
        val tiles = listOf(folder("RC - Sorted", true), folder("Pink Floyd", false), folder("Queen", false), folder("queen live", false), folder("Чайф", false))
        assertEquals(listOf('P', 'Q', 'Ч'), railLetters(tiles))
        assertEquals(2, firstIndexOf(tiles, 'Q'))
        assertEquals(-1, firstIndexOf(tiles, 'R'))
        assertEquals(listOf('A', 'Д'), railLetters(listOf(album("Abbey Road"), album("Animals"), album("Дикие травы"))))
    }

    @Test
    fun libraryRowsPerAccountAndHeadersOnlyWithSeveral() {
        assertEquals(
            listOf(LibraryRow.LIKED, LibraryRow.PLAYLISTS, LibraryRow.ALBUMS, LibraryRow.ARTISTS, LibraryRow.DOWNLOADED),
            libraryRows(Provider.YANDEX)
        )
        assertEquals(LibraryRow.FOLDERS, libraryRows(Provider.JELLYFIN).last())
        assertEquals(6, libraryRows(Provider.JELLYFIN).size)
        val yandex = AccountLibrary(AccountId.yandex("1"), "Anna", "Yandex")
        val jellyfin = AccountLibrary(AccountId.jellyfin("srv", "u"), "yamp-dev", "Home Media")
        assertEquals(false, showsAccountHeaders(listOf(jellyfin)))
        assertEquals(false, showsAccountHeaders(listOf(yandex)))
        assertEquals(true, showsAccountHeaders(listOf(yandex, jellyfin)))
        assertEquals(LibraryRow.FOLDERS, jellyfin.rows.last())
    }

    @Test
    fun everySignedInAccountHasASectionAndOnlyTheContextOneIsMarked() {
        val anna = AccountLibrary(AccountId.yandex("1"), "Anna", "Yandex")
        val boris = AccountLibrary(AccountId.yandex("2"), "Boris", "Yandex")
        val home = AccountLibrary(AccountId.jellyfin("srv", "u"), "yamp-dev", "Home Media")
        val known = listOf(boris.copy(counts = mapOf(LibraryRow.LIKED to 5)))
        val sections = librarySections(listOf(anna, boris), AccountId.yandex("2"), listOf(home), known)
        assertEquals(listOf(anna.account, boris.account, home.account), sections.map { it.account })
        assertEquals(listOf(false, true, false), sections.map { it.inContext })
        assertEquals(mapOf(LibraryRow.LIKED to 5), sections[1].counts)
        assertEquals(true, showsAccountHeaders(sections))
        // Two Yandex accounts and nothing else: still a header each; one account in all: none
        assertEquals(true, showsAccountHeaders(librarySections(listOf(anna, boris), anna.account, emptyList())))
        assertEquals(false, showsAccountHeaders(librarySections(listOf(anna), anna.account, emptyList())))
        assertEquals(false, showsAccountHeaders(librarySections(emptyList(), null, listOf(home))))
    }

    @Test
    fun aTapOpensAnAlbumOrAFolderAndPlaysTheRest() {
        assertEquals(CarTap.OPEN_ALBUM, carTap(album("Abbey Road")))
        assertEquals(CarTap.OPEN_FOLDER, carTap(folder("Queen", false)))
        assertEquals(CarTap.PLAY, carTap(CarTile.OfPlaylist(Playlist(MediaRef("yandex", "playlist", "1"), "Road", 3, null, isPublic = false))))
    }

    @Test
    fun theAlbumPageOffersRadioOnlyForYandexAndQueueActionsOnlyWhenTheyFit() {
        assertEquals(
            listOf(MediaMenuRow.PLAY_NOW, MediaMenuRow.SHUFFLE, MediaMenuRow.PLAY_NEXT, MediaMenuRow.ADD_TO_END, MediaMenuRow.ADD_TO_QUEUE, MediaMenuRow.START_RADIO),
            carAlbumButtons(Provider.YANDEX, fitsActiveQueue = true)
        )
        assertEquals(
            listOf(MediaMenuRow.PLAY_NOW, MediaMenuRow.SHUFFLE, MediaMenuRow.PLAY_NEXT, MediaMenuRow.ADD_TO_END, MediaMenuRow.ADD_TO_QUEUE),
            carAlbumButtons(Provider.JELLYFIN, fitsActiveQueue = true)
        )
        assertEquals(listOf(MediaMenuRow.PLAY_NOW, MediaMenuRow.SHUFFLE, MediaMenuRow.ADD_TO_QUEUE), carAlbumButtons(Provider.JELLYFIN, fitsActiveQueue = false))
    }
}
