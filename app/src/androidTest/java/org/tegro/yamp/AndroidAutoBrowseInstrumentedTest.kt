package org.tegro.yamp

import android.content.ComponentName
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.tegro.yamp.data.player.MusicService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit

/**
 * Browses MusicService the way Android Auto does (MediaBrowser, no Activity) and starts a radio
 * station from the tree. Radio needs no Yandex account.
 */
@RunWith(AndroidJUnit4::class)
class AndroidAutoBrowseInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var browser: MediaBrowser

    @Before
    fun connect() {
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        browser = MediaBrowser.Builder(context, token).buildAsync().get(10, TimeUnit.SECONDS)
    }

    @After
    fun disconnect() {
        instrumentation.runOnMainSync {
            browser.pause()
            browser.release()
        }
    }

    /** Browser calls must start on the main thread; the result is awaited on the test thread. */
    private fun <T : Any> call(request: MediaBrowser.() -> ListenableFuture<LibraryResult<T>>): LibraryResult<T> {
        lateinit var future: ListenableFuture<LibraryResult<T>>
        instrumentation.runOnMainSync { future = browser.request() }
        return future.get(10, TimeUnit.SECONDS)
    }

    private fun waitUntil(timeoutMs: Long, message: String, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) throw AssertionError("Timeout: $message")
            Thread.sleep(50)
        }
    }

    @Test
    fun rootListsTheAccountsThenRadioAndQueues() {
        val root = call { getLibraryRoot(null) }.value
        assertNotNull(root)
        val children = call { getChildren(root!!.mediaId, 0, 100, null) }.value.orEmpty()
        assertEquals(listOf("radio", "queues"), children.takeLast(2).map { it.mediaId })
        // The accounts' part (gap (d)): a folder per account, or the only account's rows
        assertTrue(children.dropLast(2).all { it.mediaId.startsWith("n|") })
        assertTrue(children.dropLast(2).all { it.mediaMetadata.isBrowsable == true || it.mediaMetadata.isPlayable == true })
    }

    /** Gap (d): root → (account) → Folders → a folder → "Play all" plays it; needs a signed-in Jellyfin account. */
    @Test
    fun aJellyfinFolderBrowsesAndPlaysWhole() {
        val top = call { getChildren("root", 0, 100, null) }.value.orEmpty()
        val folders = top.firstOrNull { it.mediaId.startsWith("n|fds|jellyfin:") }?.mediaId
            ?: top.firstOrNull { it.mediaId.startsWith("n|acc|jellyfin:") }?.let { account ->
                call { getChildren(account.mediaId, 0, 100, null) }.value.orEmpty().firstOrNull { it.mediaId.startsWith("n|fds|") }?.mediaId
            }
        org.junit.Assume.assumeTrue("a Jellyfin account is signed in", folders != null)
        val level = call { getChildren(folders!!, 0, 500, null) }.value.orEmpty()
        val folder = level.firstOrNull { it.mediaMetadata.isBrowsable == true }
        assertNotNull("the library has folders", folder)
        assertTrue("covers are content URIs", level.mapNotNull { it.mediaMetadata.artworkUri }.all { it.scheme == "content" })
        val inside = call { getChildren(folder!!.mediaId, 0, 500, null) }.value.orEmpty()
        // A cover reaches the car through the artwork provider (the account's header added there, never in the URI)
        (level + inside).firstNotNullOfOrNull { it.mediaMetadata.artworkUri }?.let { art ->
            val bytes = context.contentResolver.openInputStream(art)!!.use { it.readBytes() }
            assertNotNull("the cover decodes", android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }
        val playAll = inside.first()
        assertTrue(playAll.mediaId.startsWith("n|fdall|") && playAll.mediaMetadata.isPlayable == true)
        ActivityScenario.launch(MainActivity::class.java).use {
            instrumentation.runOnMainSync {
                browser.setMediaItem(playAll)
                browser.prepare()
                browser.play()
            }
            waitUntil(30_000, "the folder plays") {
                var ok = false
                instrumentation.runOnMainSync { ok = browser.isPlaying }
                ok
            }
            instrumentation.runOnMainSync { browser.pause() }
        }
    }

    /**
     * Phase 4d: "This device" in the car — its rows (Favourites · Albums · Folders), the volumes at the Folders root
     * (an absent one not playable), local covers through the artwork provider, a folder played whole. Needs a source.
     */
    @Test
    fun theLocalAccountBrowsesAndAFolderPlaysWhole() {
        val top = call { getChildren("root", 0, 100, null) }.value.orEmpty()
        val local = "local:device"
        val rows = if (top.any { it.mediaId == "n|fds|$local|" }) top.filter { it.mediaId.contains("|$local|") }
        else top.firstOrNull { it.mediaId == "n|acc|$local|" }?.let { call { getChildren(it.mediaId, 0, 100, null) }.value.orEmpty() }
        org.junit.Assume.assumeTrue("This device has a source", !rows.isNullOrEmpty())
        assertEquals(listOf("liked", "albs", "fds"), rows!!.map { it.mediaId.split('|')[1] })
        val level = call { getChildren("n|fds|$local|", 0, 500, null) }.value.orEmpty()
        assertTrue("the root lists folders", level.any { it.mediaMetadata.isBrowsable == true })
        assertTrue("no path in an id's node part", level.all { it.mediaId.split('|').size == 4 })
        val albums = call { getChildren("n|albs|$local|", 0, 500, null) }.value.orEmpty()
        assertTrue("albums from the index", albums.isNotEmpty())
        albums.firstNotNullOfOrNull { it.mediaMetadata.artworkUri }?.let { art ->
            assertEquals("content", art.scheme)
            assertTrue("no path in a cover URI", !art.toString().contains("Music"))
            val bytes = context.contentResolver.openInputStream(art)!!.use { it.readBytes() }
            assertNotNull("the cover decodes", android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }
        val folder = level.first { it.mediaMetadata.isPlayable == true && it.mediaMetadata.isBrowsable == true }
        ActivityScenario.launch(MainActivity::class.java).use {
            instrumentation.runOnMainSync {
                browser.setMediaItem(folder)
                browser.prepare()
                browser.play()
            }
            waitUntil(30_000, "the local folder plays") {
                var ok = false
                instrumentation.runOnMainSync { ok = browser.isPlaying }
                ok
            }
            instrumentation.runOnMainSync { browser.pause() }
        }
    }

    @Test
    fun radioStationsArePlayableWithArtworkAndPaged() {
        val all = call { getChildren("radio", 0, 1_000, null) }.value.orEmpty()
        assertTrue("stations listed", all.size > 10)
        assertTrue(all.all { it.mediaMetadata.isPlayable == true && it.mediaMetadata.artworkUri != null })

        val secondPage = call { getChildren("radio", 1, 5, null) }.value.orEmpty()
        assertEquals(all.subList(5, 10).map { it.mediaId }, secondPage.map { it.mediaId })

        val unknown = call { getChildren("nope", 0, 10, null) }
        assertTrue("unknown node is an error", unknown.resultCode < 0)
    }

    @Test
    fun pickingAStationInTheCarStartsIt() {
        val station = call { getItem("radio/record") }.value
        assertNotNull(station)
        // Android 15 grants audio focus only to a foreground app. In a car that comes from the Android Auto
        // binding; here the app itself is brought to the front.
        ActivityScenario.launch(MainActivity::class.java).use {
            instrumentation.runOnMainSync {
                browser.setMediaItem(station!!)
                browser.prepare()
                browser.play()
            }
            waitUntil(30_000, "station plays") {
                var ok = false
                instrumentation.runOnMainSync {
                    ok = browser.isPlaying && browser.currentMediaItem?.mediaId == "radio_record"
                }
                ok
            }
            instrumentation.runOnMainSync { browser.pause() }
        }
    }

    @Test
    fun voiceSearchForAStationNameStartsThatStation() {
        val voiceRequest = androidx.media3.common.MediaItem.Builder()
            .setRequestMetadata(
                androidx.media3.common.MediaItem.RequestMetadata.Builder().setSearchQuery("radio record").build()
            )
            .build()
        ActivityScenario.launch(MainActivity::class.java).use {
            instrumentation.runOnMainSync {
                browser.setMediaItem(voiceRequest)
                browser.prepare()
                browser.play()
            }
            waitUntil(30_000, "station plays") {
                var ok = false
                instrumentation.runOnMainSync {
                    ok = browser.isPlaying && browser.currentMediaItem?.mediaId == "radio_record"
                }
                ok
            }
            instrumentation.runOnMainSync { browser.pause() }
        }
    }
}
