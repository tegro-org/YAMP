package org.tegro.yamp.data.provider.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class LocalArtWriteTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `callers making the same thumbnail at once never share a file and leave no part behind`() {
        val target = File(folder.root, "t1-abc-256.jpg")
        val workers = 8
        val pool = Executors.newFixedThreadPool(workers)
        val start = CountDownLatch(1)
        val results = (0 until workers).map {
            pool.submit<Boolean> {
                start.await()
                LocalArt.writeAtomically(target) { part ->
                    part.writeText("picture")
                    Thread.sleep(20)
                    true
                }
            }
        }
        start.countDown()
        assertTrue(results.all { it.get() })
        pool.shutdown()
        assertEquals("picture", target.readText())
        assertEquals(listOf(target.name), folder.root.list()!!.toList())
    }

    @Test
    fun `a failed write leaves neither the target nor a part`() {
        val target = File(folder.root, "t2-abc-256.jpg")
        assertTrue(!LocalArt.writeAtomically(target) { false })
        assertEquals(emptyList<String>(), folder.root.list()!!.toList())
    }
}
