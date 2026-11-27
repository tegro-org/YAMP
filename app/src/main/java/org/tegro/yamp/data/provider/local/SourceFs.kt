package org.tegro.yamp.data.provider.local

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** One entry of a directory listing: its [name], and for a file its [size] and [mtime] (ms). */
data class FsEntry(val name: String, val isDirectory: Boolean, val size: Long, val mtime: Long)

/**
 * Read access to one volume (docs/phase-4d-local-files.md §1, D93). Paths are volume-relative, `/`-separated, `""` = the
 * volume root — the same paths as the refs, so both back-ends index identically.
 * - [FileSourceFs]: a folder from the app's own chooser on API ≤ 29 (`READ_EXTERNAL_STORAGE`, `java.io.File`);
 * - [SafSourceFs]: a persisted SAF tree grant — every source on API ≥ 30 (no media permission), a USB drive on
 *   API ≤ 29 (its mount point is not readable by apps there).
 */
interface SourceFs {
    /** The back-end's name for the journal (`file`, `saf`). */
    val kind: String get() = "?"

    /** Why the last [list] returned null, for the journal: an exception name or errno, never a path. */
    val lastError: String? get() = null

    /**
     * The entries of directory [relPath]; **null when it can't be listed** (I/O error, unmounted, no access). An empty
     * list means an empty directory, never a failure (D47: an I/O error is not an empty folder).
     */
    fun list(relPath: String): List<FsEntry>?

    /** A read-only descriptor of file [relPath]; throws [java.io.IOException] (e.g. [FileNotFoundException]). */
    fun open(relPath: String): ParcelFileDescriptor

    /** The uri tag reading and playback use for [relPath] (`file://` or `content://`). */
    fun uri(relPath: String): Uri
}

/**
 * [SourceFs] over the file API under [volumeRoot] (`/storage/<uuid>`, the primary external storage directory). One
 * `stat` per entry (`readAttributes`), not three (`isDirectory`, `length`, `lastModified`): on FUSE-backed storage
 * each is a round trip.
 */
class FileSourceFs(private val volumeRoot: File) : SourceFs {
    override val kind: String get() = "file"

    @Volatile override var lastError: String? = null
        private set

    override fun list(relPath: String): List<FsEntry>? = try {
        Files.newDirectoryStream(file(relPath).toPath()).use { stream -> stream.map(::entry) }
    } catch (e: IOException) {
        // FileSystemException.reason is the errno text ("Permission denied"); its message would name the path
        lastError = listOfNotNull(e.javaClass.simpleName, (e as? FileSystemException)?.reason).joinToString(": ")
        null
    } catch (e: SecurityException) {
        lastError = e.javaClass.simpleName
        null
    }

    private fun entry(path: Path): FsEntry {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val isDirectory = attrs.isDirectory
        return FsEntry(path.fileName.toString(), isDirectory, if (isDirectory) 0 else attrs.size(), attrs.lastModifiedTime().toMillis())
    }

    override fun open(relPath: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(relPath), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun uri(relPath: String): Uri = Uri.fromFile(file(relPath))

    private fun file(relPath: String): File = if (relPath.isEmpty()) volumeRoot else File(volumeRoot, relPath)
}

/**
 * [SourceFs] over a SAF tree grant of the system's external storage provider, whose document ids are
 * `<volumeId>:<relPath>` (`6917-1912:Music/Fleur`, `primary:Music`) — so every path under the tree maps to its
 * document id without a lookup. One `buildChildDocumentsUriUsingTree` cursor per directory with the four columns the
 * index needs (the name is the id's last segment); never `DocumentFile.listFiles()` and per-file calls.
 */
class SafSourceFs(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val volumeId: String
) : SourceFs {
    override val kind: String get() = "saf"

    @Volatile override var lastError: String? = null
        private set

    override fun list(relPath: String): List<FsEntry>? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId(relPath))
        return try {
            resolver.query(children, COLUMNS, null, null, null)?.use(::entries).also { if (it == null) lastError = "no cursor" }
        } catch (e: SecurityException) {
            failed(e)
        } catch (e: IllegalArgumentException) {
            // The provider throws this for a document it can't find (an unmounted volume, a missing folder)
            failed(e)
        } catch (e: IllegalStateException) {
            failed(e)
        }
    }

    private fun failed(e: Exception): List<FsEntry>? {
        lastError = e.javaClass.simpleName
        return null
    }

    private fun entries(cursor: Cursor): List<FsEntry> {
        val entries = ArrayList<FsEntry>(cursor.count)
        while (cursor.moveToNext()) {
            val id = cursor.getString(0) ?: continue
            val isDirectory = cursor.getString(1) == Document.MIME_TYPE_DIR
            val size = if (isDirectory || cursor.isNull(2)) 0L else cursor.getLong(2)
            val mtime = if (cursor.isNull(3)) 0L else cursor.getLong(3)
            entries += FsEntry(id.substringAfter(':').substringAfterLast('/'), isDirectory, size, mtime)
        }
        return entries
    }

    override fun open(relPath: String): ParcelFileDescriptor =
        try {
            resolver.openFileDescriptor(uri(relPath), "r") ?: throw FileNotFoundException("no descriptor")
        } catch (e: SecurityException) {
            throw FileNotFoundException(e.message).apply { initCause(e) }
        } catch (e: IllegalArgumentException) {
            throw FileNotFoundException(e.message).apply { initCause(e) }
        }

    override fun uri(relPath: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId(relPath))

    private fun documentId(relPath: String): String = "$volumeId:$relPath"

    private companion object {
        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED
        )
    }
}
