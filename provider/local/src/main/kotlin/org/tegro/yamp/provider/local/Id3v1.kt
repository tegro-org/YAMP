package org.tegro.yamp.provider.local

/** The fields of an ID3v1 / v1.1 tag; blank fields are null. */
data class Id3v1Tag(
    val title: String?,
    val artist: String?,
    val album: String?,
    val year: Int?,
    val comment: String?,
    val trackNo: Int?,
    val genre: String?
)

/**
 * The ID3v1 reader (docs/phase-4d-local-files.md §6): the 128 bytes at the end of an MP3, `TAG` + title 30, artist
 * 30, album 30, year 4, comment 30, genre 1. ID3v1.1 keeps the track number in the comment's last byte when the byte
 * before it is zero. Media3 doesn't read ID3v1, and old Russian MP3s often have nothing else; text is decoded by
 * [TagText.decode] (CP1251 / UTF-8 repair).
 */
object Id3v1 {
    const val SIZE = 128

    private const val TITLE = 3
    private const val ARTIST = 33
    private const val ALBUM = 63
    private const val YEAR = 93
    private const val COMMENT = 97
    private const val GENRE = 127
    private const val TEXT_LENGTH = 30
    private const val YEAR_LENGTH = 4
    private const val V11_ZERO = COMMENT + 28
    private const val V11_TRACK = COMMENT + 29

    /** The tag in the last 128 bytes of [tail] (a file's end); null when there is no `TAG` there. */
    fun parse(tail: ByteArray): Id3v1Tag? {
        if (tail.size < SIZE) return null
        val b = tail.copyOfRange(tail.size - SIZE, tail.size)
        if (b[0] != 'T'.code.toByte() || b[1] != 'A'.code.toByte() || b[2] != 'G'.code.toByte()) return null
        val v11 = b[V11_ZERO].toInt() == 0 && b[V11_TRACK].toInt() != 0
        return Id3v1Tag(
            title = text(b, TITLE, TEXT_LENGTH),
            artist = text(b, ARTIST, TEXT_LENGTH),
            album = text(b, ALBUM, TEXT_LENGTH),
            year = text(b, YEAR, YEAR_LENGTH)?.takeIf { y -> y.length == YEAR_LENGTH && y.all { it.isDigit() } }?.toInt()?.takeIf { it > 0 },
            comment = text(b, COMMENT, if (v11) TEXT_LENGTH - 2 else TEXT_LENGTH),
            trackNo = if (v11) b[V11_TRACK].toInt() and BYTE_MASK else null,
            genre = GENRES.getOrNull(b[GENRE].toInt() and BYTE_MASK)
        )
    }

    /** A fixed-size field: up to the first NUL, trailing spaces trimmed, decoded; null when blank. */
    private fun text(b: ByteArray, from: Int, length: Int): String? {
        var end = from
        while (end < from + length && b[end].toInt() != 0) end++
        val raw = b.copyOfRange(from, end)
        return TagText.decode(raw).trim().ifEmpty { null }
    }

    private const val BYTE_MASK = 0xFF

    /** The 80 genres of the ID3v1 spec (index = the genre byte); Winamp's extensions are not listed (null). */
    private val GENRES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychadelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock"
    )
}
