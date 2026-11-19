package org.tegro.yamp.presentation.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.tegro.yamp.data.folders.PinnedFolder
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.media.Folder
import org.tegro.yamp.domain.media.FolderLevel
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef
import org.tegro.yamp.presentation.catalog.FolderPage

/** Phase 4b on the device: the placeholder's monogram and tint, pins and the filter on a level, breadcrumbs. */
class FolderLogicTest {

    @Test
    fun monogramsAsTheCanvasDrawsThem() {
        assertEquals("МХ", FolderLook.monogram("Мельница & Хелависа - альбомы"))
        assertEquals("RC", FolderLook.monogram("RC - Sorted"))
        assertEquals("Д", FolderLook.monogram("Детские"))
        assertEquals("M", FolderLook.monogram("Metallica (mp3)"))
        assertEquals("F", FolderLook.monogram("Fleur - Дискография (2000-2026)"))
        assertEquals("П", FolderLook.monogram("2003 — Перевал"))
        assertEquals("АИ", FolderLook.monogram("Аквариум и БГ (mp3)"))
        assertEquals("1", FolderLook.monogram("1999"))
    }

    @Test
    fun theSameNameAlwaysGetsTheSameTint() {
        assertEquals(FolderLook.tintOf("RC - Sorted"), FolderLook.tintOf("RC - Sorted"))
        val tints = listOf("Pink Floyd", "Queen", "Сплин", "Чайф", "Детские", "Louna", "RC - Sorted").map(FolderLook::tintOf).toSet()
        assertNotEquals(1, tints.size)
    }

    private val ref = { id: String -> MediaRef("jellyfin@srv", "folder", id) }
    private fun folder(id: String, name: String) = Folder(ref(id), name, null)
    private fun track(id: String, name: String) = Track(TrackRef.of("jellyfin@srv", id), name, emptyList(), null, null, null, explicit = false)
    private val level = FolderLevel(
        listOf(folder("a", "Pink Floyd"), folder("b", "Queen"), folder("c", "Мельница & Хелависа - альбомы")),
        listOf(track("t", "Белая кобыла (демо)"))
    )
    private val pins = listOf(PinnedFolder("c", "Мельница & Хелависа - альбомы"), PinnedFolder("deep", "2009 — Дикие травы"))

    @Test
    fun theRootListsEveryPinTheLevelOnlyItsOwn() {
        val root = FolderLevelView.of(level, pins, root = true, filter = "") { Folder(ref(it.id), it.title, null) }
        assertEquals(listOf("Мельница & Хелависа - альбомы", "2009 — Дикие травы"), root.pinned.map { it.title })
        assertEquals(listOf("Pink Floyd", "Queen"), root.others.map { it.title })
        val inside = FolderLevelView.of(level, pins, root = false, filter = "") { Folder(ref(it.id), it.title, null) }
        assertEquals(listOf("Мельница & Хелависа - альбомы"), inside.pinned.map { it.title })
    }

    @Test
    fun theFilterSearchesThisLevelCaseInsensitive() {
        val view = FolderLevelView.of(level, pins, root = false, filter = "МЕЛ") { Folder(ref(it.id), it.title, null) }
        assertEquals(listOf("Мельница & Хелависа - альбомы"), view.pinned.map { it.title })
        assertEquals(emptyList<Folder>(), view.others)
        assertEquals(emptyList<Track>(), view.tracks)
        val tracks = FolderLevelView.of(level, emptyList(), root = false, filter = "кобыла") { Folder(ref(it.id), it.title, null) }
        assertEquals(1, tracks.tracks.size)
    }

    @Test
    fun breadcrumbsGoBackToTheirLevel() {
        val account = AccountId.jellyfin("srv", "u1")
        val root = FolderPage(null, "Folders", account)
        val melnitsa = root.child("c", "Мельница")
        val album = melnitsa.child("d", "Дикие травы")
        assertEquals(listOf(FolderPage.Crumb(null, "Folders"), FolderPage.Crumb("c", "Мельница")), album.path)
        assertEquals(melnitsa, album.up(album.path[1]))
        assertEquals(root, album.up(album.path[0]))
        assertEquals("jellyfin@srv:folder:d", album.refOf("d").value)
    }
}
