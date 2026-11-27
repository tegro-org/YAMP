package org.tegro.yamp.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.tegro.yamp.data.provider.local.FileSourceFs
import org.tegro.yamp.data.provider.local.SafSourceFs
import org.tegro.yamp.data.provider.local.SourceFs
import org.tegro.yamp.data.provider.local.VolumeMonitor
import org.tegro.yamp.data.provider.local.db.LocalDatabase
import org.tegro.yamp.data.provider.local.db.SourceEntity
import org.tegro.yamp.data.provider.local.db.TrackEntity
import org.tegro.yamp.data.provider.local.scan.LocalScanStatus
import org.tegro.yamp.data.provider.local.scan.LocalScanner
import org.tegro.yamp.data.provider.local.scan.Media3TagReader
import org.tegro.yamp.data.provider.local.scan.ScanSummary
import org.tegro.yamp.data.provider.local.scan.TagRead
import org.tegro.yamp.data.provider.local.scan.TagReader
import org.tegro.yamp.local.LocalStorageInstrumentedTest.Companion.GRANTED
import org.tegro.yamp.local.LocalTestDevice.context
import java.io.File

/**
 * Step 4 "done when" (docs/phase-4d-local-files.md) on the emulator's SD card, with the real Media3 reader: the test
 * tree gives the expected rows (CP1251, ID3v1, untagged, disc folders, WAV INFO, ALAC / APE counted, hidden and
 * `.nomedia` skipped), through SAF and the File API alike; a rescan of the unchanged tree reads nothing; unmounting
 * the card mid-scan deletes nothing.
 */
@RunWith(AndroidJUnit4::class)
class LocalScanInstrumentedTest {
    private lateinit var uuid: String
    private lateinit var monitor: VolumeMonitor
    private val dbs = mutableListOf<LocalDatabase>()

    @Before
    fun setUp() {
        uuid = LocalTestDevice.sdVolumeId()
        monitor = VolumeMonitor(context)
        LocalTestDevice.pushTree(uuid, "$GRANTED/tree")
    }

    @After
    fun tearDown() {
        if (!monitor.isMounted(uuid)) LocalTestDevice.setMounted(uuid, mounted = true, monitor)
        dbs.forEach { it.close() }
    }

    @Test
    fun aScanOfTheTestTreeGivesTheExpectedRowsAndARescanReadsNothing() = runBlocking {
        val (db, scanner) = scanner(saf(), "$GRANTED/tree")

        val first = scanner.scanAll().values.single()

        assertEquals(ScanSummary.OUTCOME_DONE, first.outcome)
        assertEquals(10, first.tracks)
        assertEquals(mapOf("alac" to 1, "ape" to 1), first.skipped)
        assertEquals(EXPECTED, rows(db, "$GRANTED/tree"))
        db.index().tracks().forEach { assertTrue("${it.ref}: ${it.durationMs}", it.durationMs in 900..1_200) }

        val again = scanner.scanAll().values.single()
        assertEquals(0, again.read)
        assertEquals(12, again.unchanged)
        assertEquals(EXPECTED, rows(db, "$GRANTED/tree"))
    }

    @Test
    fun theFileBackEndIndexesTheSameRows() = runBlocking {
        val dir = File(LocalTestDevice.appDirOn(uuid), "tree")
        LocalTestDevice.copyTree(dir)
        val root = dir.path.removePrefix("/storage/$uuid/")
        val (db, scanner) = scanner(FileSourceFs(File("/storage/$uuid")), root)

        assertEquals(10, scanner.scanAll().values.single().tracks)
        assertEquals(EXPECTED, rows(db, root))
    }

    @Test
    fun unmountingTheCardMidScanDeletesNothing() = runBlocking {
        val fs = saf()
        var reads = 0
        val media3 = Media3TagReader(context)
        val unmountAfterTwo = TagReader { f, path ->
            media3.read(f, path).also { if (++reads == 2) LocalTestDevice.setMounted(uuid, mounted = false, monitor) }
        }
        val db = database()
        val first = LocalScanner(db, monitor, { fs }, media3, LocalScanStatus())
        val source = db.sources().insert(SourceEntity(volumeId = uuid, rootRel = "$GRANTED/tree", treeUri = null, addedAt = 0))
        first.scanAll()
        val files = db.index().files()
        val tracks = db.index().tracks()

        // A full rescan re-reads everything; the card goes away after the second file
        val summary = LocalScanner(db, monitor, { fs }, unmountAfterTwo, LocalScanStatus()).scanAll(full = true).getValue(source)

        assertEquals(ScanSummary.OUTCOME_NOT_CONNECTED, summary.outcome)
        assertEquals(files.map { it.relPath to it.missingSince }, db.index().files().map { it.relPath to it.missingSince })
        assertEquals(tracks.map { it.ref }, db.index().tracks().map { it.ref })
        assertEquals(10, db.index().dirs().sumOf { it.trackCount })
    }

    private fun saf(): SourceFs = SafSourceFs(context.contentResolver, LocalTestDevice.grantTree(uuid, GRANTED), uuid)

    private fun database(): LocalDatabase =
        Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).allowMainThreadQueries().build().also { dbs += it }

    private fun scanner(fs: SourceFs, rootRel: String): Pair<LocalDatabase, LocalScanner> {
        val db = database()
        db.sources().insert(SourceEntity(volumeId = uuid, rootRel = rootRel, treeUri = null, addedAt = 0))
        return db to LocalScanner(db, monitor, { fs }, Media3TagReader(context), LocalScanStatus())
    }

    /** The tracks as `path under the root → title | artists | album artist | album | disc | no | year | art`. */
    private fun rows(db: LocalDatabase, root: String): Map<String, String> {
        val files = db.index().files().associateBy { it.id }
        return db.index().tracks().associate { t ->
            files.getValue(t.fileId).relPath.removePrefix("$root/") to
                listOf(t.title, t.artists, t.albumArtist, t.album, t.disc, t.trackNo, t.year, t.art).joinToString(" | ")
        }.toSortedMap()
    }

    private companion object {
        const val E = TrackEntity.ART_EMBEDDED
        const val F = TrackEntity.ART_FILE
        const val N = TrackEntity.ART_NONE

        val EXPECTED = sortedMapOf(
            "Audiobook/01 - Глава первая.mp3" to "Глава первая | null | null | Audiobook | null | 1 | null | $N",
            "Audiobook/02 - Глава вторая.opus" to "Глава вторая | null | null | Audiobook | null | 2 | null | $N",
            "Compilation/CD1/01 - Song A.mp3" to "Song A | null | null | Compilation | 1 | 1 | null | $N",
            "Compilation/CD2/01 - Song B.mp3" to "Song B | null | null | Compilation | 2 | 1 | null | $N",
            "Fleur/2005 - Эйфория/01 - Отречение.mp3" to "Отречение | Fleur | Fleur | Эйфория | null | 1 | 2005 | $E",
            "Fleur/2005 - Эйфория/02 - Тишина.flac" to "Тишина | Fleur | Fleur | Эйфория | null | 2 | 2005 | $F",
            "Mixed/aac.m4a" to "Aac Song | Generator | null | Formats | null | 3 | null | $N",
            // WAV LIST/INFO: Media3 ignores it, the platform retriever gave nothing either; the file name it is
            "Mixed/wave.wav" to "wave | null | null | Mixed | null | null | null | $N",
            "Old/track1.mp3" to "Ночная кобыла | Мельница | null | Дорога сна | null | null | null | $N",
            "Old/track2.mp3" to "Беспечный ангел | Ария | null | Химера | null | 7 | 2001 | $N"
        )
    }
}
