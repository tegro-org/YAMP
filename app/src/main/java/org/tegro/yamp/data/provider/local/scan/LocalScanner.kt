package org.tegro.yamp.data.provider.local.scan

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.tegro.yamp.data.provider.local.LocalJournal
import org.tegro.yamp.data.provider.local.SQL_CHUNK
import org.tegro.yamp.data.provider.local.SourceFs
import org.tegro.yamp.data.provider.local.VolumeState
import org.tegro.yamp.data.provider.local.db.DirEntity
import org.tegro.yamp.data.provider.local.db.FileEntity
import org.tegro.yamp.data.provider.local.db.LocalDatabase
import org.tegro.yamp.data.provider.local.db.MoveEntity
import org.tegro.yamp.data.provider.local.db.SourceEntity
import org.tegro.yamp.data.provider.local.db.TrackEntity
import org.tegro.yamp.data.provider.local.db.VolumeEntity
import org.tegro.yamp.data.provider.local.isUnder
import org.tegro.yamp.provider.local.AlbumGrouping
import org.tegro.yamp.provider.local.AudioFormats
import org.tegro.yamp.provider.local.FileNameFallback
import org.tegro.yamp.provider.local.IndexedFile
import org.tegro.yamp.provider.local.ListedFile
import org.tegro.yamp.provider.local.LocalPath
import org.tegro.yamp.provider.local.LocalRefs
import org.tegro.yamp.provider.local.ScanPlan
import org.tegro.yamp.provider.local.ScanPlanner
import org.tegro.yamp.provider.local.SourceListing
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The incremental scan of the sources (docs/phase-4d-local-files.md §4–§6, step 4). Per source:
 * 1. **list** every directory ([SourceWalker]; hidden / `.nomedia` / excludes);
 * 2. **plan** against the volume's index rows ([ScanPlanner]: a file is read only when new or its size / mtime changed);
 * 3. **read** the planned files ([TagReader]) and write them in transactions of [BATCH] rows — kept even if the scan
 *    stops later, since they are true;
 * 4. **finish**: moves (aliases), tombstones, purges, stale folders — only after re-checking that the volume is still
 *    mounted and the root still lists, so an unmount mid-scan deletes nothing (D47).
 *
 * For a weak head unit: one thread at background priority ([DISPATCHER]), cancellable between files, and it waits
 * (up to [YIELD_MAX_MS]) while [scanAll]'s `busy` says the player is starting a track.
 */
class LocalScanner(
    private val db: LocalDatabase,
    private val volumes: VolumeState,
    private val fsFor: (SourceEntity) -> SourceFs?,
    private val reader: TagReader,
    private val status: LocalScanStatus,
    /** A journal line (no paths: the caller's lines use hashed ids). */
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val sourceDao = db.sources()
    private val index = db.index()

    /** Scans every source ([onlySource] = just that one); [full] re-reads every file. Returns each source's summary. */
    suspend fun scanAll(full: Boolean = false, onlySource: Long? = null, busy: StateFlow<Boolean>? = null): Map<Long, ScanSummary> =
        withContext(DISPATCHER) {
            try {
                sourceDao.all().filter { onlySource == null || it.id == onlySource }
                    .associate { it.id to scanSource(it, full, busy) }
            } finally {
                status.set(ScanState())
            }
        }

    /** One source; see the class comment. Never throws for I/O: the outcome says what happened. */
    suspend fun scanSource(source: SourceEntity, full: Boolean = false, busy: StateFlow<Boolean>? = null): ScanSummary {
        val volumeId = source.volumeId
        val label = volumes.label(volumeId) ?: volumeId
        val tag = "SCAN source ${source.id} ${LocalJournal.vol(volumeId)}"
        if (!volumes.isMounted(volumeId)) {
            log("$tag: not connected")
            return ScanSummary(ScanSummary.OUTCOME_NOT_CONNECTED)
        }
        val fs = fsFor(source)
        if (fs == null) {
            log("$tag: no access")
            return ScanSummary(ScanSummary.OUTCOME_NO_ACCESS)
        }
        val job = currentCoroutineContext().job

        var state = ScanState(ScanState.Phase.LISTING, source.id, label)
        status.set(state)
        val walk = SourceWalker.walk(fs, source.rootRel, source.includeHidden, sourceDao.excludes(source.id)) { dir ->
            job.ensureActive()
            status.set(state.copy(folder = dir))
        }
        log("$tag via ${fs.kind}: ${walk.dirs.size} folders, ${walk.files.size} files, ${walk.failedDirs.size} unlisted" + (fs.lastError?.let { " (last: $it)" } ?: ""))
        if (source.rootRel in walk.failedDirs) return gone(volumeId)

        val now = clock()
        val rows = index.filesOfVolume(volumeId)
        val indexed = rows.map {
            // A full rescan re-reads every present file: an impossible mtime makes each one "changed"
            IndexedFile(it.relPath, it.size, if (full && it.missingSince == null) FORCE_MTIME else it.mtime, it.quickHash, it.missingSince)
        }
        val listing = SourceListing(volumeId, source.rootRel, volumeMounted = true, walk.files, walk.failedDirs)
        val plan = ScanPlanner.plan(listing, indexed, now) { quickHash(fs, it) }
        if (plan.skipped) return gone(volumeId)

        val dirs = writeDirs(volumeId, walk.dirs)
        val byPath = rows.associateBy { it.relPath }
        val toRead = plan.added + plan.changed + plan.revived
        state = state.copy(phase = ScanState.Phase.READING, total = toRead.size)
        status.set(state)
        var read = 0
        var failed = 0
        val pending = mutableListOf<Pair<FileEntity, TrackEntity?>>()
        for ((i, item) in toRead.withIndex()) {
            job.ensureActive()
            awaitIdle(busy)
            val path = item.file.relPath
            val dir = dirs[path.substringBeforeLast('/', missingDelimiterValue = "")] ?: continue
            status.set(state.copy(done = i, folder = dir.relPath))
            val result = if (AudioFormats.isPlayable(path)) reader.read(fs, path) else TagRead.Unsupported(AudioFormats.extension(path))
            if (result == TagRead.IoFailed) {
                if (!volumes.isMounted(volumeId)) {
                    flush(pending)
                    return ScanSummary(ScanSummary.OUTCOME_NOT_CONNECTED, read = read)
                }
                failed++
                continue
            }
            read++
            pending += entities(volumeId, item.file, item.quickHash, dir, byPath[path]?.id ?: 0, result, now)
            if (pending.size >= BATCH) flush(pending)
        }
        flush(pending)

        status.set(state.copy(phase = ScanState.Phase.FINISHING, done = toRead.size, folder = null))
        // The destructive part only while the volume is still there and the root still lists: an unmount mid-scan
        // turns every later listing into a failure, and nothing may be concluded from that.
        if (!volumes.isMounted(volumeId) || fs.list(source.rootRel) == null) {
            return ScanSummary(ScanSummary.OUTCOME_NOT_CONNECTED, read = read, failed = failed)
        }
        db.runInTransaction { finish(volumeId, label, source.rootRel, plan, walk, byPath, now) }
        val summary = summarize(source, plan, read, failed)
        sourceDao.setLastScan(source.id, now, JSON.encodeToString(ScanSummary.serializer(), summary))
        return summary
    }

    private fun gone(volumeId: String) = ScanSummary(
        if (volumes.isMounted(volumeId)) ScanSummary.OUTCOME_NO_ACCESS else ScanSummary.OUTCOME_NOT_CONNECTED
    )

    private suspend fun awaitIdle(busy: StateFlow<Boolean>?) {
        if (busy?.value == true) withTimeoutOrNull(YIELD_MAX_MS) { busy.first { !it } }
    }

    /** Inserts the listed directories that are new, updates the rest; parents come first. Returns them by path. */
    private fun writeDirs(volumeId: String, walked: List<WalkedDir>): Map<String, DirEntity> = db.runInTransaction<Map<String, DirEntity>> {
        val known = index.dirsOfVolume(volumeId).associateBy { it.relPath }.toMutableMap()
        for (dir in walked) {
            val parentId = dir.parentRel?.let { known[it]?.id }
            val old = known[dir.relPath]
            val row = DirEntity(old?.id ?: 0, volumeId, dir.relPath, parentId, dir.name, dir.artFile, old?.trackCount ?: 0)
            known[dir.relPath] = when {
                old == null -> row.copy(id = index.insertDir(row))
                old != row -> row.also(index::updateDir)
                else -> old
            }
        }
        known
    }

    private fun entities(
        volumeId: String,
        file: ListedFile,
        quickHash: String?,
        dir: DirEntity,
        fileId: Long,
        result: TagRead,
        now: Long
    ): Pair<FileEntity, TrackEntity?> {
        val ext = AudioFormats.extension(file.relPath)
        val format = when (result) {
            is TagRead.Ok -> result.format
            is TagRead.Unsupported -> result.format
            else -> null
        }
        val row = FileEntity(fileId, volumeId, file.relPath, dir.id, file.size, file.mtime, ext, result is TagRead.Ok, format, quickHash)
        if (result !is TagRead.Ok) return row to null
        val tags = FileNameFallback.apply(file.relPath, result.tags)
        val ref = LocalRefs.track(volumeId, file.relPath).value
        val art = when {
            result.embeddedArt -> TrackEntity.ART_EMBEDDED
            dir.artFile != null -> TrackEntity.ART_FILE
            else -> TrackEntity.ART_NONE
        }
        val search = listOfNotNull(tags.title, tags.artist, tags.albumArtist, tags.album, FileNameFallback.baseName(file.relPath.substringAfterLast('/')))
        val track = TrackEntity(
            ref = ref,
            fileId = 0,
            title = tags.title ?: FileNameFallback.baseName(file.relPath.substringAfterLast('/')),
            artists = tags.artist,
            albumArtist = tags.albumArtist,
            album = tags.album,
            albumKey = AlbumGrouping.albumKey(LocalPath(volumeId, file.relPath), tags),
            disc = tags.disc,
            trackNo = tags.trackNo,
            year = tags.year,
            genre = tags.genre,
            durationMs = result.durationMs,
            art = art,
            searchText = AlbumGrouping.normalize(search.distinct().joinToString(" ")),
            firstSeenAt = now
        )
        return row to track
    }

    /** Writes the read files and their tracks in one transaction; a file that is no longer playable loses its track. */
    private fun flush(pending: MutableList<Pair<FileEntity, TrackEntity?>>) {
        if (pending.isEmpty()) return
        db.runInTransaction {
            for ((file, track) in pending) {
                val id = if (file.id == 0L) index.insertFile(file) else file.id.also { index.updateFile(file) }
                val ref = LocalRefs.track(file.volumeId, file.relPath).value
                if (track == null) {
                    index.deleteTracksOfFiles(listOf(id))
                } else {
                    index.upsertTrack(track.copy(fileId = id, firstSeenAt = index.firstSeenAt(ref) ?: track.firstSeenAt))
                }
                // A file back at a path an alias pointed away from: the real file wins
                index.deleteAliasFrom(ref)
            }
        }
        pending.clear()
    }

    private fun finish(
        volumeId: String,
        label: String,
        rootRel: String,
        plan: ScanPlan,
        walk: SourceWalk,
        byPath: Map<String, FileEntity>,
        now: Long
    ) {
        for (move in plan.moves) {
            val old = LocalRefs.track(volumeId, move.oldRelPath).value
            val new = LocalRefs.track(volumeId, move.newRelPath).value
            index.retarget(old, new)
            index.upsertMove(MoveEntity(old, new, now))
        }
        index.deleteSelfAliases()
        plan.removed.mapNotNull { byPath[it]?.id }.chunked(SQL_CHUNK).forEach { index.markMissing(it, now) }
        plan.purged.mapNotNull { byPath[it]?.id }.chunked(SQL_CHUNK).forEach {
            index.deleteTracksOfFiles(it)
            index.deleteFiles(it)
        }
        val listed = walk.dirs.map { it.relPath }.toSet()
        index.dirsOfVolume(volumeId)
            .filter { isUnder(it.relPath, rootRel) && it.relPath !in listed && walk.failedDirs.none { failed -> isUnder(it.relPath, failed) } }
            .map { it.id }
            .chunked(SQL_CHUNK)
            .forEach(index::deleteDirs)
        index.recountDirs(volumeId)
        index.refreshFolderArt()
        sourceDao.upsertVolume(VolumeEntity(volumeId, label, now))
    }

    private fun summarize(source: SourceEntity, plan: ScanPlan, read: Int, failed: Int): ScanSummary {
        val present = index.filesOfVolume(source.volumeId).filter { it.missingSince == null && isUnder(it.relPath, source.rootRel) }
        val skipped = present.filter { !it.supported }.groupingBy { it.format ?: ScanSummary.UNREADABLE }.eachCount()
        return ScanSummary(
            outcome = ScanSummary.OUTCOME_DONE,
            tracks = present.count { it.supported },
            read = read,
            unchanged = plan.unchanged,
            removed = plan.removed.size,
            moved = plan.moves.size,
            failed = failed,
            skipped = skipped.toSortedMap()
        )
    }

    /** The first 64 KB's SHA-1 (16 hex), for move detection; null when the file can't be read. */
    private fun quickHash(fs: SourceFs, file: ListedFile): String? = try {
        fs.open(file.relPath).use { pfd -> FileInputStream(pfd.fileDescriptor).use(::headHash) }
    } catch (_: IOException) {
        null
    }

    private fun headHash(input: FileInputStream): String {
        val buffer = ByteArray(HASH_BYTES)
        var total = 0
        while (total < HASH_BYTES) {
            val n = input.read(buffer, total, HASH_BYTES - total)
            if (n < 0) break
            total += n
        }
        val digest = MessageDigest.getInstance("SHA-1").apply { update(buffer, 0, total) }.digest()
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_HEX)
    }

    companion object {
        const val BATCH = 200
        const val YIELD_MAX_MS = 3_000L
        private const val HASH_BYTES = 64 * 1024
        private const val HASH_HEX = 16
        private const val FORCE_MTIME = -1L
        private val JSON = Json { encodeDefaults = true }

        /** The scan's one thread, at background priority (a weak head unit plays music meanwhile). */
        val DISPATCHER: CoroutineDispatcher by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    runnable.run()
                }, "local-scan").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        }
    }
}
