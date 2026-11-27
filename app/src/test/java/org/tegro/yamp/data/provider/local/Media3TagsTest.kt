package org.tegro.yamp.data.provider.local

import android.app.Application
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.provider.local.scan.Media3TagReader
import org.tegro.yamp.data.provider.local.scan.Media3Tags
import org.tegro.yamp.provider.local.Id3v1Tag
import org.tegro.yamp.provider.local.TrackTags

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Media3TagsTest {

    private fun frame(id: String, vararg values: String) = TextInformationFrame(id, null, values.toList())

    @Test
    fun id3FramesMapToTags() {
        val tags = Media3Tags.map(
            listOf(
                frame("TIT2", "Отречение"), frame("TPE1", "Fleur"), frame("TPE2", "Fleur"), frame("TALB", "Эйфория"),
                frame("TRCK", "03/12"), frame("TPOS", "2/2"), frame("TDRC", "2005-03-01"), frame("TCON", "(17)Rock")
            ),
            id3 = true
        )
        assertEquals(TrackTags("Отречение", "Fleur", "Fleur", "Эйфория", disc = 2, trackNo = 3, year = 2005, genre = "Rock"), tags)
    }

    @Test
    fun cp1251InId3LatinFramesIsRepairedButNeverInVorbisComments() {
        val mojibake = String("Мельница".toByteArray(charset("windows-1251")), Charsets.ISO_8859_1)
        assertEquals("Мельница", Media3Tags.map(listOf(frame("TPE1", mojibake)), id3 = true).artist)
        assertEquals(mojibake, Media3Tags.map(listOf(VorbisComment("ARTIST", mojibake)), id3 = false).artist)
        // Real Latin-1 stays as it is
        assertEquals("Björk", Media3Tags.map(listOf(frame("TPE1", "Björk")), id3 = true).artist)
    }

    @Test
    fun vorbisCommentsMapAndSeveralArtistsAreJoined() {
        val tags = Media3Tags.map(
            listOf(
                VorbisComment("TITLE", "Тишина"), VorbisComment("ARTIST", "A"), VorbisComment("artist", "B"),
                VorbisComment("ALBUMARTIST", "Fleur"), VorbisComment("TRACKNUMBER", "2"), VorbisComment("DATE", "2005")
            ),
            id3 = false
        )
        assertEquals("Тишина", tags.title)
        assertEquals("A; B", tags.artist)
        assertEquals("Fleur", tags.albumArtist)
        assertEquals(2, tags.trackNo)
        assertEquals(2005, tags.year)
    }

    @Test
    fun id3v1FillsOnlyWhatIsMissing() {
        val v1 = Id3v1Tag("Беспечный ангел", "Ария", "Химера", 2001, null, 7, "Other")
        val merged = Media3Tags.withId3v1(TrackTags(artist = "Kept"), v1)
        assertEquals(TrackTags("Беспечный ангел", "Kept", null, "Химера", null, 7, 2001, "Other"), merged)
        assertEquals(TrackTags(title = "x"), Media3Tags.withId3v1(TrackTags(title = "x"), null))
    }

    @Test
    fun picturesAreDetected() {
        assertTrue(Media3Tags.hasPicture(listOf(ApicFrame("image/png", "", 3, ByteArray(1)))))
        assertTrue(Media3Tags.hasPicture(listOf(PictureFrame(3, "image/png", "", 1, 1, 8, 0, ByteArray(1)))))
        assertFalse(Media3Tags.hasPicture(listOf(frame("TIT2", "x"))))
    }

    @Test
    fun numbersAndFormatNames() {
        assertNull(Media3Tags.map(listOf(frame("TRCK", "0")), id3 = true).trackNo)
        assertEquals("alac", Media3TagReader.formatName("audio/alac"))
        assertEquals("mp3", Media3TagReader.formatName("audio/mpeg"))
        assertEquals("aac", Media3TagReader.formatName("audio/mp4a-latm"))
        assertEquals("flac", Media3TagReader.formatName("audio/flac"))
    }
}
