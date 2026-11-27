package org.tegro.yamp.data.provider.local

import android.app.Application
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.folders.PinnedFolder
import org.tegro.yamp.data.player.AccountBrowseTree
import org.tegro.yamp.data.player.BrowseLabels
import org.tegro.yamp.data.player.BrowsePlayer
import org.tegro.yamp.data.provider.local.db.LocalDatabase
import org.tegro.yamp.data.provider.local.db.MoveEntity
import org.tegro.yamp.data.provider.local.db.SourceEntity
import org.tegro.yamp.data.provider.local.scan.LocalScanStatus
import org.tegro.yamp.data.provider.local.scan.LocalScanner
import org.tegro.yamp.data.provider.local.scan.TagRead
import org.tegro.yamp.data.provider.local.scan.TagReader
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.account.ProviderAccount
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.provider.MidTrackAction
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.ProviderError
import org.tegro.yamp.domain.provider.getOrNull
import org.tegro.yamp.domain.queue.TrackRef
import org.tegro.yamp.provider.local.LocalRefs
import org.tegro.yamp.provider.local.TrackTags
import java.io.File

/**
 * "This device" as a provider (docs/phase-4d-local-files.md step 5) over a real index: two sources on two volumes,
 * scanned from a temporary tree with fixed tags. Its pages (albums per volume, artists from tags, folders with the
 * volumes at the root, natural order), search folding, the resolver (file uri, aliases, an absent volume) and the
 * Android Auto tree of the local account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalProviderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: LocalDatabase
    private val roots = mutableMapOf<String, File>()
    private val volumes = Volumes()
    private val tags = mutableMapOf<String, TrackTags>()
    private lateinit var library: LocalLibrary
    private lateinit var provider: LocalProvider
    private lateinit var resolver: LocalStreamResolver

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).allowMainThreadQueries().build()
        roots[SD] = tmp.newFolder(SD)
        roots[LocalVolume.PRIMARY] = tmp.newFolder(LocalVolume.PRIMARY)
        album(SD, "Music/Hollow Pines/2019 - Copper Sky", "Copper Sky", "Hollow Pines", 2)
        album(LocalVolume.PRIMARY, "Music/Copper Sky", "Copper Sky", "Hollow Pines", 2)
        write(SD, "Music/YouTube/01 - Lumen Lane - Night Drive (Official Audio).m4a")
        write(SD, "Music/YouTube/Field Notes - Morning Tide.opus")
        write(SD, "Music/Book/2 Chapter.mp3", TrackTags(title = "Chapter 2", album = "Book", artist = "Narrator"))
        write(SD, "Music/Book/10 Chapter.mp3", TrackTags(title = "Chapter 10", album = "Book", artist = "Narrator"))
        write(LocalVolume.PRIMARY, "Music/Ёлка.mp3", TrackTags(title = "Ёлка", artist = "Северный ветер"))
        db.sources().insert(SourceEntity(volumeId = SD, rootRel = "Music", treeUri = null, addedAt = 1))
        db.sources().insert(SourceEntity(volumeId = LocalVolume.PRIMARY, rootRel = "Music", treeUri = null, addedAt = 2))
        val fsFor: (SourceEntity) -> SourceFs? = { source -> roots[source.volumeId]?.let(::FileSourceFs) }
        LocalScanner(db, volumes, fsFor, Reader(), LocalScanStatus()).scanAll()
        library = LocalLibrary(db)
        resolver = LocalStreamResolver(library, volumes, fsFor)
        provider = LocalProvider(library, volumes, resolver, { LocalLabels("Unknown artist", "Phone") })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun theSameAlbumOnTwoVolumesIsTwoAlbumsEachNamingItsVolume() = runBlocking {
        val albums = provider.browse.albums(null).getOrNull()!!.items
        val copper = albums.filter { it.title == "Copper Sky" }
        assertEquals("per volume (decided after the boards)", 2, copper.size)
        assertEquals(setOf("SD card", "Phone"), copper.map { it.qualifier }.toSet())
        assertNull("an album on one volume needs no qualifier", albums.first { it.title == "Book" }.qualifier)
        val page = provider.catalog.album(copper.first().ref).getOrNull()!!
        assertEquals(listOf("Track 1", "Track 2"), page.tracks.map { it.title })
    }

    @Test
    fun anUntaggedFileIsTitledFromItsNameUnderUnknownArtistAndItsFolderAlbum() = runBlocking {
        val level = provider.folders.level(LocalRefs.folder(SD, "Music/YouTube")).getOrNull()!!
        assertEquals(listOf("Field Notes - Morning Tide", "Lumen Lane - Night Drive (Official Audio)"), level.tracks.map { it.title }.sorted())
        val untagged = level.tracks.first { it.title.startsWith("Lumen") }
        assertEquals(listOf("Unknown artist"), untagged.artists.map { it.name })
        assertEquals("YouTube", untagged.album?.title)
        val artists = provider.browse.artists(null).getOrNull()!!.items.map { it.name }
        assertEquals("untagged files are last", "Unknown artist", artists.last())
        assertTrue("Северный ветер" in artists)
    }

    @Test
    fun theFoldersRootIsTheVolumesAndAVolumeOpensItsOneSource() = runBlocking {
        val root = provider.folders.root().getOrNull()!!
        assertEquals(listOf("SD card", "Phone"), root.folders.map { it.title })
        assertEquals(LocalRefs.folder(SD, ""), root.folders.first().ref)
        val sd = provider.folders.level(root.folders.first().ref).getOrNull()!!
        assertEquals("the source's folder (Music) opens at once", listOf("Book", "Hollow Pines", "YouTube"), sd.folders.map { it.title })
        // Natural order: 2 before 10, whatever the tags say
        val book = provider.folders.tracks(LocalRefs.folder(SD, "Music/Book")).getOrNull()!!
        assertEquals(listOf("Chapter 2", "Chapter 10"), book.map { it.title })
    }

    @Test
    fun anAbsentVolumeStaysListedUnavailableAndItsTracksDoNotResolve() = runBlocking {
        volumes.absent += SD
        val root = provider.folders.root().getOrNull()!!
        assertEquals(listOf(false, true), root.folders.map { it.available })
        val track = provider.folders.tracks(LocalRefs.folder(SD, "Music/Book")).getOrNull()!!.first()
        assertFalse(track.available)
        assertEquals(Outcome.Err(ProviderError.Unavailable(LocalStreamResolver.REASON_VOLUME)), resolver.resolve(track.ref))
        assertNull(resolver.cached(track.ref))
        assertFalse(resolver.isAvailable(track.ref))
        assertEquals("a file that stops playing is gone, not a link to renew", MidTrackAction.UNAVAILABLE, resolver.midTrackAction(track.ref, null))
        assertTrue("its albums are listed too", provider.browse.albums(null).getOrNull()!!.items.any { !it.available && it.title == "Book" })
    }

    @Test
    fun aTrackResolvesToItsFileSeekableAndUncachedAndAMovedOneFollowsItsAlias() = runBlocking {
        val ref = TrackRef(LocalRefs.track(SD, "Music/Book/2 Chapter.mp3").value)
        val old = LocalRefs.track(SD, "Music/Old/2 Chapter.mp3").value
        db.index().upsertMove(MoveEntity(old, ref.value, 0))
        val stream = (resolver.resolve(ref) as Outcome.Ok).value
        assertEquals(Uri.fromFile(File(roots.getValue(SD), "Music/Book/2 Chapter.mp3")).toString(), stream.uri)
        assertTrue(stream.localFile)
        assertNull("never in the stream cache", stream.cacheKey)
        assertFalse(stream.progressive)
        assertEquals(1000L, stream.durationMs)
        assertEquals("the snapshot is in memory now: no disk", stream, resolver.cached(ref))
        assertEquals(stream.uri, (resolver.resolve(TrackRef(old)) as Outcome.Ok).value.uri)
        val gone = TrackRef(LocalRefs.track(SD, "Music/x.ape").value)
        assertEquals(Outcome.Err(ProviderError.Unavailable(LocalStreamResolver.REASON_FORMAT)), resolver.resolve(gone))
    }

    @Test
    fun searchFoldsCaseAndYo() = runBlocking {
        val found = provider.search.search("ЕЛКА").getOrNull()!!
        assertEquals(listOf("Ёлка"), found.tracks.map { it.title })
        assertEquals(listOf("Copper Sky", "Copper Sky"), provider.search.search("copper").getOrNull()!!.albums.map { it.title })
        assertEquals(listOf("Hollow Pines"), provider.search.search("hollow").getOrNull()!!.artists.map { it.name })
    }

    @Test
    fun favouritesAreTheLikedTracksAndNoPlaylistsExist() = runBlocking {
        val ref = LocalRefs.track(LocalVolume.PRIMARY, "Music/Ёлка.mp3").value
        // The toggle comes with Phase 5's Favorites capability: the row is written here as it will be
        db.openHelper.writableDatabase.execSQL("INSERT INTO favourites (ref, addedAt) VALUES (?, 1)", arrayOf(ref))
        assertEquals(listOf("Ёлка"), provider.library.likedTracks().getOrNull()!!.map { it.title })
        assertEquals(emptyList<Any>(), provider.library.playlists().getOrNull())
        assertEquals(0, provider.browse.counts().getOrNull()!!.playlists)
    }

    @Test
    fun androidAutoShowsTheLocalAccountWithFavouritesAlbumsFoldersAndAnAbsentVolumeDoesNotPlay() = runBlocking {
        val local = ProviderAccount(Provider.LOCAL, AccountId.local(), "This device", null, hasPlus = false)
        val played = mutableListOf<List<String>>()
        val tree = AccountBrowseTree(
            accounts = { listOf(local) },
            providers = { if (it == AccountId.local()) provider else null },
            pins = { emptyList<PinnedFolder>() },
            isDownloaded = { false },
            artwork = { url -> url?.let(Uri::parse) },
            labels = BrowseLabels("Wave", "", null, "Liked", "Favourites", "Playlists", "Albums", "Folders", "Downloaded", "Play all", "Not connected"),
            player = object : BrowsePlayer {
                override fun play(account: AccountId, tracks: List<Track>, startId: String?) {
                    played += tracks.map { it.title }
                }
                override fun hasWave(account: AccountId) = false
                override fun playWave(account: AccountId) = Unit
            }
        )
        assertEquals("no Playlists while there are none, no Downloaded", listOf("Favourites", "Albums", "Folders"), tree.top().map { it.mediaMetadata.title.toString() })
        volumes.absent += SD
        val foldersNode = AccountBrowseTree.node(AccountBrowseTree.FOLDERS, AccountId.local())
        val volumesShown = tree.children(foldersNode)!!
        assertEquals(listOf("SD card", "Phone"), volumesShown.map { it.mediaMetadata.title.toString() })
        assertEquals(listOf(false, true), volumesShown.map { it.mediaMetadata.isPlayable })
        assertEquals("Not connected", volumesShown.first().mediaMetadata.artist.toString())
        assertEquals(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, volumesShown.first().mediaMetadata.mediaType)
        // The phone's folder plays whole, in folder order
        tree.startAction(volumesShown[1].mediaId)!!.invoke()
        assertEquals(listOf("Track 1", "Track 2", "Ёлка"), played.single())
        assertTrue("AA ids hold no '|' from a path", volumesShown.all { it.mediaId.count { c -> c == '|' } == 3 })
    }

    private fun album(volume: String, dir: String, title: String, artist: String, count: Int) {
        for (n in 1..count) write(volume, "$dir/0$n - Track $n.mp3", TrackTags("Track $n", artist, artist, title, trackNo = n, year = 2019))
    }

    private fun write(volume: String, relPath: String, tags: TrackTags? = null) {
        val file = File(roots.getValue(volume), relPath)
        file.parentFile!!.mkdirs()
        file.writeText(relPath)
        tags?.let { this.tags[volume + "/" + relPath] = it }
    }

    private inner class Reader : TagReader {
        override fun read(fs: SourceFs, relPath: String): TagRead {
            val volume = roots.entries.first { (_, root) -> File(root, relPath).exists() && fs.uri(relPath).path == File(root, relPath).path }.key
            return TagRead.Ok(tags["$volume/$relPath"] ?: TrackTags(), 1000, embeddedArt = false, "mp3")
        }
    }

    private class Volumes : VolumeState {
        val absent = mutableSetOf<String>()
        override fun isMounted(volumeId: String) = volumeId !in absent
        override fun label(volumeId: String) = if (volumeId == SD) "SD card" else null
    }

    private companion object {
        const val SD = "6917-1912"
    }
}

/** Folder order: at every level the subfolders first, then the files, each in natural order. Pure. */
class FolderOrderTest {
    @Test
    fun subfoldersBeforeFilesAndNaturalNumbers() {
        val paths = listOf("a/10.mp3", "a/2.mp3", "a/b/1.mp3", "c.mp3", "a/B2/x.mp3", "a/b10/y.mp3")
        assertEquals(
            listOf("a/b/1.mp3", "a/B2/x.mp3", "a/b10/y.mp3", "a/2.mp3", "a/10.mp3", "c.mp3"),
            paths.sortedWith { x, y -> IndexSnapshot.comparePaths(x, y) }
        )
    }
}
