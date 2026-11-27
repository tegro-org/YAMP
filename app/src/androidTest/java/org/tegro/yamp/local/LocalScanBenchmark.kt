package org.tegro.yamp.local

import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tegro.yamp.data.provider.local.FileSourceFs
import org.tegro.yamp.data.provider.local.SafSourceFs
import org.tegro.yamp.data.provider.local.SourceFs
import org.tegro.yamp.data.provider.local.VolumeMonitor
import org.tegro.yamp.data.provider.local.db.LocalDatabase
import org.tegro.yamp.data.provider.local.db.SourceEntity
import org.tegro.yamp.data.provider.local.scan.LocalScanStatus
import org.tegro.yamp.data.provider.local.scan.LocalScanner
import org.tegro.yamp.data.provider.local.scan.Media3TagReader
import org.tegro.yamp.data.provider.local.scan.SourceWalker
import org.tegro.yamp.local.LocalStorageInstrumentedTest.Companion.GRANTED
import org.tegro.yamp.local.LocalTestDevice.context
import java.io.File

/**
 * The doc's speed risk (docs/phase-4d-local-files.md, Risks): listing File vs SAF and tag reading on [DIRS] × [PER_DIR]
 * generated files on the SD card. Opt-in (minutes of I/O):
 * `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.tegro.yamp.local.LocalScanBenchmark
 * -Pandroid.testInstrumentationRunnerArguments.localBench=true`; results in `adb logcat -s LocalBench`.
 */
@RunWith(AndroidJUnit4::class)
class LocalScanBenchmark {

    @Test
    fun listingAndTagReading() = runBlocking {
        assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("localBench") == "true")
        val uuid = LocalTestDevice.sdVolumeId()
        val bytes = LocalTestDevice.treeFiles().getValue("Fleur/2005 - Эйфория/01 - Отречение.mp3")

        // The same tree twice: public (SAF) and the app's own folder (File API)
        val publicRoot = "$GRANTED/bench"
        LocalTestDevice.shellScript(
            "rm -rf '/storage/$uuid/$publicRoot'\nmkdir -p '/storage/$uuid/$publicRoot'\n" +
                "base64 -d > '/storage/$uuid/$publicRoot/seed.mp3' <<'EOF'\n" + android.util.Base64.encodeToString(bytes, 0) + "EOF\n" +
                "cd '/storage/$uuid/$publicRoot'\nfor d in \$(seq 1 $DIRS); do mkdir \"Album \$d\"; for t in \$(seq 1 $PER_DIR); do " +
                "cp seed.mp3 \"Album \$d/\$t - Track.mp3\"; done; done\nrm seed.mp3\n"
        )
        val appDir = File(LocalTestDevice.appDirOn(uuid), "bench").apply { deleteRecursively() }
        for (d in 1..DIRS) for (t in 1..PER_DIR) File(appDir, "Album $d/$t - Track.mp3").apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        val fileRoot = appDir.path.removePrefix("/storage/$uuid/")

        val saf = SafSourceFs(context.contentResolver, LocalTestDevice.grantTree(uuid, GRANTED), uuid)
        val file = FileSourceFs(File("/storage/$uuid"))
        val files = DIRS * PER_DIR

        report("list SAF", DIRS + 1) { check(SourceWalker.walk(saf, publicRoot, false, emptyList()).files.size == files) }
        report("list File", DIRS + 1) { check(SourceWalker.walk(file, fileRoot, false, emptyList()).files.size == files) }

        val reader = Media3TagReader(context)
        val safPaths = SourceWalker.walk(saf, publicRoot, false, emptyList()).files.map { it.relPath }
        val filePaths = SourceWalker.walk(file, fileRoot, false, emptyList()).files.map { it.relPath }
        report("tags Media3 SAF", files) { safPaths.forEach { reader.read(saf, it) } }
        report("tags Media3 File", files) { filePaths.forEach { reader.read(file, it) } }
        report("tags platform MMR File", MMR_FILES) {
            filePaths.take(MMR_FILES).forEach { path ->
                MediaMetadataRetriever().apply {
                    setDataSource("/storage/$uuid/$path")
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    release()
                }
            }
        }

        // Whole scans through the scanner: first (reads and hashes everything) and an unchanged rescan
        val monitor = VolumeMonitor(context)
        for ((name, fs, root) in listOf(Triple("SAF", saf, publicRoot), Triple("File", file, fileRoot))) {
            val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
            db.sources().insert(SourceEntity(volumeId = uuid, rootRel = root, treeUri = null, addedAt = 0))
            val scanner = LocalScanner(db, monitor, { fs as SourceFs }, reader, LocalScanStatus())
            report("scan first $name", files) { scanner.scanAll() }
            report("scan unchanged $name", files) { scanner.scanAll() }
            db.close()
        }
    }

    private inline fun report(what: String, count: Int, block: () -> Unit) {
        val start = SystemClock.elapsedRealtime()
        block()
        val ms = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "$what: $ms ms for $count = ${"%.2f".format(ms.toDouble() / count)} ms each")
    }

    private companion object {
        const val TAG = "LocalBench"
        const val DIRS = 100
        const val PER_DIR = 20
        const val MMR_FILES = 500
    }
}
