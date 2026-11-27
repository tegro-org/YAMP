package org.tegro.yamp.provider.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumGroupingTest {

    private fun track(relPath: String, tags: TrackTags = TrackTags(), volume: String = "V1") =
        GroupTrack(LocalPath(volume, relPath), FileNameFallback.apply(relPath, tags))

    private fun names(album: LocalAlbum) = album.tracks.map { LocalRefs.path(it)!!.relPath.substringAfterLast('/') }

    @Test
    fun discFolderNames() {
        assertEquals(1, AlbumGrouping.discNumber("CD1"))
        assertEquals(2, AlbumGrouping.discNumber("CD 2"))
        assertEquals(3, AlbumGrouping.discNumber("Disc 3"))
        assertEquals(4, AlbumGrouping.discNumber("disk_4"))
        assertEquals(5, AlbumGrouping.discNumber("Диск 5"))
        assertEquals(6, AlbumGrouping.discNumber("cd06"))
        assertEquals(1, AlbumGrouping.discNumber("CD1 - Bonus"))
        assertNull(AlbumGrouping.discNumber("CDs"))
        assertNull(AlbumGrouping.discNumber("Discovery"))
        assertNull(AlbumGrouping.discNumber("CD100"))
        assertNull(AlbumGrouping.discNumber("Album"))
    }

    @Test
    fun discFoldersCollapseIntoTheirParent() {
        assertEquals("Artist/Album", AlbumGrouping.albumDir("Artist/Album/CD1"))
        assertEquals("Artist/Album", AlbumGrouping.albumDir("Artist/Album"))
        assertEquals("", AlbumGrouping.albumDir("CD1"))
        assertEquals("", AlbumGrouping.albumDir(""))
    }

    @Test
    fun aTaggedAlbumWithAlbumArtistIsOneAcrossFolders() {
        val tags = TrackTags(album = "Эйфория", albumArtist = "Флёр")
        val albums = AlbumGrouping.group(
            listOf(
                track("a/01.mp3", tags.copy(title = "One", trackNo = 1)),
                track("b/02.mp3", tags.copy(title = "Two", trackNo = 2, albumArtist = "флер "))
            )
        )
        assertEquals(1, albums.size)
        assertEquals("Эйфория", albums[0].title)
        assertEquals("Флёр", albums[0].artist)
        assertEquals(listOf("01.mp3", "02.mp3"), names(albums[0]))
    }

    @Test
    fun aCompilationFolderWithoutAlbumArtistStaysOneAlbum() {
        val albums = AlbumGrouping.group(
            listOf(
                track("Hits 2005/01 - a.mp3", TrackTags(artist = "Artist A", album = "Hits 2005")),
                track("Hits 2005/02 - b.mp3", TrackTags(artist = "Artist B", album = "Hits 2005")),
                track("Hits 2005/03 - c.mp3", TrackTags(artist = "Artist C", album = "Hits 2005"))
            )
        )
        assertEquals(1, albums.size)
        assertNull(albums[0].artist)
        assertTrue(albums[0].various)
    }

    @Test
    fun theSameAlbumTitleInTwoFoldersWithoutAlbumArtistIsTwoAlbums() {
        val albums = AlbumGrouping.group(
            listOf(
                track("Artist A/Greatest Hits/01.mp3", TrackTags(artist = "Artist A", album = "Greatest Hits")),
                track("Artist B/Greatest Hits/01.mp3", TrackTags(artist = "Artist B", album = "Greatest Hits"))
            )
        )
        assertEquals(2, albums.size)
        assertEquals(setOf("Artist A", "Artist B"), albums.map { it.artist }.toSet())
        assertFalse(albums.any { it.various })
    }

    @Test
    fun twoTaggedAlbumsInOneFolderStayTwo() {
        val albums = AlbumGrouping.group(
            listOf(
                track("Downloads/a.mp3", TrackTags(artist = "X", album = "First")),
                track("Downloads/b.mp3", TrackTags(artist = "X", album = "Second"))
            )
        )
        assertEquals(listOf("First", "Second"), albums.map { it.title })
    }

    @Test
    fun discFoldersMakeOneAlbumOrderedByDiscThenTrack() {
        val albums = AlbumGrouping.group(
            listOf(
                track("Box/CD2/01 - d2t1.mp3"),
                track("Box/CD1/02 - d1t2.mp3"),
                track("Box/CD1/01 - d1t1.mp3"),
                track("Box/CD10/01 - d10t1.mp3")
            )
        )
        assertEquals(1, albums.size)
        assertEquals("Box", albums[0].title)
        assertEquals(listOf("01 - d1t1.mp3", "02 - d1t2.mp3", "01 - d2t1.mp3", "01 - d10t1.mp3"), names(albums[0]))
    }

    @Test
    fun untaggedFilesGroupByFolderInNaturalOrder() {
        val albums = AlbumGrouping.group(
            listOf(
                track("Book/Chapter 10.mp3"),
                track("Book/Chapter 2.mp3"),
                track("Book/Chapter 1.mp3")
            )
        )
        assertEquals(1, albums.size)
        assertEquals("Book", albums[0].title)
        assertNull(albums[0].artist)
        assertFalse(albums[0].various)
        assertEquals(listOf("Chapter 1.mp3", "Chapter 2.mp3", "Chapter 10.mp3"), names(albums[0]))
    }

    @Test
    fun tracksWithoutNumbersGoAfterNumberedOnes() {
        val album = AlbumGrouping.group(listOf(track("A/zz.mp3", TrackTags(trackNo = 1)), track("A/aa.mp3"))).single()
        assertEquals(listOf("zz.mp3", "aa.mp3"), names(album))
    }

    @Test
    fun missingAlbumAtTheVolumeRootIsOneUntitledAlbumPerVolume() {
        val albums = AlbumGrouping.group(listOf(track("a.mp3"), track("b.mp3"), track("c.mp3", volume = "V2")))
        assertEquals(2, albums.size)
        assertTrue(albums.all { it.title.isEmpty() })
    }

    @Test
    fun theSameFolderOnTwoVolumesIsTwoAlbums() {
        val key1 = AlbumGrouping.albumKey(LocalPath("V1", "Music/A/01.mp3"), TrackTags(album = "A"))
        val key2 = AlbumGrouping.albumKey(LocalPath("V2", "Music/A/01.mp3"), TrackTags(album = "A"))
        assertNotEquals(key1, key2)
    }

    @Test
    fun theSameTaggedAlbumOnTwoVolumesIsTwoAlbums() {
        val tags = TrackTags(album = "The Wall", albumArtist = "Pink Floyd")
        val key1 = AlbumGrouping.albumKey(LocalPath("V1", "Music/The Wall/01.mp3"), tags)
        val key2 = AlbumGrouping.albumKey(LocalPath("V2", "Music/The Wall/01.mp3"), tags)
        assertNotEquals(key1, key2)
        val albums = AlbumGrouping.group(
            listOf(track("Music/The Wall/01.mp3", tags), track("Music/The Wall/01.mp3", tags, volume = "V2"))
        )
        assertEquals(2, albums.size)
        assertTrue(albums.all { it.title == "The Wall" && it.tracks.size == 1 })
    }

    @Test
    fun theSameTaggedAlbumInTwoFoldersOfOneVolumeIsOneKey() {
        val tags = TrackTags(album = "The Wall", albumArtist = "Pink Floyd")
        val key1 = AlbumGrouping.albumKey(LocalPath("V1", "Music/The Wall/CD1/01.mp3"), tags)
        val key2 = AlbumGrouping.albumKey(LocalPath("V1", "Downloads/wall/02.mp3"), tags)
        assertEquals(key1, key2)
    }

    @Test
    fun theAlbumKeyIgnoresCaseAndSpaces() {
        val a = AlbumGrouping.albumKey(LocalPath("V1", "x/1.mp3"), TrackTags(album = "The  Wall", albumArtist = "Pink Floyd"))
        val b = AlbumGrouping.albumKey(LocalPath("V1", "y/1.mp3"), TrackTags(album = "the wall ", albumArtist = "PINK FLOYD"))
        assertEquals(a, b)
    }

    @Test
    fun theYearIsTheFirstTrackWithOne() {
        val album = AlbumGrouping.group(
            listOf(track("A/02.mp3", TrackTags(album = "A", year = 2001)), track("A/01.mp3", TrackTags(album = "A")))
        ).single()
        assertEquals(2001, album.year)
        assertEquals(LocalRefs.album(album.key), album.ref)
    }

    @Test
    fun artistsAreSplitAtSemicolonsAndFeat() {
        assertEquals(listOf("A", "B"), AlbumGrouping.splitArtists("A; B"))
        assertEquals(listOf("Би-2", "Земфира"), AlbumGrouping.splitArtists("Би-2 feat. Земфира"))
        assertEquals(listOf("A", "B"), AlbumGrouping.splitArtists("A ft. B"))
        assertEquals(listOf("A", "B"), AlbumGrouping.splitArtists("A Feat B"))
        assertEquals(listOf("A", "B", "C"), AlbumGrouping.splitArtists("A (feat. B); C"))
        assertEquals(listOf("A", "B"), AlbumGrouping.splitArtists("A [featuring B]"))
        assertEquals(listOf("A"), AlbumGrouping.splitArtists("A; a; ;"))
        assertEquals(listOf("A", "B"), AlbumGrouping.splitArtists("A\u0000B"))
    }

    @Test
    fun bandNamesWithAmpersandsAndSlashesAreKept() {
        assertEquals(listOf("Simon & Garfunkel"), AlbumGrouping.splitArtists("Simon & Garfunkel"))
        assertEquals(listOf("AC/DC"), AlbumGrouping.splitArtists("AC/DC"))
        assertEquals(listOf("Earth, Wind & Fire"), AlbumGrouping.splitArtists("Earth, Wind & Fire"))
        assertEquals(listOf("Featurette"), AlbumGrouping.splitArtists("Featurette"))
        assertEquals(emptyList<String>(), AlbumGrouping.splitArtists(null))
        assertEquals(emptyList<String>(), AlbumGrouping.splitArtists("  "))
    }

    @Test
    fun libraryArtistsPreferTheAlbumArtist() {
        assertEquals(listOf("Various"), AlbumGrouping.libraryArtists(TrackTags(artist = "A feat. B", albumArtist = "Various")))
        assertEquals(listOf("A", "B"), AlbumGrouping.libraryArtists(TrackTags(artist = "A feat. B")))
        assertEquals(emptyList<String>(), AlbumGrouping.libraryArtists(TrackTags()))
    }
}
