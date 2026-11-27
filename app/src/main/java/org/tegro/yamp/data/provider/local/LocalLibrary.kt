package org.tegro.yamp.data.provider.local

import androidx.room.InvalidationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tegro.yamp.data.provider.local.db.DirEntity
import org.tegro.yamp.data.provider.local.db.LibraryTrackRow
import org.tegro.yamp.data.provider.local.db.LocalDatabase
import org.tegro.yamp.data.provider.local.db.SourceEntity
import org.tegro.yamp.data.provider.local.db.TrackEntity
import org.tegro.yamp.domain.media.NaturalOrder
import org.tegro.yamp.provider.local.AlbumGrouping
import org.tegro.yamp.provider.local.LocalRefs
import org.tegro.yamp.provider.local.TrackTags

/**
 * One album of the index: its tracks in play order (disc, track number, file name). [titles] etc. as the tags say;
 * [volumeId] is the one volume it is on (albums are per volume, user 2026-11-20).
 */
class IndexAlbum(
    val key: String,
    val title: String,
    /** The album artist, else the one artist its tracks share; empty for a compilation without an album artist. */
    val artists: List<String>,
    val year: Int?,
    val volumeId: String,
    val tracks: List<LibraryTrackRow>,
    val firstSeenAt: Long
) {
    val ref = LocalRefs.album(key)

    /** The first track with a picture: the album's cover. */
    val coverTrack: LibraryTrackRow? = tracks.firstOrNull { it.art != TrackEntity.ART_NONE }
}

/** A library artist: the album artist's, else the track artist's name; [name] "" = the untagged files. */
class IndexArtist(val name: String, val tracks: List<LibraryTrackRow>, val albumKeys: Set<String>) {
    val ref = LocalRefs.artist(name)
}

/**
 * Everything the local pages show, read from `local.db` at once (docs/phase-4d-local-files.md §8: albums, artists and
 * genres are queries over the tracks, not tables). Built off the main thread; a change to the index drops it and the
 * next call builds a new one. Pure data: availability (a mounted volume) is asked when a page is made.
 */
class IndexSnapshot(
    val tracks: List<LibraryTrackRow>,
    dirs: List<DirEntity>,
    val sources: List<SourceEntity>,
    val volumeLabels: Map<String, String>,
    val favourites: List<String>,
    private val moves: Map<String, String>,
    val skipped: List<org.tegro.yamp.data.provider.local.db.FileEntity> = emptyList()
) {
    val byRef: Map<String, LibraryTrackRow> = tracks.associateBy { it.ref }
    val dirsById: Map<Long, DirEntity> = dirs.associateBy { it.id }
    private val dirsByPath: Map<String, DirEntity> = dirs.associateBy { pathKey(it.volumeId, it.relPath) }
    private val children: Map<Long, List<DirEntity>> = dirs.filter { it.parentId != null }.groupBy { it.parentId!! }
    private val tracksByDir: Map<Long, List<LibraryTrackRow>> = tracks.groupBy { it.dirId }

    /** Tracks anywhere under a folder (`volume␀path` → count), the volume root (`volume␀`) included. */
    private val countsUnder: Map<String, Int> = HashMap<String, Int>().also { counts ->
        for (track in tracks) {
            var dir = track.relPath.substringBeforeLast('/', missingDelimiterValue = "")
            while (true) {
                val key = pathKey(track.volumeId, dir)
                counts[key] = (counts[key] ?: 0) + 1
                if (dir.isEmpty()) break
                dir = dir.substringBeforeLast('/', missingDelimiterValue = "")
            }
        }
    }

    val albums: List<IndexAlbum> = tracks.groupBy { it.albumKey }.map { (key, members) -> album(key, members) }
        .sortedWith(compareBy(NaturalOrder) { it.title })
    val albumsByRef: Map<String, IndexAlbum> = albums.associateBy { it.ref.value }

    /** Library artists by name (tags), untagged files under "" — always last. */
    val artists: List<IndexArtist> = artistsOf(tracks, albums)
    val artistsByRef: Map<String, IndexArtist> = artists.associateBy { it.ref.value }

    /** Genres as tagged (the first spelling of each), in natural order. */
    val genres: Map<String, List<LibraryTrackRow>> = tracks.filter { !it.genre.isNullOrBlank() }
        .groupBy { AlbumGrouping.normalize(it.genre!!) }
        .entries.sortedWith(compareBy(NaturalOrder) { it.value.first().genre!!.trim() })
        .associate { it.value.first().genre!!.trim() to it.value }

    /**
     * Albums whose title and artist are on more than one volume (the same album on two sticks): they are told apart by
     * the volume's name (Decisions after the boards).
     */
    val duplicated: Set<String> = albums.groupBy { AlbumGrouping.normalize(it.title) + '\u0000' + AlbumGrouping.normalize(it.artists.joinToString(";")) }
        .values.filter { group -> group.map { it.volumeId }.distinct().size > 1 }
        .flatten().map { it.key }.toSet()

    fun dir(volumeId: String, relPath: String): DirEntity? = dirsByPath[pathKey(volumeId, relPath)]

    /** The folders right inside [dir] that hold tracks somewhere under them, in natural order. */
    fun childrenOf(dir: DirEntity): List<DirEntity> = children[dir.id].orEmpty()
        .filter { countUnder(it.volumeId, it.relPath) > 0 }
        .sortedWith(compareBy(NaturalOrder) { it.name })

    /** The tracks right inside [dir], in file order (natural): audiobook chapters play in order. */
    fun tracksIn(dir: DirEntity): List<LibraryTrackRow> = tracksByDir[dir.id].orEmpty().sortedWith(FILE_ORDER)

    /** Every track under [relPath] of [volumeId], in folder order: folder by folder, natural order at every level. */
    fun tracksUnder(volumeId: String, relPath: String): List<LibraryTrackRow> =
        tracks.filter { it.volumeId == volumeId && isUnder(it.relPath, relPath) }.sortedWith(FILE_ORDER)

    fun countUnder(volumeId: String, relPath: String): Int = countsUnder[pathKey(volumeId, relPath)] ?: 0

    /** [ref] after the same-volume move aliases (D95): where the file is now. */
    fun follow(ref: String): String = moves[ref] ?: ref

    private fun album(key: String, members: List<LibraryTrackRow>): IndexAlbum {
        val ordered = members.sortedWith(PLAY_ORDER)
        val albumArtist = ordered.firstNotNullOfOrNull { it.albumArtist?.trim()?.ifEmpty { null } }
        val trackArtists = ordered.mapNotNull { it.artists?.trim()?.ifEmpty { null } }.distinctBy(AlbumGrouping::normalize)
        val artists = albumArtist?.let(AlbumGrouping::splitArtists) ?: trackArtists.singleOrNull()?.let(AlbumGrouping::splitArtists).orEmpty()
        return IndexAlbum(
            key = key,
            title = ordered.firstNotNullOfOrNull { it.album?.trim()?.ifEmpty { null } }
                ?: ordered.first().relPath.substringBeforeLast('/', "").substringAfterLast('/'),
            artists = artists,
            year = ordered.firstNotNullOfOrNull { it.year },
            volumeId = ordered.first().volumeId,
            tracks = ordered,
            firstSeenAt = ordered.minOf { it.firstSeenAt }
        )
    }

    companion object {
        private fun pathKey(volumeId: String, relPath: String) = volumeId + '\u0000' + relPath

        /**
         * Folder order, as the folder pages list a level: at every level its subfolders first (natural order), then
         * its files (natural order) — `a/b/1.mp3` before `a/2.mp3` before `a/10.mp3`.
         */
        val FILE_ORDER: Comparator<LibraryTrackRow> = Comparator { a, b -> comparePaths(a.relPath, b.relPath) }

        fun comparePaths(a: String, b: String): Int {
            val sa = a.split('/')
            val sb = b.split('/')
            var i = 0
            while (i < sa.size && i < sb.size) {
                val fileA = i == sa.lastIndex
                val fileB = i == sb.lastIndex
                if (fileA != fileB) return if (fileA) 1 else -1
                if (sa[i] != sb[i]) return NaturalOrder.compare(sa[i], sb[i])
                i++
            }
            return sa.size - sb.size
        }

        val PLAY_ORDER: Comparator<LibraryTrackRow> = compareBy<LibraryTrackRow> { it.disc ?: 1 }
            .thenBy(nullsLast()) { it.trackNo }
            .then(FILE_ORDER)

        fun libraryArtists(row: LibraryTrackRow): List<String> = AlbumGrouping.libraryArtists(TrackTags(artist = row.artists, albumArtist = row.albumArtist))

        private fun artistsOf(tracks: List<LibraryTrackRow>, albums: List<IndexAlbum>): List<IndexArtist> {
            val albumOf = HashMap<String, String>()
            albums.forEach { album -> album.tracks.forEach { albumOf[it.ref] = album.key } }
            val byName = LinkedHashMap<String, Pair<String, MutableList<LibraryTrackRow>>>()
            for (track in tracks) {
                val names = libraryArtists(track).ifEmpty { listOf("") }
                for (name in names) {
                    byName.getOrPut(AlbumGrouping.normalize(name)) { name to mutableListOf() }.second += track
                }
            }
            return byName.values
                .map { (name, list) -> IndexArtist(name, list.sortedWith(PLAY_ORDER), list.mapNotNull { albumOf[it.ref] }.toSet()) }
                .sortedWith(compareBy<IndexArtist> { it.name.isEmpty() }.thenBy(NaturalOrder) { it.name })
        }
    }
}

/**
 * Keeps the [IndexSnapshot] of `local.db`: built on first use (off the main thread), dropped whenever the index or the
 * sources change (Room's invalidation tracker), so a scan's result shows on the next page load. [version] ticks on
 * every drop, for screens that reload then.
 */
class LocalLibrary(private val db: LocalDatabase) {
    @Volatile private var current: IndexSnapshot? = null
    private val lock = Mutex()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    init {
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(arrayOf("tracks", "files", "dirs", "sources", "volumes", "favourites", "moves")) {
            override fun onInvalidated(tables: Set<String>) {
                current = null
                _version.value++
            }
        })
    }

    /** The snapshot if one is built — no disk, safe on the main thread (the resolver's `cached`). */
    fun snapshotOrNull(): IndexSnapshot? = current

    suspend fun snapshot(): IndexSnapshot = current ?: lock.withLock {
        current ?: withContext(Dispatchers.IO) { read() }.also { current = it }
    }

    private fun read(): IndexSnapshot {
        val library = db.library()
        return IndexSnapshot(
            tracks = library.tracks(),
            dirs = library.dirs(),
            sources = db.sources().all(),
            volumeLabels = library.volumes().associate { it.volumeId to it.label },
            favourites = library.favourites().map { it.ref },
            moves = library.moves().associate { it.oldRef to it.newRef },
            skipped = library.skippedFiles()
        )
    }
}
