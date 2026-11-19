package org.tegro.yamp.data.player

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.folders.PinnedFolder
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.account.ProviderAccount
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.Cover
import org.tegro.yamp.domain.media.Folder
import org.tegro.yamp.domain.media.FolderLevel
import org.tegro.yamp.domain.media.Playlist
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.provider.Browse
import org.tegro.yamp.domain.provider.BrowseCounts
import org.tegro.yamp.domain.provider.Catalog
import org.tegro.yamp.domain.provider.ArtistPage
import org.tegro.yamp.domain.provider.Folders
import org.tegro.yamp.domain.provider.Library
import org.tegro.yamp.domain.provider.MusicProvider
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.Page
import org.tegro.yamp.domain.provider.ProviderError
import org.tegro.yamp.domain.provider.Search
import org.tegro.yamp.domain.provider.SearchResult
import org.tegro.yamp.domain.provider.StreamResolver
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef

/** Gap (d): the Android Auto tree per account — accounts, Jellyfin folders, picks, search and secret-free covers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AccountBrowseTreeTest {

    private val yandex = AccountId.yandex("1001")
    private val jellyfin = AccountId.jellyfin("srv1", "user1")
    private val jfProvider = "jellyfin@srv1"
    private val secretToken = "tok-SECRET-42"
    private val server = "http://jf.example:8096/"

    /** A Jellyfin cover as the mapper builds it: the server URL, never a token (that goes in a header). */
    private fun cover(id: String) = object : Cover {
        override val key = "${server}Items/$id/Images/Primary?fillWidth={px}&tag=t"
        override fun url(sizePx: Int) = key.replace("{px}", sizePx.toString())
    }

    private fun track(provider: String, id: String) =
        Track(TrackRef.of(provider, id), "T $id", emptyList(), null, 1000L, cover(id), explicit = false)

    private fun folder(id: String, title: String) = Folder(MediaRef(jfProvider, "folder", id), title, cover(id))

    private val levels = mapOf(
        null to FolderLevel(listOf(folder("f2", "Rock"), folder("f1", "Jazz")), listOf(track(jfProvider, "loose"))),
        "f1" to FolderLevel(listOf(folder("f11", "Miles")), listOf(track(jfProvider, "j1"), track(jfProvider, "j2"))),
        "f11" to FolderLevel(emptyList(), listOf(track(jfProvider, "m1")))
    )

    private inner class FakeJellyfin : MusicProvider {
        override val account = jellyfin
        override val streams: StreamResolver get() = error("not used")
        override val library = object : Library {
            override suspend fun likedTracks() = Outcome.Ok(listOf(track(jfProvider, "fav1"), track(jfProvider, "fav2")))
            override suspend fun playlists() = Outcome.Ok(listOf(Playlist(MediaRef(jfProvider, "playlist", "p1"), "Drive", 1, null, false)))
            override suspend fun playlistTracks(playlist: MediaRef) = Outcome.Ok(listOf(track(jfProvider, "pt1")))
        }
        override val browse = object : Browse {
            override suspend fun counts() = Outcome.Ok(BrowseCounts(1, 0, 0, 1))
            override suspend fun albums(cursor: Page.Cursor?) =
                Outcome.Ok(Page(listOf(Album(MediaRef(jfProvider, "album", "a1"), "Kind of Blue", emptyList(), cover("a1"), 1959, 2))))
            override suspend fun artists(cursor: Page.Cursor?) = Outcome.Ok(Page(emptyList<org.tegro.yamp.domain.media.Artist>()))
            override suspend fun genres(cursor: Page.Cursor?) = Outcome.Ok(Page(emptyList<org.tegro.yamp.domain.media.Genre>()))
            override suspend fun playlists(cursor: Page.Cursor?) = Outcome.Ok(Page(emptyList<Playlist>()))
            override suspend fun genreAlbums(genre: MediaRef, cursor: Page.Cursor?) = Outcome.Ok(Page(emptyList<Album>()))
        }
        override val catalog = object : Catalog {
            override suspend fun album(album: MediaRef) = Outcome.Ok(
                Album(album, "Kind of Blue", emptyList(), null, 1959, 2, tracks = listOf(track(jfProvider, "k1"), track(jfProvider, "k2")))
            )
            override suspend fun artist(artist: MediaRef): Outcome<ArtistPage> = Outcome.Err(ProviderError.NotFound)
        }
        override val folders = object : Folders {
            override suspend fun root() = Outcome.Ok(levels.getValue(null))
            override suspend fun level(folder: MediaRef) = levels[folder.id]?.let { Outcome.Ok(it) } ?: Outcome.Err(ProviderError.NotFound)
            override suspend fun tracks(folder: MediaRef): Outcome<List<Track>> = when (folder.id) {
                "f1" -> Outcome.Ok(listOf(track(jfProvider, "m1"), track(jfProvider, "j1"), track(jfProvider, "j2")))
                else -> Outcome.Ok(levels[folder.id]?.tracks.orEmpty())
            }
        }
        override val search = object : Search {
            override suspend fun search(text: String) = Outcome.Ok(SearchResult(tracks = listOf(track(jfProvider, "s1"))))
        }
    }

    private inner class FakeYandex : MusicProvider {
        override val account = yandex
        override val streams: StreamResolver get() = error("not used")
        override val library = object : Library {
            override suspend fun likedTracks() = Outcome.Ok(listOf(track("yandex", "y1"), track("yandex", "y2")))
            override suspend fun playlists() = Outcome.Ok(emptyList<Playlist>())
            override suspend fun playlistTracks(playlist: MediaRef) = Outcome.Ok(emptyList<Track>())
        }
        override val search = object : Search {
            override suspend fun search(text: String) = Outcome.Ok(SearchResult())
        }
    }

    private data class Played(val account: AccountId, val tracks: List<String>, val start: String?)

    private val played = mutableListOf<Played>()
    private var signedIn = listOf(
        ProviderAccount(Provider.YANDEX, yandex, "Arkady", null, hasPlus = true),
        ProviderAccount(Provider.JELLYFIN, jellyfin, "dev", null, hasPlus = false)
    )
    private var pins = listOf(PinnedFolder("f1", "Jazz", null))

    private val tree = AccountBrowseTree(
        accounts = { signedIn },
        providers = { id -> if (id == yandex) FakeYandex() else if (id == jellyfin) FakeJellyfin() else null },
        pins = { if (it == jellyfin) pins else emptyList() },
        isDownloaded = { it.id == "fav2" || it.id == "y1" },
        artwork = BrowseArtwork::uriFor,
        labels = BrowseLabels("Wave", "sub", null, "Liked", "Favourites", "Playlists", "Albums", "Folders", "Downloaded", "Play all"),
        player = object : BrowsePlayer {
            override fun play(account: AccountId, tracks: List<Track>, startId: String?) {
                played += Played(account, tracks.map { it.ref.id }, startId)
            }
            override fun hasWave(account: AccountId) = account == yandex
            override fun playWave(account: AccountId) {
                played += Played(account, listOf("wave"), null)
            }
        }
    )

    private fun node(kind: String, account: AccountId, arg: String = "") = AccountBrowseTree.node(kind, account, arg)

    @Test
    fun severalAccountsGetAFolderEach() {
        val top = tree.top()
        assertEquals(listOf(node("acc", yandex), node("acc", jellyfin)), top.map { it.mediaId })
        assertEquals(listOf("Arkady", "dev"), top.map { it.mediaMetadata.title.toString() })
        assertTrue(top.all { it.mediaMetadata.isBrowsable == true })
    }

    @Test
    fun theOnlyAccountsRowsSitAtTheRoot() {
        signedIn = signedIn.filter { it.provider == Provider.JELLYFIN }
        assertEquals(
            listOf("liked", "pls", "albs", "fds", "dl").map { node(it, jellyfin) },
            tree.top().map { it.mediaId }
        )
        signedIn = emptyList()
        assertTrue(tree.top().isEmpty())
    }

    @Test
    fun eachAccountHasItsServicesRows() = runTest {
        assertEquals(
            listOf("Favourites", "Playlists", "Albums", "Folders", "Downloaded"),
            tree.children(node("acc", jellyfin))!!.map { it.mediaMetadata.title.toString() }
        )
        val yandexRows = tree.children(node("acc", yandex))!!
        assertEquals(listOf("Wave", "Liked", "Playlists", "Albums", "Downloaded"), yandexRows.map { it.mediaMetadata.title.toString() })
        assertEquals(true, yandexRows.first().mediaMetadata.isPlayable)
    }

    @Test
    fun anAccountThatIsNotSignedInIsUnknown() = runTest {
        assertNull(tree.children(node("acc", AccountId.jellyfin("gone", "x"))))
        assertNull(tree.children("n|bogus"))
        assertNull(tree.startAction(node("liked", AccountId.jellyfin("gone", "x"))))
    }

    @Test
    fun foldersBrowseRecursivelyAndPlayWhole() = runTest {
        val root = tree.children(node("fds", jellyfin))!!
        // The pin first, then the other folders as the level lists them, then the loose track
        val f1 = node("fd", jellyfin, "$jfProvider:folder:f1")
        assertEquals(listOf(f1, node("fd", jellyfin, "$jfProvider:folder:f2")), root.take(2).map { it.mediaId })
        assertTrue(root.take(2).all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == true })
        assertEquals("T loose", root.last().mediaMetadata.title.toString())

        val inside = tree.children(f1)!!
        assertEquals("Play all", inside.first().mediaMetadata.title.toString())
        assertEquals(true, inside.first().mediaMetadata.isPlayable)
        val miles = inside[1]
        assertEquals("Miles", miles.mediaMetadata.title.toString())
        assertEquals(listOf("T j1", "T j2"), inside.drop(2).map { it.mediaMetadata.title.toString() })
        assertEquals(listOf("T m1"), tree.children(miles.mediaId)!!.drop(1).map { it.mediaMetadata.title.toString() })

        // "Play all" and the folder itself play every track under it
        tree.startAction(inside.first().mediaId)!!()
        tree.startAction(f1)!!()
        assertEquals(listOf("m1", "j1", "j2"), played[0].tracks)
        assertEquals(played[0], played[1])
        assertEquals(jellyfin, played[0].account)
    }

    @Test
    fun aTrackPlaysItsListFromItInItsAccount() = runTest {
        val loose = tree.children(node("fd", jellyfin, "$jfProvider:folder:f1"))!!.last()
        tree.startAction(loose.mediaId)!!()
        assertEquals(Played(jellyfin, listOf("j1", "j2"), "j2"), played.single())

        val liked = tree.children(node("liked", yandex))!!
        tree.startAction(liked[1].mediaId)!!()
        assertEquals(Played(yandex, listOf("y1", "y2"), "y2"), played[1])

        val album = tree.children(node("albs", jellyfin))!!.single()
        val albumTracks = tree.children(album.mediaId)!!
        tree.startAction(albumTracks.first().mediaId)!!()
        assertEquals(Played(jellyfin, listOf("k1", "k2"), "k1"), played[2])

        tree.startAction(node("wave", yandex))!!()
        assertEquals(listOf("wave"), played[3].tracks)
        assertNull("no wave for Jellyfin", tree.startAction(node("wave", jellyfin)))
    }

    @Test
    fun downloadedIsPerAccount() = runTest {
        assertEquals(listOf("T fav2"), tree.children(node("dl", jellyfin))!!.map { it.mediaMetadata.title.toString() })
        assertEquals(listOf("T y1"), tree.children(node("dl", yandex))!!.map { it.mediaMetadata.title.toString() })
    }

    @Test
    fun searchCoversJellyfin() = runTest {
        val found = tree.search("miles")
        assertEquals(listOf("T s1"), found.map { it.mediaMetadata.title.toString() })
        tree.startAction(found.single().mediaId)!!()
        assertEquals(Played(jellyfin, listOf("s1"), "s1"), played.single())
        tree.voiceSearch("miles")!!()
        assertEquals(jellyfin, played[1].account)
    }

    @Test
    fun artworkUrisAreContentUrisWithoutSecretsOrServer() = runTest {
        val items = tree.children(node("fds", jellyfin))!! + tree.children(node("liked", jellyfin))!! + tree.children(node("albs", jellyfin))!!
        val art = items.mapNotNull { it.mediaMetadata.artworkUri }
        assertTrue(art.size >= 4)
        for (uri in art) {
            assertEquals("content", uri.scheme)
            assertEquals(BrowseArtwork.AUTHORITY, uri.authority)
            val text = uri.toString()
            assertFalse(text, text.contains("jf.example") || text.contains("8096") || text.contains(secretToken) || text.contains("Items"))
            assertNotNull(BrowseArtwork.urlOf(uri.lastPathSegment!!))
        }
        // Ids carry no secrets either
        assertTrue(items.none { it.mediaId.contains(secretToken) || it.mediaId.contains("jf.example") })
    }

    @Test
    fun bundledAndLocalArtStaysAndJunkIsDropped() {
        assertEquals("android.resource://x/1", BrowseArtwork.uriFor("android.resource://x/1").toString())
        assertNull(BrowseArtwork.uriFor("file:///sdcard/a.jpg"))
        assertNull(BrowseArtwork.uriFor(null))
        val uri = BrowseArtwork.uriFor("https://avatars.example/a/400x400")!!
        assertTrue(BrowseArtwork.KEY.matches(uri.lastPathSegment!!))
    }
}
