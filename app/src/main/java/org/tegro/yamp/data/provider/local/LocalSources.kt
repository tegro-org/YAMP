package org.tegro.yamp.data.provider.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.tegro.yamp.data.provider.local.db.ExcludeEntity
import org.tegro.yamp.data.provider.local.db.LocalDatabase
import org.tegro.yamp.data.provider.local.db.SourceEntity
import org.tegro.yamp.data.provider.local.db.VolumeEntity
import org.tegro.yamp.provider.local.LocalPath
import org.tegro.yamp.provider.local.LocalRefs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The sources store (docs/phase-4d-local-files.md, step 3): the folders the user chose, their excludes and "show
 * hidden", and the [SourceFs] each one is read through. A source is either a SAF tree (the system picker: every source
 * on API ≥ 30, a USB drive on API ≤ 29) read through its persisted grant, or a path (the app's own chooser, API ≤ 29)
 * read with the file API under `/storage/<uuid>`. Both use the volume UUID as the volume id, so refs are the same.
 */
@Singleton
class LocalSources @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: LocalDatabase,
    private val volumes: VolumeMonitor,
    private val journal: LocalJournal
) {
    private val dao = db.sources()

    val sources: Flow<List<SourceEntity>> = dao.observeAll()

    suspend fun all(): List<SourceEntity> = withContext(Dispatchers.IO) { dao.all() }

    /**
     * Adds the folder of [treeUri] (an `ACTION_OPEN_DOCUMENT_TREE` result) and keeps its read grant. The same folder
     * twice returns the existing source. Null when the tree is not on a storage volume (another provider's tree).
     */
    suspend fun add(treeUri: Uri, now: Long = System.currentTimeMillis()): SourceEntity? = withContext(Dispatchers.IO) {
        val path = treePath(treeUri)
        if (path == null) {
            journal.event("ADD tree rejected: not a storage volume (${treeUri.authority})")
            return@withContext null
        }
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            journal.event("ADD tree ${LocalJournal.vol(path.volumeId)} ${LocalJournal.path(path.relPath)}: no persistable grant (${e.javaClass.simpleName})")
            throw e
        }
        forgetGrants()
        volumes.refresh()
        val volume = volumes.volume(path.volumeId)
        journal.event("ADD tree ${LocalJournal.vol(path.volumeId)} ${LocalJournal.path(path.relPath)} grant kept; ${volume?.let(LocalJournal::volume) ?: "volume not listed"}")
        db.withTransaction {
            volume?.let { dao.upsertVolume(VolumeEntity(it.id, it.label, now)) }
            dao.find(path.volumeId, path.relPath) ?: SourceEntity(
                volumeId = path.volumeId,
                rootRel = path.relPath,
                treeUri = treeUri.toString(),
                addedAt = now
            ).let { it.copy(id = dao.insert(it)) }
        }
    }

    /**
     * Removes source [id] (user data: its excludes go with it) and the index rows no remaining source covers;
     * favourites and move aliases stay (user data). The grant is released when no other source uses it.
     */
    suspend fun remove(id: Long) = withContext(Dispatchers.IO) {
        val source = dao.byId(id) ?: return@withContext
        db.runInTransaction {
            dao.delete(id)
            pruneUncovered(source.volumeId)
        }
        val tree = source.treeUri ?: return@withContext
        if (dao.all().none { it.treeUri == tree }) {
            runCatching { context.contentResolver.releasePersistableUriPermission(tree.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            forgetGrants()
        }
    }

    /** Deletes the index rows of [volumeId] that lie under no source's root (after a source is removed). */
    private fun pruneUncovered(volumeId: String) {
        val roots = dao.all().filter { it.volumeId == volumeId }.map { it.rootRel }
        val index = db.index()
        fun covered(relPath: String) = roots.any { isUnder(relPath, it) }
        val files = index.filesOfVolume(volumeId).filterNot { covered(it.relPath) }.map { it.id }
        files.chunked(SQL_CHUNK).forEach {
            index.deleteTracksOfFiles(it)
            index.deleteFiles(it)
        }
        index.dirsOfVolume(volumeId).filterNot { covered(it.relPath) }.map { it.id }.chunked(SQL_CHUNK).forEach(index::deleteDirs)
    }

    suspend fun setIncludeHidden(id: Long, include: Boolean) = withContext(Dispatchers.IO) { dao.setIncludeHidden(id, include) }

    /** Excludes [relPath] (volume-relative, under the source's root) from source [id]. */
    suspend fun exclude(id: Long, relPath: String) = withContext(Dispatchers.IO) { dao.addExclude(ExcludeEntity(id, relPath)) }

    suspend fun include(id: Long, relPath: String) = withContext(Dispatchers.IO) { dao.removeExclude(id, relPath) }

    /**
     * How [source] is read on this device: a tree source through SAF (needs the persisted grant), a path source with the
     * File back-end on API ≤ 29 (needs the volume's readable mount point, i.e. the volume mounted). Null = it can't be
     * read now.
     */
    fun fsFor(source: SourceEntity): SourceFs? {
        val directory = volumes.volume(source.volumeId)?.directory
        val access = accessOf(source, Build.VERSION.SDK_INT, ::hasGrant, readable = directory != null)
        logAccess(source, access)
        return when (access) {
            SourceAccess.TREE -> source.treeUri?.let { SafSourceFs(context.contentResolver, it.toUri(), source.volumeId) }
            SourceAccess.PATH -> directory?.let(::FileSourceFs)
            null -> null
        }
    }

    /** The back-end of each source as last logged: a line only when it changes (fsFor runs per track and cover). */
    private val loggedAccess = java.util.concurrent.ConcurrentHashMap<Long, String>()

    private fun logAccess(source: SourceEntity, access: SourceAccess?) {
        val why = when (access) {
            SourceAccess.TREE -> "saf"
            SourceAccess.PATH -> "file"
            null -> when {
                source.treeUri != null -> "none: tree grant missing"
                Build.VERSION.SDK_INT > Build.VERSION_CODES.Q -> "none: a path source on API ${Build.VERSION.SDK_INT}"
                else -> "none: file=${volumes.volume(source.volumeId)?.fileAccess ?: "volume not listed"}"
            }
        }
        if (loggedAccess.put(source.id, why) != why) {
            journal.event("SOURCE ${source.id} ${LocalJournal.vol(source.volumeId)} ${if (source.treeUri != null) "tree" else "path"} → $why")
        }
    }

    /**
     * Whether the app still holds the persisted read grant of [treeUri] ("needs access again" when not). The system's
     * list is asked at most every [GRANTS_FOR_MS] (the resolver asks on the main thread); adding or removing a source
     * asks afresh.
     */
    fun hasGrant(treeUri: String): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val known = grants.takeIf { now - grantsAt < GRANTS_FOR_MS } ?: context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }.map { it.uri.toString() }.toSet().also {
                grants = it
                grantsAt = now
            }
        return treeUri in known
    }

    @Volatile private var grants: Set<String> = emptySet()
    @Volatile private var grantsAt = Long.MIN_VALUE / 2

    private fun forgetGrants() {
        grantsAt = Long.MIN_VALUE / 2
    }

    /**
     * Adds the folder [relPath] of volume [volumeId] chosen in the app's own chooser (API ≤ 29: read as a path with
     * `READ_EXTERNAL_STORAGE`, no tree grant). The same folder twice returns the existing source.
     */
    suspend fun addPath(volumeId: String, relPath: String, now: Long = System.currentTimeMillis()): SourceEntity? = withContext(Dispatchers.IO) {
        if (!LocalRefs.isValidRelPath(relPath, allowRoot = true)) return@withContext null
        volumes.refresh()
        val volume = volumes.volume(volumeId)
        journal.event("ADD path ${LocalJournal.vol(volumeId)} ${LocalJournal.path(relPath)}; ${volume?.let(LocalJournal::volume) ?: "volume not listed"}")
        if (volume == null) return@withContext null
        db.withTransaction {
            dao.upsertVolume(VolumeEntity(volume.id, volume.label, now))
            dao.find(volumeId, relPath) ?: SourceEntity(volumeId = volumeId, rootRel = relPath, treeUri = null, addedAt = now)
                .let { it.copy(id = dao.insert(it)) }
        }
    }

    /** The back-end a source is read through. */
    enum class SourceAccess { TREE, PATH }

    companion object {
        /**
         * Which back-end reads [source] now ([fsFor]): a tree source only through its grant ([granted]), on any API
         * level; a path source only with the file API on API ≤ 29 and only while its volume's mount point is
         * [readable]. Null = it can't be read now.
         */
        fun accessOf(source: SourceEntity, sdk: Int, granted: (String) -> Boolean, readable: Boolean): SourceAccess? {
            val tree = source.treeUri
            return when {
                tree != null -> SourceAccess.TREE.takeIf { granted(tree) }
                sdk <= Build.VERSION_CODES.Q && readable -> SourceAccess.PATH
                else -> null
            }
        }

        /** The volume and folder of a tree of the external storage provider; null for any other tree. */
        fun treePath(treeUri: Uri): LocalPath? {
            if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
            return parseDocumentId(runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null)
        }

        /** `6917-1912:Music/Fleur` → (`6917-1912`, `Music/Fleur`); `primary:` → (`primary`, ``); null when malformed. */
        fun parseDocumentId(documentId: String): LocalPath? {
            val colon = documentId.indexOf(':').takeIf { it > 0 } ?: return null
            val relPath = documentId.substring(colon + 1).trim('/')
            if (!LocalRefs.isValidRelPath(relPath, allowRoot = true)) return null
            return LocalPath(documentId.substring(0, colon), relPath)
        }

        /** The root document of volume [volumeId] in the external storage provider (the system picker's start). */
        fun volumeRootUri(volumeId: String): Uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "$volumeId:")

        /**
         * The document of [relPath] on volume [volumeId], the system picker's start when the app's own chooser can't
         * read that folder (a USB drive on Android 9–10, D93); the volume's root document when [relPath] is empty.
         */
        fun volumeDocumentUri(volumeId: String, relPath: String): Uri =
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "$volumeId:${relPath.trim('/')}")

        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val GRANTS_FOR_MS = 10_000L
    }
}
