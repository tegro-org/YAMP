package org.tegro.yamp.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class ZipAssemblerTest {

    private fun entries(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = zip.readBytes().decodeToString()
            }
        }
        return out
    }

    @Test
    fun failedItemsDoNotAbortTheZipAndAllAreInTheManifest() {
        val buffer = ByteArrayOutputStream()
        val results = ZipAssembler(buffer).run {
            add("audio.txt", "focus stack".toByteArray())
            fail("yamp/yamp.log", "exit 1: run-as: package not debuggable")
            add("logcat.txt", "line".toByteArray(), truncated = true)
            finish(listOf("device" to "KX11"))
        }
        val zip = entries(buffer.toByteArray())
        assertEquals(setOf("audio.txt", "logcat.txt", ZipAssembler.MANIFEST), zip.keys)
        assertEquals("focus stack", zip["audio.txt"])
        assertTrue(zip["logcat.txt"]!!.contains("[truncated by YAMP Diag]"))
        val manifest = zip[ZipAssembler.MANIFEST]!!
        assertTrue(manifest.startsWith("device: KX11\n"))
        assertTrue(manifest.contains("audio.txt: ok (11 B)"))
        assertTrue(manifest.contains("yamp/yamp.log: failed — exit 1: run-as: package not debuggable"))
        assertTrue(manifest.contains("logcat.txt: truncated (4 B)"))
        assertEquals(3, results.size)
    }
}
