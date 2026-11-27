package org.tegro.yamp.data.provider.local

import android.app.Application
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.provider.local.db.ExcludeEntity
import org.tegro.yamp.data.provider.local.db.LocalDatabase
import org.tegro.yamp.data.provider.local.db.SourceEntity
import org.tegro.yamp.data.provider.local.db.TrackEntity
import org.tegro.yamp.data.provider.local.scan.LocalScanStatus
import org.tegro.yamp.data.provider.local.scan.LocalScanner
import org.tegro.yamp.data.provider.local.scan.ScanState
import org.tegro.yamp.data.provider.local.scan.ScanSummary
import org.tegro.yamp.data.provider.local.scan.SourceWalker
import org.tegro.yamp.data.provider.local.scan.TagRead
import org.tegro.yamp.data.provider.local.scan.TagReader
import org.tegro.yamp.provider.local.TrackTags
import java.io.File
import java.io.FileNotFoundException

/**
 * The scanner over a real temporary tree (File back-end) with a fake tag reader and a fake volume state
 * (docs/phase-4d-local-files.md step 4 "done when": expected rows, an unchanged rescan reads nothing, an unmount
 * mid-scan deletes nothing; plus moves, tombstones, failed listings, hidden folders, excludes, unsupported formats).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalScannerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: LocalDatabase
    private lateinit var volume: File
    private val volumes = FakeVolumes()
    private val reader = FakeReader()
    private var now = 1_000_000L
    private lateinit var fs: TestFs
    private lateinit var scanner: LocalScanner
    private val status = LocalScanStatus()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).allowMainThreadQueries().build()
        volume = tmp.newFolder("6917-1912")
        fs = TestFs(FileSourceFs(volume))
        scanner = LocalScanner(db, volumes, { fs }, reader, status) { now }
        write("Music/Fleur/2005 - Эйфория/01 - Отречение.mp3", "a1")
        write("Music/Fleur/2005 - Эйфория/02 - Тишина.flac", "a2")
        write("Music/Fleur/2005 - Эйфория/folder.jpg", "img")
        write("Music/Book/03 - Глава.mp3", "b3")
        write("Music/Lossless/x.ape", "ape")
        write("Music/Lossless/y.m4a", "alac")
        write("Music/Ringtones/.nomedia", "")
        write("Music/Ringtones/ring.mp3", "r")
        write("Music/.hidden/h.mp3", "h")
        write("Music/Skip/s.mp3", "s")
        write("Music/notes.txt", "t")
        write("Other/outside.mp3", "o")
        reader.results["Music/Fleur/2005 - Эйфория/01 - Отречение.mp3"] =
            TagRead.Ok(TrackTags("Отречение", "Fleur", "Fleur", "Эйфория", trackNo = 1, year = 2005), 1000, embeddedArt = true, "mp3")
        reader.results["Music/Lossless/y.m4a"] = TagRead.Unsupported("alac")
        val id = db.sources().insert(SourceEntity(volumeId = VOLUME, rootRel = "Music", treeUri = null, addedAt = 0))
        db.sources().addExclude(ExcludeEntity(id, "Music/Skip"))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun aFirstScanIndexesThePlayableFilesAndCountsTheRest() = runBlocking {
        val summary = scan()

        assertEquals(ScanSummary.OUTCOME_DONE, summary.outcome)
        assertEquals(3, summary.tracks)
        assertEquals(mapOf("alac" to 1, "ape" to 1), summary.skipped)
        val tracks = db.index().tracks().associateBy { it.ref }
        assertEquals(
            setOf(ref("Music/Book/03 - Глава.mp3"), ref("Music/Fleur/2005 - Эйфория/01 - Отречение.mp3"), ref("Music/Fleur/2005 - Эйфория/02 - Тишина.flac")),
            tracks.keys
        )
        val tagged = tracks.getValue(ref("Music/Fleur/2005 - Эйфория/01 - Отречение.mp3"))
        assertEquals("Отречение", tagged.title)
        assertEquals(TrackEntity.ART_EMBEDDED, tagged.art)
        // Untagged: the file name gives title and number, the folder the album; the folder's cover is the art
        val untagged = tracks.getValue(ref("Music/Fleur/2005 - Эйфория/02 - Тишина.flac"))
        assertEquals("Тишина", untagged.title)
        assertEquals(2, untagged.trackNo)
        assertEquals("2005 - Эйфория", untagged.album)
        assertEquals(TrackEntity.ART_FILE, untagged.art)
        assertEquals(TrackEntity.ART_NONE, tracks.getValue(ref("Music/Book/03 - Глава.mp3")).art)
        assertTrue(tracks.getValue(ref("Music/Book/03 - Глава.mp3")).searchText.contains("глава"))
        // Hidden, .nomedia, excluded and outside-the-root files are not candidates; folders carry the cover and counts
        val dirs = db.index().dirs().associateBy { it.relPath }
        assertEquals(setOf("Music", "Music/Book", "Music/Fleur", "Music/Fleur/2005 - Эйфория", "Music/Lossless"), dirs.keys)
        assertEquals("folder.jpg", dirs.getValue("Music/Fleur/2005 - Эйфория").artFile)
        assertEquals(2, dirs.getValue("Music/Fleur/2005 - Эйфория").trackCount)
        assertEquals(dirs.getValue("Music/Fleur").id, dirs.getValue("Music/Fleur/2005 - Эйфория").parentId)
        assertEquals(ScanState(), status.state.value)
    }

    @Test
    fun aRescanOfAnUnchangedTreeReadsNothing() = runBlocking {
        scan()
        val reads = reader.reads.size

        val again = scan()

        assertEquals(reads, reader.reads.size)
        assertEquals(0, again.read)
        assertEquals(5, again.unchanged)
        assertEquals(3, again.tracks)
    }

    @Test
    fun aFullRescanReadsEveryFileAgain() = runBlocking {
        scan()
        reader.reads.clear()
        scan(full = true)
        assertEquals(4, reader.reads.size) // the APE is counted by extension, never read
    }

    @Test
    fun unmountingMidScanDeletesNothing() = runBlocking {
        scan()
        val before = db.index().files()
        // Changed files to read; the volume goes away after the first one, every later access fails
        write("Music/Book/03 - Глава.mp3", "b3-changed")
        write("Music/Book/04 - New.mp3", "b4")
        reader.reads.clear()
        reader.onRead = { if (reader.reads.size == 1) unmount() }

        val summary = scan()

        assertEquals(ScanSummary.OUTCOME_NOT_CONNECTED, summary.outcome)
        assertTrue(db.index().files().none { it.missingSince != null })
        // What was read before the unmount is kept (it is true); nothing else changed
        assertEquals(before.map { it.relPath }.toSet(), db.index().files().map { it.relPath }.toSet() - "Music/Book/04 - New.mp3")
        assertTrue(db.index().tracks().map { it.ref }.containsAll(before.filter { it.supported }.map { ref(it.relPath) }))
    }

    @Test
    fun anUnmountDuringListingChangesNothing() = runBlocking {
        scan()
        val before = db.index().files()
        fs.failList = { it.startsWith("Music/Fleur") }
        fs.afterList = { if (it == "Music/Lossless") unmount() }

        val summary = scan()

        assertEquals(ScanSummary.OUTCOME_NOT_CONNECTED, summary.outcome)
        assertEquals(before, db.index().files())
    }

    @Test
    fun anAbsentVolumeIsNotScanned() = runBlocking {
        scan()
        volumes.mounted = false
        File(volume, "Music").deleteRecursively()
        assertEquals(ScanSummary.OUTCOME_NOT_CONNECTED, scan().outcome)
        assertEquals(5, db.index().files().size)
        assertTrue(db.index().files().none { it.missingSince != null })
    }

    @Test
    fun aFolderThatFailsToListKeepsItsFiles() = runBlocking {
        scan()
        fs.failList = { it == "Music/Fleur/2005 - Эйфория" }
        val summary = scan()
        assertEquals(ScanSummary.OUTCOME_DONE, summary.outcome)
        assertTrue(db.index().files().none { it.missingSince != null })
        assertTrue(db.index().dirs().any { it.relPath == "Music/Fleur/2005 - Эйфория" })
    }

    @Test
    fun aDeletedFileBecomesATombstoneAndComesBack() = runBlocking {
        scan()
        val path = "Music/Book/03 - Глава.mp3"
        File(volume, path).delete()
        scan()
        assertEquals(now, db.index().files().single { it.relPath == path }.missingSince)
        assertEquals(2, scan().tracks)

        write(path, "b3")
        scan()
        assertNull(db.index().files().single { it.relPath == path }.missingSince)
    }

    @Test
    fun aRenamedFileLeavesAnAliasAndNoTombstone() = runBlocking {
        scan()
        val old = "Music/Book/03 - Глава.mp3"
        val new = "Music/Book/Renamed/03 - Глава.mp3"
        File(volume, new).parentFile!!.mkdirs()
        File(volume, old).renameTo(File(volume, new))
        now += 1_000

        val summary = scan()

        assertEquals(1, summary.moved)
        assertEquals(listOf(ref(old) to ref(new)), db.index().moves().map { it.oldRef to it.newRef })
        assertTrue(db.index().files().none { it.relPath == old })
        assertTrue(db.index().tracks().any { it.ref == ref(new) })
        assertTrue(db.index().tracks().none { it.ref == ref(old) })

        // Moved again: the chain collapses to the newest path
        val third = "Music/Book/03 - Глава (2).mp3"
        File(volume, new).renameTo(File(volume, third))
        scan()
        assertEquals(listOf(ref(old) to ref(third), ref(new) to ref(third)), db.index().moves().map { it.oldRef to it.newRef })
    }

    @Test
    fun anIoFailureOnAMountedVolumeIsRetriedNextTime() = runBlocking {
        reader.results["Music/Book/03 - Глава.mp3"] = TagRead.IoFailed
        val first = scan()
        assertEquals(1, first.failed)
        assertTrue(db.index().files().none { it.relPath == "Music/Book/03 - Глава.mp3" })

        reader.results.remove("Music/Book/03 - Глава.mp3")
        reader.reads.clear()
        scan()
        assertEquals(listOf("Music/Book/03 - Глава.mp3"), reader.reads)
    }

    @Test
    fun showHiddenIncludesDotAndNomediaFolders() = runBlocking {
        val source = db.sources().all().single()
        db.sources().setIncludeHidden(source.id, true)
        scan()
        val paths = db.index().files().map { it.relPath }
        assertTrue("Music/Ringtones/ring.mp3" in paths)
        assertTrue("Music/.hidden/h.mp3" in paths)
        assertTrue("Music/Skip/s.mp3" !in paths)
    }

    @Test
    fun hidingAFolderAgainTombstonesItsFiles() = runBlocking {
        val source = db.sources().all().single()
        db.sources().setIncludeHidden(source.id, true)
        scan()
        db.sources().setIncludeHidden(source.id, false)
        scan()
        assertTrue(db.index().files().single { it.relPath == "Music/Ringtones/ring.mp3" }.missingSince != null)
    }

    @Test
    fun theWalkerSkipsTheRulesButNeverTheRoot() {
        File(volume, "Music/.nomedia").writeText("")
        val walk = SourceWalker.walk(fs, "Music", includeHidden = false, excludes = listOf("Music/Skip"))
        assertTrue(walk.files.any { it.relPath == "Music/Book/03 - Глава.mp3" })
        assertTrue(walk.files.none { it.relPath.startsWith("Music/Skip") || it.relPath.startsWith("Music/Ringtones") })
        assertTrue(walk.failedDirs.isEmpty())
    }

    @Test
    fun treeDocumentIdsMapToVolumeAndPath() {
        assertEquals(org.tegro.yamp.provider.local.LocalPath("6917-1912", "Music/Fleur"), LocalSources.parseDocumentId("6917-1912:Music/Fleur"))
        assertEquals(org.tegro.yamp.provider.local.LocalPath("primary", ""), LocalSources.parseDocumentId("primary:"))
        assertNull(LocalSources.parseDocumentId("no-colon"))
        assertNull(LocalSources.parseDocumentId("primary:a/../b"))
    }

    private suspend fun scan(full: Boolean = false): ScanSummary = scanner.scanAll(full).values.single()

    private fun unmount() {
        volumes.mounted = false
        fs.dead = true
    }

    private fun write(path: String, content: String) {
        File(volume, path).apply { parentFile!!.mkdirs() }.writeText(content)
        // Distinct, increasing mtimes: a rewrite within one second must still look changed
        File(volume, path).setLastModified(now + (mtime++) * 1000)
    }

    private var mtime = 0L

    private fun ref(path: String) = org.tegro.yamp.provider.local.LocalRefs.track(VOLUME, path).value

    private class FakeVolumes : VolumeState {
        var mounted = true
        override fun isMounted(volumeId: String) = mounted && volumeId == VOLUME
        override fun label(volumeId: String) = "SD card"
    }

    private class FakeReader : TagReader {
        val results = mutableMapOf<String, TagRead>()
        val reads = mutableListOf<String>()
        var onRead: () -> Unit = {}

        override fun read(fs: SourceFs, relPath: String): TagRead {
            if ((fs as TestFs).dead) return TagRead.IoFailed
            reads += relPath
            val result = results[relPath] ?: TagRead.Ok(TrackTags(), 1000, embeddedArt = false, "mp3")
            onRead()
            return result
        }
    }

    /** The File back-end with failures on demand: [dead] = the volume is gone (every access fails). */
    class TestFs(private val delegate: SourceFs) : SourceFs {
        var dead = false
        var failList: (String) -> Boolean = { false }
        var afterList: (String) -> Unit = {}

        override fun list(relPath: String): List<FsEntry>? {
            if (dead || failList(relPath)) return null
            return delegate.list(relPath).also { afterList(relPath) }
        }

        override fun open(relPath: String): ParcelFileDescriptor = if (dead) throw FileNotFoundException(relPath) else delegate.open(relPath)

        override fun uri(relPath: String): Uri = delegate.uri(relPath)
    }

    private companion object {
        const val VOLUME = "6917-1912"
    }
}
