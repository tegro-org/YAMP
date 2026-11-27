package org.tegro.yamp.provider.local

/**
 * A file's tags as read (Media3 / ID3v1, after [TagText.repair]) — every field may be missing. [artist] and
 * [albumArtist] are the raw text; [AlbumGrouping.splitArtists] makes lists of them.
 */
data class TrackTags(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val disc: Int? = null,
    val trackNo: Int? = null,
    val year: Int? = null,
    val genre: String? = null
)

/**
 * What an untagged file (YouTube downloads, audiobooks) shows (docs/phase-4d-local-files.md, UX): title = the file
 * name without its extension and a leading track number (`01 - `, `01. `, `1_`, `01 `), the track number from that
 * prefix, album = the folder (a `CD1` / `Disc 2` folder gives the disc and its parent's name), artist left empty
 * (the UI shows "Unknown artist" in its own language). Tags that are present always win.
 */
object FileNameFallback {

    /** [tags] of the file at [relPath] with the missing title, track number, album and disc filled in. */
    fun apply(relPath: String, tags: TrackTags): TrackTags {
        val fileName = relPath.substringAfterLast('/')
        val dir = relPath.substringBeforeLast('/', missingDelimiterValue = "")
        val (number, name) = parse(baseName(fileName))
        return tags.copy(
            title = tags.title.clean() ?: name,
            trackNo = tags.trackNo ?: number,
            album = tags.album.clean() ?: AlbumGrouping.albumDir(dir).substringAfterLast('/').ifEmpty { null },
            disc = tags.disc ?: AlbumGrouping.discNumber(dir.substringAfterLast('/')),
            artist = tags.artist.clean(),
            albumArtist = tags.albumArtist.clean(),
            genre = tags.genre.clean()
        )
    }

    /** The file name without its extension (`.mp3`); a name that only starts with a dot keeps it. */
    fun baseName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    /**
     * A leading track number and the rest: `01 - Song`, `01. Song`, `1_Song`, `3) Song`, `01 Song` (a bare space only
     * after a zero-padded number, so `99 Luftballons` stays whole). Up to three digits (a year like `1979 - …` is a
     * title). A name with nothing after the number (`01`, `01 -`) stays whole, without a track number.
     */
    fun parse(name: String): Pair<Int?, String> {
        val trimmed = name.trim()
        val match = SEPARATED.matchEntire(trimmed) ?: PADDED.matchEntire(trimmed) ?: return null to trimmed
        val title = match.groupValues[2].trim()
        if (title.none { it.isLetterOrDigit() }) return null to trimmed
        return match.groupValues[1].toInt() to title
    }

    private fun String?.clean(): String? = this?.trim()?.ifEmpty { null }

    private val SEPARATED = Regex("""^(\d{1,3})\s*[-._)]\s*(.+)$""")
    private val PADDED = Regex("""^(0\d{1,2})\s+(.+)$""")
}
