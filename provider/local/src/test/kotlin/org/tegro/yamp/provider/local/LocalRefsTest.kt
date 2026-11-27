package org.tegro.yamp.provider.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef

class LocalRefsTest {

    @Test
    fun aTrackRefIsTheVolumeAndThePathReadable() {
        val ref = LocalRefs.track("1A2B-3C4D", "Fleur/2005 - Эйфория/03 - Отречение.flac")
        assertEquals("local:track:1A2B-3C4D/Fleur/2005 - Эйфория/03 - Отречение.flac", ref.value)
        assertEquals(LocalPath("1A2B-3C4D", "Fleur/2005 - Эйфория/03 - Отречение.flac"), LocalRefs.path(ref))
    }

    @Test
    fun reservedCharactersRoundTrip() {
        val paths = listOf(
            "AC:DC/Back in Black.mp3",
            "100% Hits | Vol 1/01.mp3",
            "a%3Ab/already%escaped.mp3",
            "Кино/Группа крови: live/Звезда по имени Солнце.mp3",
            "  spaces  /  and tabs\t.mp3",
            "日本/曲.flac",
            "emoji 🎵/track.ogg"
        )
        for (path in paths) {
            val ref = LocalRefs.track("primary", path)
            assertEquals(path, LocalRefs.path(ref)?.relPath)
            assertEquals(LocalPath("primary", path), LocalRefs.path(ref.value))
            // the persisted form survives the domain parser and splits only where it should
            val parsed = MediaRef.parse(ref.value)!!
            assertEquals("local", parsed.provider)
            assertEquals("track", parsed.kind)
            assertFalse(ref.id, ref.id.contains(':') || ref.id.contains('|'))
        }
    }

    @Test
    fun escapedFormIsAsDocumented() {
        assertEquals("local:track:primary/AC%3ADC/a%7Cb%25.mp3", LocalRefs.track("primary", "AC:DC/a|b%.mp3").value)
    }

    @Test
    fun theVolumeIdIsEscapedIncludingSlash() {
        val ref = LocalRefs.folder("odd/vol:1", "Music")
        assertEquals("local:folder:odd%2Fvol%3A1/Music", ref.value)
        assertEquals(LocalPath("odd/vol:1", "Music"), LocalRefs.path(ref))
    }

    @Test
    fun theVolumeIsPartOfTheIdentity() {
        assertNotEquals(LocalRefs.track("AAAA-1111", "Music/a.mp3"), LocalRefs.track("BBBB-2222", "Music/a.mp3"))
    }

    @Test
    fun aFolderMayBeTheVolumeRootATrackMayNot() {
        val root = LocalRefs.folder("primary", "")
        assertEquals("local:folder:primary/", root.value)
        assertEquals(LocalPath("primary", ""), LocalRefs.path(root))
        assertNull(LocalRefs.path("local:track:primary/"))
        assertThrows { LocalRefs.track("primary", "") }
    }

    @Test
    fun badPathsAreRefusedOnBuildAndParse() {
        for (bad in listOf("/abs.mp3", "a//b.mp3", "a/../b.mp3", "./a.mp3", "a/", "..")) {
            assertThrows { LocalRefs.track("primary", bad) }
            assertNull(bad, LocalRefs.path("local:track:primary/$bad"))
        }
        assertThrows { LocalRefs.track("", "a.mp3") }
    }

    @Test
    fun malformedRefsParseToNull() {
        assertNull(LocalRefs.path("local:track:noslash.mp3"))
        assertNull(LocalRefs.path("local:track:/a.mp3"))
        assertNull(LocalRefs.path("local:track:primary/bad%2"))
        assertNull(LocalRefs.path("local:track:primary/bad%zz.mp3"))
        assertNull(LocalRefs.path("jellyfin@x:track:primary/a.mp3"))
        assertNull(LocalRefs.path("local:album:primary/a.mp3"))
        assertNull(LocalRefs.path("garbage"))
    }

    @Test
    fun unescapeIsStrict() {
        assertEquals("a:b", LocalRefs.unescape("a%3ab"))
        assertEquals("plain", LocalRefs.unescape("plain"))
        assertNull(LocalRefs.unescape("%"))
        assertNull(LocalRefs.unescape("%4"))
        assertNull(LocalRefs.unescape("%G0"))
    }

    @Test
    fun aLocalTrackRefIsATrackRef() {
        val value = LocalRefs.track("primary", "Music/a.mp3").value
        assertEquals(value, TrackRef.parse(value)?.value)
    }

    @Test
    fun albumAndArtistRefsAreStableShortHashes() {
        val album = LocalRefs.album("tag\u0000эйфория\u0000флёр")
        assertEquals(album, LocalRefs.album("tag\u0000эйфория\u0000флёр"))
        assertEquals("album", album.kind)
        assertEquals(16, album.id.length)
        assertTrue(album.id.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(album, LocalRefs.album("tag\u0000other\u0000флёр"))
    }

    @Test
    fun artistRefsIgnoreCaseSpacesAndYo() {
        assertEquals(LocalRefs.artist("Флёр"), LocalRefs.artist("  флер "))
        assertEquals(LocalRefs.artist("Pink  Floyd"), LocalRefs.artist("pink floyd"))
        assertNotEquals(LocalRefs.artist("Pink Floyd"), LocalRefs.artist("Pink"))
        assertEquals("artist", LocalRefs.artist("x").kind)
    }

    private fun assertThrows(block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $thrown", thrown is IllegalArgumentException)
    }

    @Test
    fun genreRefsIgnoreCaseAndSpaces() {
        assertEquals(LocalRefs.genre("Hard  Rock"), LocalRefs.genre(" hard rock"))
        assertEquals("genre", LocalRefs.genre("Jazz").kind)
        assertNotEquals(LocalRefs.genre("Jazz"), LocalRefs.genre("Rock"))
    }
}
