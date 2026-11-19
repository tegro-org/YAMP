package org.tegro.yamp.provider.jellyfin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.domain.media.FolderLevel
import org.tegro.yamp.domain.provider.Outcome
import java.io.IOException

/**
 * The folder tree (Phase 4b) against JSON recorded from the dev server (scripts/jellyfin-dev; ids scrubbed): the music
 * library's root with a hand-named Cyrillic folder, its level (a subfolder and a loose track), the whole folder's tracks
 * in tree order, paging, the cache while the server is away, and "Recently added".
 */
class JellyfinFoldersTest {
    private val serverId = "0123456789abcdef0123456789abcdef"
    private val mappers = JellyfinMappers(JellyfinRefs(serverId), "http://10.0.2.2:8096/")
    private val json = JellyfinApiFactory.json
    private fun result(name: String) = json.decodeFromString<ItemsResultDto>(resource(name))

    private val musicView = "0000000000000000000000000000b00b"
    private val tree = "0000000000000000000000000000b005"

    private class MapCache : JellyfinListCache {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, json: String) {
            map[key] = json
        }
    }

    private inner class RecordedApi : FakeJellyfinApi() {
        var down: Throwable? = null
        var views = result("user-views.json")
        val calls = mutableListOf<String>()

        override suspend fun userViews(userId: String): ItemsResultDto {
            down?.let { throw it }
            return views
        }

        override suspend fun items(
            userId: String, includeItemTypes: String?, parentId: String?, recursive: Boolean?, sortBy: String?,
            startIndex: Int?, limit: Int?, fields: String?, albumArtistIds: String?, artistIds: String?, genreIds: String?,
            sortOrder: String?, filters: String?, searchTerm: String?
        ): ItemsResultDto {
            down?.let { throw it }
            calls += "items:$includeItemTypes:$parentId:$recursive:$startIndex:$limit:$fields"
            val all = when {
                includeItemTypes == "MusicAlbum" -> result("recent-albums.json")
                recursive == true && parentId == tree -> result("folder-tracks.json")
                parentId == musicView -> result("folder-root.json")
                parentId == tree -> result("folder-level.json")
                else -> ItemsResultDto()
            }
            val from = startIndex ?: 0
            val to = if (limit == null) all.items.size else (from + limit).coerceAtMost(all.items.size)
            return ItemsResultDto(all.items.subList(from.coerceAtMost(all.items.size), to), all.items.size, from)
        }
    }

    private val api = RecordedApi()
    private val cache = MapCache()

    private fun library(pageSize: Int = JellyfinLibrary.PAGE_SIZE) = JellyfinLibrary(api, "u1", mappers, cache, pageSize)

    private fun FolderLevel.names() = folders.map { it.title } to tracks.map { it.title }

    @Test
    fun rootSkipsTheOnlyMusicLibraryAndSortsLatinBeforeCyrillic() = runTest {
        val root = (library().folderRoot() as Outcome.Ok).value
        assertEquals(listOf("Kevin MacLeod", "Сборники - разное") to emptyList<String>(), root.names())
        val folder = root.folders[1]
        assertEquals("jellyfin@$serverId:folder:$tree", folder.ref.value)
        assertEquals(2, folder.childCount)
        assertEquals(3, folder.trackCount)
        assertNull("a folder without its own image gets the placeholder", folder.cover)
        assertTrue("the Kevin MacLeod folder has an image", root.folders[0].cover != null)
        assertTrue(api.calls.single().startsWith("items:null:$musicView:null:0:100:ChildCount,RecursiveItemCount"))
    }

    @Test
    fun withSeveralMusicLibrariesTheLibrariesAreTheTop() = runTest {
        val music = result("user-views.json").items.first { it.collectionType == "music" }
        api.views = ItemsResultDto(listOf(music, music.copy(id = "0000000000000000000000000000c001", name = "Audiobooks")), 2)
        val root = (library().folderRoot() as Outcome.Ok).value
        assertEquals(listOf("Audiobooks", "Music"), root.folders.map { it.title })
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun levelListsSubfoldersThenLooseTracks() = runTest {
        val level = (library().folderLevel(tree) as Outcome.Ok).value
        assertEquals(listOf("Дорога") to listOf("Carefree"), level.names())
        assertEquals(2, level.folders.single().childCount)
    }

    @Test
    fun levelIsPagedToTheEnd() = runTest {
        val level = (library(pageSize = 1).folderLevel(tree) as Outcome.Ok).value
        assertEquals(listOf("Дорога") to listOf("Carefree"), level.names())
        assertEquals(2, api.calls.size)
    }

    @Test
    fun folderTracksFollowTheTreeSubfoldersFirst() = runTest {
        val tracks = (library().folderTracks(tree) as Outcome.Ok).value
        assertEquals(listOf("Sneaky Snitch", "Wallpaper", "Carefree"), tracks.map { it.title })
        assertTrue(api.calls.single().contains(":Audio:$tree:true:0:500:Path"))
    }

    @Test
    fun treeOrderNaturalAcrossFolders() {
        fun item(path: String, track: Int? = null) = BaseItemDto(id = path, path = path, indexNumber = track, type = "Audio")
        val sorted = listOf(
            item("/m/A/Disc 10/01 x.mp3", 1), item("/m/A/loose.mp3"), item("/m/A/Disc 2/02 y.mp3", 2),
            item("/m/A/Disc 2/01 z.mp3", 1), item("/m/Б/01 w.mp3", 1)
        ).sortedWith(JellyfinLibrary.TREE_ORDER).map { it.path }
        assertEquals(
            listOf("/m/A/Disc 2/01 z.mp3", "/m/A/Disc 2/02 y.mp3", "/m/A/Disc 10/01 x.mp3", "/m/A/loose.mp3", "/m/Б/01 w.mp3"),
            sorted
        )
    }

    @Test
    fun aVisitedLevelIsServedWhileTheServerIsAway() = runTest {
        val lib = library()
        lib.folderLevel(tree)
        lib.folderTracks(tree)
        api.down = IOException("unreachable")
        assertEquals(listOf("Дорога") to listOf("Carefree"), (lib.folderLevel(tree) as Outcome.Ok).value.names())
        assertEquals(3, (lib.folderTracks(tree) as Outcome.Ok).value.size)
        assertEquals(false, lib.reachable.value)
        assertTrue(lib.folderLevel("0000000000000000000000000000ffff") is Outcome.Err)
    }

    @Test
    fun recentAlbumsNewestFirst() = runTest {
        val albums = (library().recentAlbums(12) as Outcome.Ok).value
        assertEquals(listOf("Дорога", "YAMP Test FLAC", "YAMP Test MP3"), albums.map { it.title })
        assertEquals(2, albums.first().trackCount)
    }
}
