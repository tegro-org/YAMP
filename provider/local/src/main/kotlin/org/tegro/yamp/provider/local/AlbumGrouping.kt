package org.tegro.yamp.provider.local

import java.util.Locale
import org.tegro.yamp.domain.media.NaturalOrder
import org.tegro.yamp.domain.queue.MediaRef

/** A track as album grouping sees it: where it is and its tags after [FileNameFallback.apply]. */
data class GroupTrack(val path: LocalPath, val tags: TrackTags)

/**
 * An album of the local library. [artist] is the album artist, else the one artist all its tracks share; null with
 * [various] = true when they differ (a compilation without an album artist), null with [various] = false when no
 * track names one. [tracks] are in play order: disc, track number, then the file name (natural order).
 */
data class LocalAlbum(
    val key: String,
    val ref: MediaRef,
    val title: String,
    val artist: String?,
    val various: Boolean,
    val year: Int?,
    val tracks: List<MediaRef>
)

/**
 * Album and artist grouping (docs/phase-4d-local-files.md §6). Albums are per volume: the key is (volume, album
 * title, album artist), so the same album on two sticks is two albums; a track without an album artist is grouped by
 * its folder (with `CD1` / `Disc 2` levels collapsed into the parent) and its
 * album title, so a compilation folder stays one album and two tagged albums in one folder stay two. Library artists
 * come from the album artist, else the track artist, split at `;` and `feat.`.
 */
object AlbumGrouping {

    /** The disc number of a `CD1`, `CD 2`, `Disc 3`, `disk_4`, `Диск 5` folder; null for any other name. */
    fun discNumber(folderName: String): Int? = DISC_FOLDER.matchEntire(folderName.trim())?.groupValues?.get(1)?.toInt()

    /** The album's folder for a track in [dirRelPath]: the parent when the folder is a disc folder (`CD1`). */
    fun albumDir(dirRelPath: String): String {
        val name = dirRelPath.substringAfterLast('/')
        return if (discNumber(name) != null) dirRelPath.substringBeforeLast('/', missingDelimiterValue = "") else dirRelPath
    }

    /** The grouping key of a track at [path] with fallback-applied [tags]. */
    fun albumKey(path: LocalPath, tags: TrackTags): String {
        val album = normalize(tags.album.orEmpty())
        val albumArtist = tags.albumArtist?.let(::normalize).orEmpty()
        if (album.isNotEmpty() && albumArtist.isNotEmpty()) return "tag$SEP${path.volumeId}$SEP$album$SEP$albumArtist"
        val dir = albumDir(path.relPath.substringBeforeLast('/', missingDelimiterValue = ""))
        return "dir$SEP${path.volumeId}/$dir$SEP$album"
    }

    /** Case-, space- and `ё`-insensitive form of a name, for keys and artist refs. */
    fun normalize(text: String): String =
        WHITESPACE.replace(text.trim(), " ").lowercase(Locale.ROOT).replace('ё', 'е')

    /**
     * The artists in a tag: `A; B`, `A feat. B`, `A ft. B`, `A (feat. B)`, `A featuring B` → [A, B]; duplicates
     * (case-insensitive) and blanks dropped. `&`, `,` and `/` are not split — they are part of many band names.
     */
    fun splitArtists(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val flat = BRACKETED_FEAT.replace(text) { ";" + it.groupValues[1] }
        return flat.split(';', '\u0000')
            .flatMap { it.split(FEAT) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy(::normalize)
    }

    /** The artists a track lists under in the Library: the album artist's, else the track artist's. */
    fun libraryArtists(tags: TrackTags): List<String> = splitArtists(tags.albumArtist).ifEmpty { splitArtists(tags.artist) }

    /** [tracks] grouped into albums, ordered by title (natural order). */
    fun group(tracks: List<GroupTrack>): List<LocalAlbum> =
        tracks.groupBy { albumKey(it.path, it.tags) }
            .map { (key, members) -> album(key, members) }
            .sortedWith(compareBy(NaturalOrder) { it.title })

    private fun album(key: String, members: List<GroupTrack>): LocalAlbum {
        val ordered = members.sortedWith(PLAY_ORDER)
        val albumArtist = ordered.firstNotNullOfOrNull { it.tags.albumArtist?.trim()?.ifEmpty { null } }
        val artists = ordered.mapNotNull { it.tags.artist?.trim()?.ifEmpty { null } }.distinctBy(::normalize)
        return LocalAlbum(
            key = key,
            ref = LocalRefs.album(key),
            title = ordered.firstNotNullOfOrNull { it.tags.album?.trim()?.ifEmpty { null } }.orEmpty(),
            artist = albumArtist ?: artists.singleOrNull(),
            various = albumArtist == null && artists.size > 1,
            year = ordered.firstNotNullOfOrNull { it.tags.year },
            tracks = ordered.map { LocalRefs.track(it.path.volumeId, it.path.relPath) }
        )
    }

    private val PLAY_ORDER: Comparator<GroupTrack> =
        compareBy<GroupTrack> { it.tags.disc ?: 1 }
            .thenBy(nullsLast()) { it.tags.trackNo }
            .thenBy(NaturalOrder) { it.path.relPath }

    private const val SEP = '\u0000'
    private val WHITESPACE = Regex("""\s+""")
    private val DISC_FOLDER = Regex("""^(?:cd|disc|disk|диск)[\s._#-]*(\d{1,2})(?:[\s._-].*)?$""", RegexOption.IGNORE_CASE)
    private val BRACKETED_FEAT = Regex("""\s*[(\[]\s*(?:feat\.?|ft\.|featuring)\s+([^)\]]+)[)\]]""", RegexOption.IGNORE_CASE)
    private val FEAT = Regex("""\s+(?:feat\.?|ft\.|featuring)\s+""", RegexOption.IGNORE_CASE)
}
