package org.tegro.yamp.data.provider.local.scan

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.tegro.yamp.provider.local.Id3v1Tag
import org.tegro.yamp.provider.local.TagText
import org.tegro.yamp.provider.local.TrackTags

/**
 * Media3 metadata entries → [TrackTags] (docs/phase-4d-local-files.md §6). ID3 text frames (MP3, WAV; Media3 also maps
 * MP4 `ilst` atoms to them: `©nam` → TIT2, `aART` → TPE2, `trkn` → TRCK…) and Vorbis comments (FLAC, Ogg, Opus).
 *
 * CP1251 repair ([TagText.repair]) applies only when [id3] is true — the ID3 container, whose "Latin-1" frames old
 * Russian taggers filled with CP1251. Media3 doesn't expose a frame's encoding byte, so a UTF-16 frame is told apart by
 * the repair's own rule (text with characters above U+00FF is never touched). Vorbis comments and MP4 atoms are
 * UTF-8 by spec: never repaired.
 */
@OptIn(UnstableApi::class)
object Media3Tags {

    fun map(entries: List<Metadata.Entry>, id3: Boolean): TrackTags {
        val fields = mutableMapOf<Field, String>()
        val artists = mutableListOf<String>()
        for ((field, raw) in entries.flatMap(::fieldsOf)) {
            val text = (if (id3) TagText.repair(raw) else raw).trim().ifEmpty { null } ?: continue
            if (field == Field.ARTIST) artists += text else fields.putIfAbsent(field, text)
        }
        return TrackTags(
            title = fields[Field.TITLE],
            artist = artists.distinct().joinToString("; ").ifEmpty { null },
            albumArtist = fields[Field.ALBUM_ARTIST],
            album = fields[Field.ALBUM],
            disc = fields[Field.DISC]?.let(::leadingNumber),
            trackNo = fields[Field.TRACK]?.let(::leadingNumber),
            year = fields[Field.YEAR]?.let(::year),
            genre = fields[Field.GENRE]?.let(::genre)
        )
    }

    /** The tag fields one entry holds (an ID3 frame can hold several values). */
    private fun fieldsOf(entry: Metadata.Entry): List<Pair<Field, String>> = when (entry) {
        is TextInformationFrame -> ID3_FIELDS[entry.id]?.let { field -> entry.values.map { field to it } }.orEmpty()
        is VorbisComment -> VORBIS_FIELDS[entry.key.uppercase()]?.let { listOf(it to entry.value) }.orEmpty()
        else -> emptyList()
    }

    /** Whether the entries carry a picture (ID3 APIC, FLAC / Vorbis PICTURE). */
    fun hasPicture(entries: List<Metadata.Entry>): Boolean =
        entries.any { it is ApicFrame || it is PictureFrame || (it is VorbisComment && it.key.equals("METADATA_BLOCK_PICTURE", ignoreCase = true)) }

    /** [tags] with the fields it lacks taken from an ID3v1 [v1] tag (old MP3s with nothing else). */
    fun withId3v1(tags: TrackTags, v1: Id3v1Tag?): TrackTags {
        if (v1 == null) return tags
        return tags.copy(
            title = tags.title ?: v1.title,
            artist = tags.artist ?: v1.artist,
            album = tags.album ?: v1.album,
            year = tags.year ?: v1.year,
            trackNo = tags.trackNo ?: v1.trackNo,
            genre = tags.genre ?: v1.genre
        )
    }

    /** `3`, `03/12`, `3 of 12` → 3; null without a positive leading number. */
    internal fun leadingNumber(text: String): Int? = LEADING_NUMBER.find(text.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    /** `2005`, `2005-03-01`, `2005/2006` → 2005. */
    internal fun year(text: String): Int? = YEAR.find(text)?.value?.toIntOrNull()

    /** ID3's `(17)Rock` / `(17)` references: the text when there is one, else the raw value. */
    internal fun genre(text: String): String? = GENRE_REF.replace(text, "").trim().ifEmpty { text.trim() }.ifEmpty { null }

    internal enum class Field { TITLE, ARTIST, ALBUM_ARTIST, ALBUM, TRACK, DISC, YEAR, GENRE }

    private val ID3_FIELDS = mapOf(
        "TIT2" to Field.TITLE, "TT2" to Field.TITLE,
        "TPE1" to Field.ARTIST, "TP1" to Field.ARTIST,
        "TPE2" to Field.ALBUM_ARTIST, "TP2" to Field.ALBUM_ARTIST,
        "TALB" to Field.ALBUM, "TAL" to Field.ALBUM,
        "TRCK" to Field.TRACK, "TRK" to Field.TRACK,
        "TPOS" to Field.DISC, "TPA" to Field.DISC,
        "TDRC" to Field.YEAR, "TYER" to Field.YEAR, "TYE" to Field.YEAR, "TDRL" to Field.YEAR, "TDOR" to Field.YEAR,
        "TCON" to Field.GENRE, "TCO" to Field.GENRE
    )
    private val VORBIS_FIELDS = mapOf(
        "TITLE" to Field.TITLE,
        "ARTIST" to Field.ARTIST,
        "ALBUMARTIST" to Field.ALBUM_ARTIST, "ALBUM ARTIST" to Field.ALBUM_ARTIST, "ALBUM_ARTIST" to Field.ALBUM_ARTIST,
        "ALBUM" to Field.ALBUM,
        "TRACKNUMBER" to Field.TRACK,
        "DISCNUMBER" to Field.DISC,
        "DATE" to Field.YEAR, "YEAR" to Field.YEAR, "ORIGINALDATE" to Field.YEAR,
        "GENRE" to Field.GENRE
    )
    private val LEADING_NUMBER = Regex("""^(\d{1,4})""")
    private val YEAR = Regex("""(?<!\d)(1[89]\d\d|20\d\d)(?!\d)""")
    private val GENRE_REF = Regex("""^\(\d+\)""")
}
