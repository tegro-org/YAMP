package org.tegro.yamp.data.provider.local

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.provider.local.LocalSources.SourceAccess
import org.tegro.yamp.data.provider.local.db.SourceEntity
import org.tegro.yamp.provider.local.VolumeMounts
import java.io.File

/**
 * Which back-end reads a source (D93 as built): a USB drive on Android 9 is a tree source read through SAF, since its
 * mount point is not readable by apps; a chooser folder is a path source read with the file API.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalSourceAccessTest {
    private val tree = SourceEntity(volumeId = USB, rootRel = "Music", treeUri = "content://com.android.externalstorage.documents/tree/$USB%3AMusic", addedAt = 0)
    private val path = SourceEntity(volumeId = LocalVolume.PRIMARY, rootRel = "Music", treeUri = null, addedAt = 0)

    @Test
    fun `a tree source is read through its grant on API 28 even when the mount point is unreadable`() {
        assertEquals(SourceAccess.TREE, LocalSources.accessOf(tree, API_28, granted = { true }, readable = false))
        assertEquals(SourceAccess.TREE, LocalSources.accessOf(tree, API_28, granted = { true }, readable = true))
        assertEquals(SourceAccess.TREE, LocalSources.accessOf(tree, API_34, granted = { true }, readable = false))
    }

    @Test
    fun `a tree source without its grant can't be read, never falls back to the file API`() {
        assertNull(LocalSources.accessOf(tree, API_28, granted = { false }, readable = true))
        assertNull(LocalSources.accessOf(tree, API_34, granted = { false }, readable = true))
    }

    @Test
    fun `a path source is read with the file API on API 29 and lower while readable`() {
        assertEquals(SourceAccess.PATH, LocalSources.accessOf(path, API_28, granted = { false }, readable = true))
        assertEquals(SourceAccess.PATH, LocalSources.accessOf(path, API_29, granted = { false }, readable = true))
        assertNull(LocalSources.accessOf(path, API_28, granted = { false }, readable = false))
        assertNull(LocalSources.accessOf(path, API_34, granted = { true }, readable = true))
    }

    @Test
    fun `the system picker starts at the volume root document`() {
        assertEquals("content://com.android.externalstorage.documents/document/$USB%3A", LocalSources.volumeRootUri(USB).toString())
    }

    @Test
    fun `the system picker can start at the folder being viewed`() {
        assertEquals(
            "content://com.android.externalstorage.documents/document/$USB%3AMusic%2FFleur",
            LocalSources.volumeDocumentUri(USB, "Music/Fleur").toString()
        )
        assertEquals(
            "content://com.android.externalstorage.documents/document/$USB%3A",
            LocalSources.volumeDocumentUri(USB, "").toString()
        )
    }

    @Test
    fun `journal lines hash volume ids and paths`() {
        val line = LocalJournal.volume(LocalVolume(USB, "USB drive", mounted = true, directory = null, removable = true, fileAccess = "ENOENT"))
        assertEquals("${LocalJournal.vol(USB)} removable mounted file=ENOENT", line)
        assertFalse(USB in line)
        assertEquals("primary", LocalJournal.vol(LocalVolume.PRIMARY))
        assertEquals("root", LocalJournal.path(""))
        assertFalse("Music" in LocalJournal.path("Music"))
        assertEquals(LocalJournal.path("Music"), LocalJournal.path("Music"))
        assertEquals("primary mounted file=ok", LocalJournal.volume(LocalVolume(LocalVolume.PRIMARY, "", true, File("/x"), fileAccess = "ok")))
    }

    @Test
    fun `the mount line names every candidate, hides the uuid and keeps the vendor's mount name`() {
        val candidates = VolumeMounts.candidates(USB, storageChildren = listOf("emulated", "udisk1"), belongsToVolume = { it.name == "udisk1" })
        val mount = VolumeMounts.select(candidates) { if (it.name == "udisk1") VolumeMounts.OK else "ENOENT" }
        val line = LocalJournal.mountTrace(LocalVolume(USB, "USB drive", true, mount.dir, true, mount.result, mount))!!
        val vol = LocalJournal.vol(USB)
        assertEquals("MOUNT $vol uuid:$vol=ENOENT child:udisk1=ok media_rw:$vol=ENOENT → child:udisk1", line)
        assertFalse(USB in line)
        assertNull(LocalJournal.mountTrace(LocalVolume(LocalVolume.PRIMARY, "", true, File("/x"))))
    }

    private companion object {
        const val USB = "1A2B-3C4D"
        const val API_28 = 28
        const val API_29 = 29
        const val API_34 = 34
    }
}
