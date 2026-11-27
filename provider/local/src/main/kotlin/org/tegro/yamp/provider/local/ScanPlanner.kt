package org.tegro.yamp.provider.local

/** A candidate file a directory listing returned. [mtime] in ms, as the file system reports it. */
data class ListedFile(val relPath: String, val size: Long, val mtime: Long)

/** A file the index knows; [missingSince] set = a tombstone (gone, kept for move detection). */
data class IndexedFile(val relPath: String, val size: Long, val mtime: Long, val quickHash: String?, val missingSince: Long?)

/**
 * One source's listing. [volumeMounted] false = the volume is absent: nothing is planned. [failedDirs] are the
 * directories whose listing failed (an I/O error is not an empty folder): nothing under them is changed. A failed
 * [rootRel] means the whole source is skipped.
 */
data class SourceListing(
    val volumeId: String,
    val rootRel: String,
    val volumeMounted: Boolean,
    val files: List<ListedFile>,
    val failedDirs: Set<String> = emptySet()
)

/** A file to read (tags, art) and its quick hash (first 64 KB; null when it could not be computed). */
data class FileToRead(val file: ListedFile, val quickHash: String?)

/** A same-volume rename / move: the alias `old → new` for the resolver (`moves` table). */
data class Move(val oldRelPath: String, val newRelPath: String)

/**
 * What a scan of one source does to the index.
 * - [added]: new files, to read and insert; [changed]: size or mtime differ, to re-read; [revived]: tombstones that
 *   are back, to re-read and un-tombstone.
 * - [unchanged]: the count of files left alone.
 * - [removed]: gone from a successful listing on a mounted volume → tombstone (`missingSince = now`).
 * - [moves]: aliases from a gone file (just removed or an earlier tombstone) to an added one with the same size and
 *   quick hash, only when the match is unique on both sides. The old file is purged rather than tombstoned: the alias
 *   now carries it.
 * - [purged]: rows to delete — tombstones older than [ScanPlanner.TOMBSTONE_TTL_MS] and the old side of [moves].
 * [skipped] = the volume is absent or the source root couldn't be listed: everything else is empty.
 */
data class ScanPlan(
    val skipped: Boolean,
    val added: List<FileToRead> = emptyList(),
    val changed: List<FileToRead> = emptyList(),
    val revived: List<FileToRead> = emptyList(),
    val unchanged: Int = 0,
    val removed: List<String> = emptyList(),
    val moves: List<Move> = emptyList(),
    val purged: List<String> = emptyList()
) {
    companion object {
        val SKIPPED = ScanPlan(skipped = true)
    }
}

/**
 * The incremental scan's decisions (docs/phase-4d-local-files.md §4–§5), pure: a source's listing + the index rows of
 * that volume → [ScanPlan]. A file is re-read only when it is new or its (size, mtime) changed; **nothing is ever
 * removed on an absent volume or under a directory whose listing failed** (D47). Moves never cross volumes: one plan
 * covers one volume.
 */
object ScanPlanner {
    const val TOMBSTONE_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000

    /**
     * [index] = the index rows of [listing]'s volume (rows outside the source root are ignored). [quickHash] is called
     * for each added, changed and revived file (I/O stays with the caller); null = unknown, never matched as a move.
     */
    fun plan(listing: SourceListing, index: List<IndexedFile>, now: Long, quickHash: (ListedFile) -> String?): ScanPlan {
        if (!listing.volumeMounted || listing.rootRel in listing.failedDirs) return ScanPlan.SKIPPED
        val inRoot = index.filter { isUnder(it.relPath, listing.rootRel) }
        val known = inRoot.associateBy { it.relPath }
        val listed = listing.files.filter { isUnder(it.relPath, listing.rootRel) }.associateBy { it.relPath }

        val added = mutableListOf<FileToRead>()
        val changed = mutableListOf<FileToRead>()
        val revived = mutableListOf<FileToRead>()
        var unchanged = 0
        for (file in listed.values) {
            val row = known[file.relPath]
            when {
                row == null -> added += FileToRead(file, quickHash(file))
                row.missingSince != null -> revived += FileToRead(file, quickHash(file))
                row.size != file.size || row.mtime != file.mtime -> changed += FileToRead(file, quickHash(file))
                else -> unchanged++
            }
        }

        val gone = inRoot.filter { it.relPath !in listed && !isProtected(it.relPath, listing.failedDirs) }
        val removedNow = gone.filter { it.missingSince == null }
        val tombstones = gone.filter { it.missingSince != null }
        val moves = matchMoves(added, gone)
        val movedAway = moves.map { it.oldRelPath }.toSet()
        val expired = tombstones.filter { now - (it.missingSince ?: now) > TOMBSTONE_TTL_MS }.map { it.relPath }

        return ScanPlan(
            skipped = false,
            added = added,
            changed = changed,
            revived = revived,
            unchanged = unchanged,
            removed = removedNow.map { it.relPath }.filter { it !in movedAway },
            moves = moves,
            purged = (movedAway + expired).distinct()
        )
    }

    /** Pairs of (gone, added) with the same size and quick hash, unique on both sides; ambiguous keys give nothing. */
    private fun matchMoves(added: List<FileToRead>, gone: List<IndexedFile>): List<Move> {
        val newByKey = added.filter { it.quickHash != null }.groupBy { it.file.size to it.quickHash }
        val oldByKey = gone.filter { it.quickHash != null }.groupBy { it.size to it.quickHash }
        return newByKey.mapNotNull { (key, news) ->
            val olds = oldByKey[key] ?: return@mapNotNull null
            if (news.size != 1 || olds.size != 1) return@mapNotNull null
            Move(olds.single().relPath, news.single().file.relPath)
        }
    }

    /** Whether [relPath] lies under a directory whose listing failed. */
    private fun isProtected(relPath: String, failedDirs: Set<String>): Boolean = failedDirs.any { isUnder(relPath, it) }

    /** Whether [relPath] is [dir] itself or inside it; the empty [dir] is the volume root. */
    private fun isUnder(relPath: String, dir: String): Boolean = dir.isEmpty() || relPath == dir || relPath.startsWith("$dir/")
}
