package org.tegro.yamp.provider.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** D99: the removable mount is found by what the system says it is, never by assuming the directory is the UUID. */
class VolumeMountsTest {

    private val uuid = "AE43-9FDE"
    private val storage = File("/storage")

    /** What the system maps: only [mapped] belongs to our volume. */
    private fun candidates(children: List<String>, mapped: Set<String> = emptySet(), own: File? = null) =
        VolumeMounts.candidates(uuid, own, children, { it.name in mapped })

    private fun probe(vararg readable: String): (File) -> String = { f ->
        if (f.path in readable) VolumeMounts.OK else "ENOENT"
    }

    @Test
    fun `a directory named by the vendor is used when the system maps it to the volume`() {
        val list = candidates(listOf("emulated", "self", "udisk1"), mapped = setOf("udisk1"))
        assertEquals(
            listOf("/storage/AE43-9FDE", "/storage/udisk1", "/mnt/media_rw/AE43-9FDE"),
            list.filter { it.source.accepted }.map { it.dir.path }
        )
        val selection = VolumeMounts.select(list, probe("/storage/udisk1"))
        assertEquals(File("/storage/udisk1"), selection.dir)
        assertEquals(VolumeMounts.OK, selection.result)
    }

    @Test
    fun `a directory named by the uuid wins over the children`() {
        val list = candidates(listOf("AE43-9FDE", "udisk1"), mapped = setOf("udisk1"))
        assertEquals(File("/storage/AE43-9FDE"), VolumeMounts.select(list, probe("/storage/AE43-9FDE", "/storage/udisk1")).dir)
        assertEquals(1, list.count { it.dir.path == "/storage/AE43-9FDE" })
    }

    @Test
    fun `an unreadable candidate is skipped for the next one`() {
        val list = candidates(listOf("udisk1"), mapped = setOf("udisk1"))
        val selection = VolumeMounts.select(list) { f ->
            when (f.path) {
                "/storage/udisk1" -> "EACCES"
                "/mnt/media_rw/AE43-9FDE" -> VolumeMounts.OK
                else -> "ENOENT"
            }
        }
        assertEquals(File("/mnt/media_rw/AE43-9FDE"), selection.dir)
        assertEquals(listOf("ENOENT", "EACCES", "ok"), selection.attempts.take(3).map { it.result })
    }

    @Test
    fun `a readable directory of another volume is probed but never chosen`() {
        val list = candidates(listOf("sdcard1", "udisk1"), mapped = setOf("udisk1"))
        val selection = VolumeMounts.select(list, probe("/storage/sdcard1"))
        assertNull(selection.dir)
        assertEquals(MountSource.OTHER_VOLUME, selection.attempts.single { it.readable }.candidate.source)
    }

    @Test
    fun `none readable is unavailable with the most actionable error`() {
        val list = candidates(listOf("udisk1"), mapped = setOf("udisk1"))
        val selection = VolumeMounts.select(list) { if (it.path == "/storage/udisk1") "EACCES" else "ENOENT" }
        assertNull(selection.dir)
        assertEquals("EACCES", selection.result)
        assertEquals(list.size, selection.attempts.size)
    }

    @Test
    fun `nothing present is ENOENT`() {
        val selection = VolumeMounts.select(candidates(emptyList())) { "ENOENT" }
        assertNull(selection.dir)
        assertEquals(VolumeMounts.ABSENT, selection.result)
    }

    @Test
    fun `the volume's own directory is tried first and a path is tried once`() {
        val own = File("/storage/AE43-9FDE")
        val list = candidates(listOf("udisk1"), mapped = setOf("udisk1"), own = own)
        assertEquals(MountSource.OWN, list.first().source)
        assertEquals(list.map { it.dir.path }.distinct(), list.map { it.dir.path })
    }
}
