package org.tegro.yamp.provider.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFormatsTest {

    @Test
    fun extensionsAreLowercaseAndIgnoreALeadingDot() {
        assertEquals("flac", AudioFormats.extension("01 - Song.FLAC"))
        assertEquals("mp3", AudioFormats.extension("a.b.mp3"))
        assertEquals("", AudioFormats.extension(".nomedia"))
        assertEquals("", AudioFormats.extension("README"))
    }

    @Test
    fun candidatesArePlayableAndKnownUnsupportedFormatsOnly() {
        assertTrue(AudioFormats.isCandidate("a.mp3"))
        assertTrue(AudioFormats.isCandidate("book.m4b"))
        assertTrue(AudioFormats.isCandidate("a.ape"))
        assertTrue(AudioFormats.isCandidate("a.WV"))
        assertFalse(AudioFormats.isCandidate("a.cue"))
        assertFalse(AudioFormats.isCandidate("cover.jpg"))
        assertFalse(AudioFormats.isCandidate(".nomedia"))
        assertTrue(AudioFormats.isPlayable("a.opus"))
        assertFalse(AudioFormats.isPlayable("a.dsf"))
    }

    @Test
    fun theNamedCoverWinsInPriorityOrder() {
        assertEquals("Cover.JPG", FolderArt.pick(listOf("back.jpg", "folder.png", "Cover.JPG", "01.mp3")))
        assertEquals("folder.png", FolderArt.pick(listOf("back.jpg", "folder.png", "front.webp")))
        assertEquals("front.webp", FolderArt.pick(listOf("back.jpg", "front.webp", "album.jpg")))
        assertEquals("album.jpeg", FolderArt.pick(listOf("back.jpg", "album.jpeg")))
    }

    @Test
    fun aSingleUnnamedImageIsTheCoverSeveralAreNot() {
        assertEquals("scan.jpg", FolderArt.pick(listOf("scan.jpg", "01.mp3", "notes.txt")))
        assertNull(FolderArt.pick(listOf("scan1.jpg", "scan2.jpg")))
        assertNull(FolderArt.pick(listOf("01.mp3", "cover.gif")))
    }
}
