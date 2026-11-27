package org.tegro.yamp.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tegro.yamp.data.provider.local.FileSourceFs
import org.tegro.yamp.data.provider.local.SafSourceFs
import org.tegro.yamp.data.provider.local.SourceFs
import org.tegro.yamp.data.provider.local.VolumeMonitor
import org.tegro.yamp.local.LocalTestDevice.context
import java.io.File

/**
 * Step 3 "done when" (docs/phase-4d-local-files.md): a folder on the SD card lists the same through both back-ends,
 * and the volume state follows unmount / mount. The File back-end reads the app's own folder on the card (API 30+
 * gives a plain path nothing else without a media permission); SAF reads a public folder through a persisted tree
 * grant, as the app will on API 30+.
 */
@RunWith(AndroidJUnit4::class)
class LocalStorageInstrumentedTest {

    @Test
    fun bothBackEndsListTheTestTreeTheSame() {
        val uuid = LocalTestDevice.sdVolumeId()
        LocalTestDevice.pushTree(uuid, "$GRANTED/tree")
        val tree = LocalTestDevice.grantTree(uuid, GRANTED)
        val appDir = LocalTestDevice.appDirOn(uuid)
        LocalTestDevice.copyTree(File(appDir, "tree"))

        val saf = SafSourceFs(context.contentResolver, tree, uuid)
        val file = FileSourceFs(File("/storage/$uuid"))
        val fileRoot = File(appDir, "tree").path.removePrefix("/storage/$uuid/")

        val expected = LocalTestDevice.treeFiles().keys
        val viaSaf = listRecursively(saf, "$GRANTED/tree")
        val viaFile = listRecursively(file, fileRoot)
        assertEquals(expected, viaSaf.filterValues { it >= 0 }.keys)
        assertEquals(viaFile, viaSaf)
        // Folders are listed as folders (size -1 here), files with their size
        assertEquals(-1L, viaSaf.getValue("Fleur"))
        assertTrue(viaSaf.getValue("Old/track1.mp3") > 0)

        // A folder that doesn't exist is a failure, never an empty listing
        assertNull(saf.list("$GRANTED/tree/nope"))
        assertNull(file.list("$fileRoot/nope"))
    }

    @Test
    fun volumeStateFollowsUnmountAndMount() {
        val uuid = LocalTestDevice.sdVolumeId()
        LocalTestDevice.pushTree(uuid, "$GRANTED/tree")
        val tree = LocalTestDevice.grantTree(uuid, GRANTED)
        val saf = SafSourceFs(context.contentResolver, tree, uuid)
        val monitor = VolumeMonitor(context)
        assertTrue(monitor.isMounted(uuid))
        assertTrue(monitor.volume(uuid)!!.label.isNotBlank())
        try {
            LocalTestDevice.setMounted(uuid, mounted = false, monitor)
            assertFalse(monitor.isMounted(uuid))
            // An unmounted volume can't be listed: the scanner sees a failure, not an empty folder
            assertNull(saf.list("$GRANTED/tree"))
        } finally {
            LocalTestDevice.setMounted(uuid, mounted = true, monitor)
        }
        assertTrue(monitor.isMounted(uuid))
        assertTrue(saf.list("$GRANTED/tree").orEmpty().any { it.name == "Fleur" })
    }

    /** Every entry under [root]: path relative to it → size (−1 for a folder). */
    private fun listRecursively(fs: SourceFs, root: String): Map<String, Long> {
        val out = sortedMapOf<String, Long>()
        fun walk(dir: String, rel: String) {
            for (entry in checkNotNull(fs.list(dir)) { "can't list $dir" }) {
                val path = if (rel.isEmpty()) entry.name else "$rel/${entry.name}"
                if (entry.isDirectory) {
                    out[path] = -1
                    walk("$dir/${entry.name}", path)
                } else {
                    out[path] = entry.size
                }
            }
        }
        walk(root, "")
        return out
    }

    companion object {
        /** The folder the SAF grant covers; the scan tests and the benchmark use subfolders of it. */
        const val GRANTED = "YampTest"
    }
}
