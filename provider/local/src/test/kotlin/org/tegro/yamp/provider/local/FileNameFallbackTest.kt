package org.tegro.yamp.provider.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileNameFallbackTest {

    @Test
    fun trackNumberPrefixesAreStripped() {
        assertEquals(1 to "Song", FileNameFallback.parse("01 - Song"))
        assertEquals(1 to "Song", FileNameFallback.parse("01. Song"))
        assertEquals(1 to "Song", FileNameFallback.parse("1_Song"))
        assertEquals(3 to "Song", FileNameFallback.parse("3) Song"))
        assertEquals(12 to "Song", FileNameFallback.parse("12-Song"))
        assertEquals(1 to "Song", FileNameFallback.parse("01 Song"))
        assertEquals(105 to "Глава 105", FileNameFallback.parse("105 - Глава 105"))
        assertEquals(2 to "Отречение", FileNameFallback.parse("02 – Отречение".replace('–', '-')))
    }

    @Test
    fun namesThatMerelyStartWithANumberStayWhole() {
        assertEquals(null to "99 Luftballons", FileNameFallback.parse("99 Luftballons"))
        assertEquals(null to "1979 - Smashing", FileNameFallback.parse("1979 - Smashing"))
        assertEquals(null to "Song 2", FileNameFallback.parse("Song 2"))
        assertEquals(null to "01", FileNameFallback.parse("01"))
        assertEquals(null to "01 -", FileNameFallback.parse("01 -"))
    }

    @Test
    fun baseNameDropsOnlyTheLastExtension() {
        assertEquals("a.b", FileNameFallback.baseName("a.b.mp3"))
        assertEquals("noext", FileNameFallback.baseName("noext"))
        assertEquals(".hidden", FileNameFallback.baseName(".hidden"))
    }

    @Test
    fun anUntaggedYouTubeDownloadGetsTitleAndFolderAlbum() {
        val tags = FileNameFallback.apply("Music/YouTube/Artist - Some Song (Official Video).m4a", TrackTags())
        assertEquals("Artist - Some Song (Official Video)", tags.title)
        assertEquals("YouTube", tags.album)
        assertNull(tags.trackNo)
        assertNull(tags.artist)
        assertNull(tags.disc)
    }

    @Test
    fun anAudiobookChapterGetsItsNumber() {
        val tags = FileNameFallback.apply("Книги/Мастер и Маргарита/07 - Глава седьмая.mp3", TrackTags())
        assertEquals("Глава седьмая", tags.title)
        assertEquals(7, tags.trackNo)
        assertEquals("Мастер и Маргарита", tags.album)
    }

    @Test
    fun aDiscFolderGivesTheDiscAndTheParentAlbum() {
        val tags = FileNameFallback.apply("Fleur/Box/CD2/03 - Song.flac", TrackTags())
        assertEquals("Box", tags.album)
        assertEquals(2, tags.disc)
        assertEquals(3, tags.trackNo)
    }

    @Test
    fun presentTagsWin() {
        val read = TrackTags(title = "Real", album = "Tagged", trackNo = 9, disc = 1, artist = "A")
        assertEquals(read, FileNameFallback.apply("X/CD2/01 - Other.mp3", read))
    }

    @Test
    fun blankTagsCountAsMissing() {
        val tags = FileNameFallback.apply("Dir/02 - Name.mp3", TrackTags(title = "  ", album = "", artist = " ", genre = ""))
        assertEquals("Name", tags.title)
        assertEquals("Dir", tags.album)
        assertNull(tags.artist)
        assertNull(tags.genre)
    }

    @Test
    fun aTagTrackNumberWinsButTheTitleStillComesFromTheName() {
        val tags = FileNameFallback.apply("Dir/02 - Name.mp3", TrackTags(trackNo = 5))
        assertEquals(5, tags.trackNo)
        assertEquals("Name", tags.title)
    }

    @Test
    fun aFileAtTheVolumeRootHasNoAlbum() {
        val tags = FileNameFallback.apply("song.mp3", TrackTags())
        assertEquals("song", tags.title)
        assertNull(tags.album)
    }
}
