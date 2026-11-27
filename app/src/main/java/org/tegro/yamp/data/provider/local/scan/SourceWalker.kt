package org.tegro.yamp.data.provider.local.scan

import org.tegro.yamp.data.provider.local.FsEntry
import org.tegro.yamp.data.provider.local.SourceFs
import org.tegro.yamp.data.provider.local.childPath
import org.tegro.yamp.data.provider.local.isUnder
import org.tegro.yamp.provider.local.AudioFormats
import org.tegro.yamp.provider.local.FolderArt
import org.tegro.yamp.provider.local.ListedFile

/** A directory the walk listed: its path, its parent's (null for the source root) and its cover file. */
data class WalkedDir(val relPath: String, val parentRel: String?, val name: String, val artFile: String?)

/** A source's listing: its directories (parents first), candidate files, and the directories whose listing failed. */
data class SourceWalk(val dirs: List<WalkedDir>, val files: List<ListedFile>, val failedDirs: Set<String>)

/**
 * Lists a source (docs/phase-4d-local-files.md §5 rules): every directory under [rootRel] breadth-first (cheap: one
 * listing per directory, directory mtimes are not trusted), skipping `.`-folders, folders with `.nomedia` and
 * dot-files unless [includeHidden], and [excludes] (volume-relative) always. The root itself is never skipped — the
 * user chose it. A directory that can't be listed is recorded in [SourceWalk.failedDirs] and not descended into.
 */
object SourceWalker {
    private const val NOMEDIA = ".nomedia"

    fun walk(
        fs: SourceFs,
        rootRel: String,
        includeHidden: Boolean,
        excludes: Collection<String>,
        onDir: (String) -> Unit = {}
    ): SourceWalk {
        val walk = Walk(includeHidden, excludes)
        walk.queue += WalkedDir(rootRel, null, rootRel.substringAfterLast('/'), null)
        while (walk.queue.isNotEmpty()) {
            val dir = walk.queue.removeFirst()
            onDir(dir.relPath)
            val entries = fs.list(dir.relPath)
            if (entries == null) walk.failed += dir.relPath else walk.visit(dir, entries)
        }
        return SourceWalk(walk.dirs, walk.files, walk.failed)
    }

    private class Walk(val includeHidden: Boolean, val excludes: Collection<String>) {
        val dirs = mutableListOf<WalkedDir>()
        val files = mutableListOf<ListedFile>()
        val failed = mutableSetOf<String>()
        val queue = ArrayDeque<WalkedDir>()

        fun visit(dir: WalkedDir, entries: List<FsEntry>) {
            val isRoot = dir.parentRel == null
            val hiddenByNomedia = entries.any { !it.isDirectory && it.name == NOMEDIA }
            if (hiddenByNomedia && !isRoot && !includeHidden) return
            dirs += dir.copy(artFile = FolderArt.pick(entries.filter { !it.isDirectory }.map { it.name }))
            for (entry in entries.sortedBy { it.name }) {
                if (!includeHidden && entry.name.startsWith(".")) continue
                val path = childPath(dir.relPath, entry.name)
                if (entry.isDirectory) {
                    if (excludes.none { isUnder(path, it) }) queue += WalkedDir(path, dir.relPath, entry.name, null)
                } else if (AudioFormats.isCandidate(entry.name)) {
                    files += ListedFile(path, entry.size, entry.mtime)
                }
            }
        }
    }
}
