package org.tegro.yamp.local

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tegro.yamp.data.provider.local.LocalSources
import org.tegro.yamp.data.provider.local.LocalVolume
import org.tegro.yamp.data.provider.local.SafSourceFs
import org.tegro.yamp.data.provider.local.VolumeMonitor
import org.tegro.yamp.data.provider.local.db.SourceEntity
import org.tegro.yamp.local.LocalStorageInstrumentedTest.Companion.GRANTED
import org.tegro.yamp.local.LocalTestDevice.context

/**
 * Android 9–10 and a USB drive (D93 as built): the drive is mounted for the system only (`/mnt/media_rw/<uuid>`), so
 * YAMP reads it through a SAF tree taken in the system picker, opened at the volume's root. On the emulator the
 * virtual disk stands in for the drive (it is an SD card there, so its mount point is also readable — the tree must
 * still win). The grant survives the volume going and coming back; the volume id is the same UUID in both back-ends.
 */
@RunWith(AndroidJUnit4::class)
class LocalLegacyTreeInstrumentedTest {

    @Test
    fun aVolumeRootPickedInTheSystemPickerIsReadThroughSafAcrossARemount() {
        assumeTrue("Android 9–10 only", Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q)
        val uuid = LocalTestDevice.sdVolumeId()
        LocalTestDevice.pushTree(uuid, "$GRANTED/tree")
        val monitor = VolumeMonitor(context)
        assertEquals(LocalVolume.FILE_ACCESS_OK, monitor.volume(uuid)!!.fileAccess)

        // The root, as the chooser's "Choose in the system picker" opens it
        val tree = LocalTestDevice.grantTree(uuid, "")
        assertEquals(LocalSources.volumeRootUri(uuid), android.provider.DocumentsContract.buildDocumentUri(LocalSources.EXTERNAL_STORAGE_AUTHORITY, "$uuid:"))
        val path = checkNotNull(LocalSources.treePath(tree))
        assertEquals(uuid, path.volumeId)
        assertEquals("", path.relPath)

        val source = SourceEntity(volumeId = uuid, rootRel = "", treeUri = tree.toString(), addedAt = 0)
        val granted: (String) -> Boolean = { uri -> context.contentResolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isReadPermission } }
        assertEquals(LocalSources.SourceAccess.TREE, LocalSources.accessOf(source, Build.VERSION.SDK_INT, granted, readable = true))
        val saf = SafSourceFs(context.contentResolver, tree, uuid)
        assertTrue(saf.list("").orEmpty().any { it.name == GRANTED && it.isDirectory })
        assertTrue(saf.list("$GRANTED/tree").orEmpty().any { it.name == "Fleur" })

        try {
            LocalTestDevice.setMounted(uuid, mounted = false, monitor)
        } finally {
            LocalTestDevice.setMounted(uuid, mounted = true, monitor)
        }
        // Re-inserted: the same grant, the same document ids
        assertTrue(granted(tree.toString()))
        assertTrue(saf.list("$GRANTED/tree").orEmpty().any { it.name == "Fleur" })
    }
}
