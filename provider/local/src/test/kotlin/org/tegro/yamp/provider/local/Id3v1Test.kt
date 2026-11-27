package org.tegro.yamp.provider.local

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Id3v1Test {

    private val cp1251 = Charset.forName("windows-1251")

    private fun tag(
        title: ByteArray = ByteArray(0),
        artist: ByteArray = ByteArray(0),
        album: ByteArray = ByteArray(0),
        year: String = "",
        comment: ByteArray = ByteArray(0),
        track: Int? = null,
        genre: Int = 255,
        pad: Byte = 0
    ): ByteArray {
        val b = ByteArray(128) { pad }
        "TAG".toByteArray().copyInto(b, 0)
        title.copyInto(b, 3)
        artist.copyInto(b, 33)
        album.copyInto(b, 63)
        year.toByteArray().copyInto(b, 93)
        comment.copyInto(b, 97)
        if (track != null) {
            b[125] = 0
            b[126] = track.toByte()
        }
        b[127] = genre.toByte()
        return b
    }

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun aV10TagIsRead() {
        val parsed = Id3v1.parse(tag(ascii("Time"), ascii("Pink Floyd"), ascii("The Dark Side of the Moon"), "1973", ascii("great"), genre = 17))!!
        assertEquals("Time", parsed.title)
        assertEquals("Pink Floyd", parsed.artist)
        assertEquals("The Dark Side of the Moon", parsed.album)
        assertEquals(1973, parsed.year)
        assertEquals("great", parsed.comment)
        assertNull(parsed.trackNo)
        assertEquals("Rock", parsed.genre)
    }

    @Test
    fun aV11TagHasATrackNumber() {
        val parsed = Id3v1.parse(tag(ascii("Song"), comment = ascii("c"), track = 7, genre = 0))!!
        assertEquals(7, parsed.trackNo)
        assertEquals("c", parsed.comment)
        assertEquals("Blues", parsed.genre)
    }

    @Test
    fun aTrackNumberAbove127IsUnsigned() {
        assertEquals(200, Id3v1.parse(tag(ascii("x"), track = 200))!!.trackNo)
    }

    @Test
    fun aFullCommentIsNotATrackNumber() {
        val comment = ascii("012345678901234567890123456789")
        val parsed = Id3v1.parse(tag(comment = comment))!!
        assertNull(parsed.trackNo)
        assertEquals("012345678901234567890123456789", parsed.comment)
    }

    @Test
    fun spacePaddedFieldsAreTrimmedAndBlanksAreNull() {
        val parsed = Id3v1.parse(tag(ascii("Title"), pad = ' '.code.toByte(), year = "    "))!!
        assertEquals("Title", parsed.title)
        assertNull(parsed.artist)
        assertNull(parsed.album)
        assertNull(parsed.year)
    }

    @Test
    fun cp1251FieldsAreRepaired() {
        val parsed = Id3v1.parse(tag("Отречение".toByteArray(cp1251), "Флёр".toByteArray(cp1251), "Эйфория".toByteArray(cp1251), "2005"))!!
        assertEquals("Отречение", parsed.title)
        assertEquals("Флёр", parsed.artist)
        assertEquals("Эйфория", parsed.album)
        assertEquals(2005, parsed.year)
    }

    @Test
    fun aFullWidthFieldEndsAtItsBoundary() {
        val title = ascii("A".repeat(30))
        val parsed = Id3v1.parse(tag(title, ascii("B")))!!
        assertEquals("A".repeat(30), parsed.title)
        assertEquals("B", parsed.artist)
    }

    @Test
    fun theTagIsTakenFromTheEndOfALongerBuffer() {
        val audio = ByteArray(1000) { 0x55 }
        assertEquals("End", Id3v1.parse(audio + tag(ascii("End")))!!.title)
    }

    @Test
    fun noTagOrShortInputIsNull() {
        assertNull(Id3v1.parse(ByteArray(128)))
        assertNull(Id3v1.parse(ascii("TAG")))
        assertNull(Id3v1.parse(tag(ascii("x")) + ByteArray(1)))
    }

    @Test
    fun aBadYearOrUnknownGenreIsNull() {
        val parsed = Id3v1.parse(tag(ascii("x"), year = "19x5", genre = 150))!!
        assertNull(parsed.year)
        assertNull(parsed.genre)
        assertEquals("Hard Rock", Id3v1.parse(tag(ascii("x"), genre = 79))!!.genre)
    }
}
