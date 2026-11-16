package org.tegro.yamp.data.diagnostics

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/** Logs go out as files with the read grant, so file-only targets (LocalSend) receive them (F7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FileShareTest {

    private val one = Uri.parse("content://org.tegro.music.yamp.update_provider/cached_files/shared/yamp-journal.txt")
    private val two = Uri.parse("content://org.tegro.music.yamp.update_provider/logs/yamp.log")

    @Test
    fun oneFileIsSentWithStreamClipDataAndTheReadGrant() {
        val intent = FileShare.intent(listOf(one), subject = "YAMP journal", text = "line")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        @Suppress("DEPRECATION")
        assertEquals(one, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(1, intent.clipData!!.itemCount)
        assertEquals(one, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("line", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("YAMP journal", intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun severalFilesGoAsSendMultipleWithEveryUriInTheClip() {
        val intent = FileShare.intent(listOf(one, two), subject = "YAMP diagnostic log")
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        @Suppress("DEPRECATION")
        assertEquals(listOf(one, two), intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(2, intent.clipData!!.itemCount)
        assertEquals(two, intent.clipData!!.getItemAt(1).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun largeTextGoesOnlyAsTheFile() {
        val intent = FileShare.intent(listOf(one), subject = null, text = "x".repeat(200_000))
        assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(intent.hasExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun theTextFileLandsInTheSharedCacheFolderWithAStampedName() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = FileShare.writeTextFile(context, "yamp-journal", "hello", now = Date(0))
        assertEquals("shared", file.parentFile!!.name)
        assertTrue(file.name.startsWith("yamp-journal-") && file.name.endsWith(".txt"))
        assertEquals("hello", file.readText())
    }
}
