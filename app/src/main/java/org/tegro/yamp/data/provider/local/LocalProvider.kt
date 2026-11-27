package org.tegro.yamp.data.provider.local

import org.tegro.yamp.data.provider.local.db.LibraryTrackRow
import org.tegro.yamp.data.provider.local.db.SourceEntity
import org.tegro.yamp.data.provider.local.db.TrackEntity
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.AlbumBrief
import org.tegro.yamp.domain.media.Artist
import org.tegro.yamp.domain.media.ArtistBrief
import org.tegro.yamp.domain.media.Cover
import org.tegro.yamp.domain.media.Folder
import org.tegro.yamp.domain.media.FolderLevel
import org.tegro.yamp.domain.media.Genre
import org.tegro.yamp.domain.media.Playlist
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.provider.ArtistPage
import org.tegro.yamp.domain.provider.Browse
import org.tegro.yamp.domain.provider.BrowseCounts
import org.tegro.yamp.domain.provider.Catalog
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
import org.tegro.yamp.provider.local.AlbumGrouping
import org.tegro.yamp.provider.local.LocalRefs

/** The words the local pages need from resources (the provider itself has no context). */
data class LocalLabels(
    val unknownArtist: String,
    /** The built-in shared storage ("Phone"): the system calls it "Internal shared storage". */
    val phone: String
)

/** A local cover: served by the app's artwork provider by an opaque key (no path in the URI), at `{px}`. */
data class LocalCover(override val key: String) : Cover {
    override fun url(sizePx: Int): String = key.replace(LocalArt.SIZE, sizePx.toString())
}

/**
 * "This device" as a [MusicProvider] (docs/phase-4d-local-files.md §8, D92): the index of the chosen folders in
 * `local.db`. Library (favourites; playlists stay empty in Stage 1), search over the normalised text (Cyrillic folded,
 * `ё` = `е`), album and artist pages, browse lists (albums per volume, artists from tags, genres, recently added) and
 * the folder tree (root = the volumes when there are several sources, a source's folder directly when there is one).
 * Every call reads the in-memory [IndexSnapshot]; nothing here touches the volumes. What sits on an absent volume
 * is listed as unavailable ([Track.available], [Folder.available], [Album.available]), never dropped (D47, D96).
 */
class LocalProvider(
    private val index: LocalLibrary,
    private val volumes: VolumeState,
    override val streams: StreamResolver,
    private val labels: () -> LocalLabels,
    private val art: LocalArt? = null
) : MusicProvider {

    override val account: AccountId = AccountId.local()

    override val library: Library = object : Library {
        override suspend fun likedTracks(): Outcome<List<Track>> = ok { s ->
            s.favourites.mapNotNull { s.byRef[s.follow(it)] }.distinctBy { it.ref }.map { track(s, it) }
        }

        override suspend fun playlists(): Outcome<List<Playlist>> = Outcome.Ok(emptyList())

        override suspend fun playlistTracks(playlist: MediaRef): Outcome<List<Track>> = Outcome.Err(ProviderError.NotFound)
    }

    override val search: Search = object : Search {
        override suspend fun search(text: String): Outcome<SearchResult> = ok { s ->
            val q = AlbumGrouping.normalize(text)
            if (q.isEmpty()) return@ok SearchResult()
            SearchResult(
                tracks = s.tracks.asSequence().filter { searchText(it).contains(q) }.take(SEARCH_TRACKS).sortedWith(IndexSnapshot.FILE_ORDER).map { track(s, it) }.toList(),
                artists = s.artists.asSequence().filter { it.name.isNotEmpty() && AlbumGrouping.normalize(it.name).contains(q) }.take(SEARCH_OTHERS)
                    .map { artist(s, it) }.toList(),
                albums = s.albums.asSequence().filter { AlbumGrouping.normalize(it.title).contains(q) }.take(SEARCH_OTHERS).map { album(s, it) }.toList()
            )
        }
    }

    override val catalog: Catalog = object : Catalog {
        override suspend fun album(album: MediaRef): Outcome<Album> {
            val s = index.snapshot()
            val found = s.albumsByRef[album.value] ?: return Outcome.Err(ProviderError.NotFound)
            return Outcome.Ok(album(s, found, withTracks = true))
        }

        override suspend fun artist(artist: MediaRef): Outcome<ArtistPage> {
            val s = index.snapshot()
            val found = s.artistsByRef[artist.value] ?: return Outcome.Err(ProviderError.NotFound)
            val albums = s.albums.filter { it.key in found.albumKeys }
            return Outcome.Ok(
                ArtistPage(
                    artist = artist(s, found),
                    popularTracks = albums.flatMap { it.tracks }.filter { it in found.tracks }.map { track(s, it) },
                    albums = albums.map { album(s, it) }
                )
            )
        }
    }

    override val browse: Browse = object : Browse {
        override suspend fun counts(): Outcome<BrowseCounts> = ok { s -> BrowseCounts(s.albums.size, s.artists.size, s.genres.size, playlists = 0) }

        override suspend fun albums(cursor: Page.Cursor?): Outcome<Page<Album>> = ok { s -> Page(s.albums.map { album(s, it) }) }

        override suspend fun artists(cursor: Page.Cursor?): Outcome<Page<Artist>> = ok { s -> Page(s.artists.map { artist(s, it) }) }

        override suspend fun genres(cursor: Page.Cursor?): Outcome<Page<Genre>> = ok { s ->
            Page(s.genres.map { (name, tracks) -> Genre(LocalRefs.genre(name), name, tracks.firstOrNull { it.art != TrackEntity.ART_NONE }?.let { coverOf(s, it) }) })
        }

        override suspend fun playlists(cursor: Page.Cursor?): Outcome<Page<Playlist>> = Outcome.Ok(Page(emptyList()))

        override suspend fun genreAlbums(genre: MediaRef, cursor: Page.Cursor?): Outcome<Page<Album>> = ok { s ->
            val keys = s.genres.entries.firstOrNull { LocalRefs.genre(it.key) == genre }?.value?.map { it.albumKey }?.toSet().orEmpty()
            Page(s.albums.filter { it.key in keys }.map { album(s, it) })
        }

        override suspend fun recentAlbums(limit: Int): Outcome<List<Album>> = ok { s ->
            s.albums.sortedByDescending { it.firstSeenAt }.take(limit).map { album(s, it) }
        }
    }

    override val folders: Folders = object : Folders {
        override suspend fun root(): Outcome<FolderLevel> = ok { s ->
            val sources = s.sources
            when {
                sources.isEmpty() -> FolderLevel(emptyList(), emptyList())
                // One source: its folder at once (the 4b "skip the library level" rule)
                sources.size == 1 -> dirLevel(s, sources.single().volumeId, sources.single().rootRel)
                else -> FolderLevel(sources.map { it.volumeId }.distinct().map { volumeFolder(s, it) }, emptyList())
            }
        }

        override suspend fun level(folder: MediaRef): Outcome<FolderLevel> {
            val s = index.snapshot()
            val path = LocalRefs.path(folder) ?: return Outcome.Err(ProviderError.NotFound)
            if (path.relPath.isNotEmpty()) return Outcome.Ok(dirLevel(s, path.volumeId, path.relPath))
            // A volume of the root: its one source's folder straight away, else its sources (the whole volume, if
            // one of them is its root)
            val ofVolume = s.sources.filter { it.volumeId == path.volumeId }
            return Outcome.Ok(
                when {
                    ofVolume.isEmpty() -> FolderLevel(emptyList(), emptyList())
                    ofVolume.size == 1 || ofVolume.any { it.rootRel.isEmpty() } ->
                        dirLevel(s, path.volumeId, ofVolume.singleOrNull()?.rootRel ?: "")
                    else -> FolderLevel(ofVolume.map { sourceFolder(s, it) }, emptyList())
                }
            )
        }

        override suspend fun tracks(folder: MediaRef): Outcome<List<Track>> {
            val s = index.snapshot()
            val path = LocalRefs.path(folder) ?: return Outcome.Err(ProviderError.NotFound)
            val roots = if (path.relPath.isEmpty()) s.sources.filter { it.volumeId == path.volumeId }.map { it.rootRel } else listOf(path.relPath)
            return Outcome.Ok(roots.flatMap { s.tracksUnder(path.volumeId, it) }.distinctBy { it.ref }.map { track(s, it) })
        }
    }

    /** A source's folder, or the volume root's own content when the source is the whole volume. */
    private fun dirLevel(s: IndexSnapshot, volumeId: String, relPath: String): FolderLevel {
        val dir = s.dir(volumeId, relPath) ?: return FolderLevel(emptyList(), emptyList())
        val mounted = volumes.isMounted(volumeId)
        return FolderLevel(
            folders = s.childrenOf(dir).map { child ->
                Folder(
                    ref = LocalRefs.folder(volumeId, child.relPath),
                    title = child.name,
                    cover = child.artFile?.let { art?.folderCover(child) },
                    childCount = null,
                    trackCount = s.countUnder(volumeId, child.relPath),
                    available = mounted
                )
            },
            tracks = s.tracksIn(dir).map { track(s, it, mounted) }
        )
    }

    private fun volumeFolder(s: IndexSnapshot, volumeId: String) = Folder(
        ref = LocalRefs.folder(volumeId, ""),
        title = volumeLabel(s, volumeId),
        cover = null,
        trackCount = s.sources.filter { it.volumeId == volumeId }.sumOf { s.countUnder(volumeId, it.rootRel) },
        available = volumes.isMounted(volumeId)
    )

    private fun sourceFolder(s: IndexSnapshot, source: SourceEntity) = Folder(
        ref = LocalRefs.folder(source.volumeId, source.rootRel),
        title = source.rootRel.substringAfterLast('/').ifEmpty { volumeLabel(s, source.volumeId) },
        cover = null,
        trackCount = s.countUnder(source.volumeId, source.rootRel),
        available = volumes.isMounted(source.volumeId)
    )

    /** The volume's name: "Phone" for the built-in storage, else the system's label (the stick's, "SD card"). */
    fun volumeLabel(s: IndexSnapshot, volumeId: String): String =
        if (volumeId == LocalVolume.PRIMARY) labels().phone else volumes.label(volumeId) ?: s.volumeLabels[volumeId] ?: volumeId

    private fun track(s: IndexSnapshot, row: LibraryTrackRow, mounted: Boolean = volumes.isMounted(row.volumeId)): Track {
        val names = IndexSnapshot.libraryArtists(row).let { library ->
            // A track names its own artist(s); the album artist only when the track names none
            AlbumGrouping.splitArtists(row.artists).ifEmpty { library }
        }
        return Track(
            ref = TrackRef(row.ref),
            title = row.title,
            artists = names.map { ArtistBrief(LocalRefs.artist(it), it) }.ifEmpty { listOf(ArtistBrief(LocalRefs.artist(""), labels().unknownArtist)) },
            album = AlbumBrief(LocalRefs.album(row.albumKey), row.album),
            durationMs = row.durationMs.takeIf { it > 0 },
            cover = coverOf(s, row),
            explicit = false,
            available = mounted
        )
    }

    private fun album(s: IndexSnapshot, album: IndexAlbum, withTracks: Boolean = false): Album {
        val mounted = volumes.isMounted(album.volumeId)
        return Album(
            ref = album.ref,
            title = album.title,
            artists = album.artists.map { ArtistBrief(LocalRefs.artist(it), it) },
            cover = album.coverTrack?.let { coverOf(s, it) },
            year = album.year,
            trackCount = album.tracks.size,
            tracks = if (withTracks) album.tracks.map { track(s, it, mounted) } else emptyList(),
            qualifier = if (album.key in s.duplicated) volumeLabel(s, album.volumeId) else null,
            available = mounted
        )
    }

    private fun artist(s: IndexSnapshot, artist: IndexArtist): Artist = Artist(
        ref = artist.ref,
        name = artist.name.ifEmpty { labels().unknownArtist },
        cover = s.albums.firstOrNull { it.key in artist.albumKeys && it.coverTrack != null }?.coverTrack?.let { coverOf(s, it) }
    )

    private fun coverOf(s: IndexSnapshot, row: LibraryTrackRow): Cover? = art?.trackCover(row, s.dirsById[row.dirId])

    /** The scanner's normalised text (title, artists, album, file name), as the query is normalised. */
    private fun searchText(row: LibraryTrackRow): String = row.searchText

    private suspend fun <T> ok(build: (IndexSnapshot) -> T): Outcome<T> = Outcome.Ok(build(index.snapshot()))

    private companion object {
        const val SEARCH_TRACKS = 50
        const val SEARCH_OTHERS = 20
    }
}
