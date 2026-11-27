package org.tegro.yamp.data.provider.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import androidx.core.content.edit
import androidx.core.graphics.scale
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tegro.yamp.BuildConfig
import org.tegro.yamp.data.provider.local.db.DirEntity
import org.tegro.yamp.data.provider.local.db.LibraryTrackRow
import org.tegro.yamp.data.provider.local.db.LocalDatabase
import org.tegro.yamp.data.provider.local.db.TrackEntity
import org.tegro.yamp.domain.media.Cover
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Covers of local files (docs/phase-4d-local-files.md §7): the embedded picture of a track, else its folder's
 * `cover.*` / `folder.*` / `front.*` / `album.*` (or single image) as the scan found it, else none (the drawn
 * placeholder). One URL form for Coil, the player's artwork and Android Auto (which loads `content://` only):
 * `content://<app>.artwork/local/<key>/<px>`, served by `ArtworkProvider` through [open].
 *
 * The key names a database row and a version, never a path: `t<file id>-<mtime>` (embedded) or `d<dir id>-<name hash>`
 * (folder image), signed with a per-install secret so another app can't walk the ids. Thumbnails are made lazily —
 * [SMALL_PX] or [LARGE_PX], JPEG — into `cache/local-art/`, the newest [MAX_FILES] kept.
 */
@Singleton
class LocalArt @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: LocalDatabase,
    private val sources: LocalSources
) {
    private val secret: String by lazy {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SECRET, null) ?: ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes).hex().also { prefs.edit { putString(KEY_SECRET, it) } }
    }

    /** The cover of a track as the index knows it; null = none (the placeholder). */
    fun trackCover(row: LibraryTrackRow, dir: DirEntity?): Cover? = when (row.art) {
        TrackEntity.ART_EMBEDDED -> cover(KIND_TRACK, row.fileId, row.mtime.coerceAtLeast(0).toString(RADIX))
        TrackEntity.ART_FILE -> dir?.let(::folderCover)
        else -> null
    }

    /** A folder's own image (`cover.jpg`…); null when the scan found none. */
    fun folderCover(dir: DirEntity): Cover? = dir.artFile?.let { cover(KIND_DIR, dir.id, shortHash(it)) }

    private fun cover(kind: Char, id: Long, version: String): Cover {
        val name = "$kind$id-$version"
        return LocalCover("content://$AUTHORITY/$PATH/$name-${sign(name)}/$SIZE")
    }

    /**
     * The thumbnail for the [key] of a cover URI at about [px] wide; throws [FileNotFoundException] for a key this
     * install did not sign, a row that is gone, an absent volume or a file without a picture.
     */
    fun open(key: String, px: Int): ParcelFileDescriptor {
        val parts = KEY.matchEntire(key)?.groupValues?.takeIf { sign("${it[1]}${it[2]}-${it[3]}") == it[4] } ?: throw FileNotFoundException("unknown")
        val kind = parts[1]
        val id = parts[2]
        val name = "$kind$id-${parts[3]}"
        val size = if (px <= SMALL_PX) SMALL_PX else LARGE_PX
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val file = File(dir, "$name-$size.jpg")
        if (!file.exists()) {
            val bytes = if (kind == KIND_TRACK.toString()) embedded(id.toLong()) else folderImage(id.toLong())
            if (bytes == null || !write(bytes, size, file)) throw FileNotFoundException("no picture")
            trim(dir)
        } else {
            file.setLastModified(System.currentTimeMillis())
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun embedded(fileId: Long): ByteArray? {
        val row = db.library().file(fileId) ?: return null
        return withFile(row.volumeId, row.relPath) { pfd ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(pfd.fileDescriptor)
                retriever.embeddedPicture
            } catch (_: RuntimeException) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    private fun folderImage(dirId: Long): ByteArray? {
        val dir = db.library().dir(dirId) ?: return null
        val name = dir.artFile ?: return null
        return withFile(dir.volumeId, childPath(dir.relPath, name)) { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                val bytes = input.readNBytesCompat(MAX_IMAGE_BYTES + 1)
                bytes.takeIf { it.size <= MAX_IMAGE_BYTES }
            }
        }
    }

    /** Opens [relPath] through the source that covers it (the scanner's back-end); null when it can't be read now. */
    private fun <T> withFile(volumeId: String, relPath: String, read: (ParcelFileDescriptor) -> T?): T? {
        val source = db.sources().all().filter { it.volumeId == volumeId && isUnder(relPath, it.rootRel) }.maxByOrNull { it.rootRel.length } ?: return null
        val fs = sources.fsFor(source) ?: return null
        return try {
            fs.open(relPath).use(read)
        } catch (_: IOException) {
            null
        }
    }

    private fun write(bytes: ByteArray, size: Int, file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val scale = size.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = if (scale < 1f) decoded.scale((decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1)) else decoded
        val ok = writeAtomically(file) { part -> part.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) } }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return ok
    }

    private fun trim(dir: File) {
        val files = dir.listFiles()?.takeIf { it.size > MAX_FILES } ?: return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun sign(name: String): String = sha256(secret + name).take(SIGNATURE_HEX)

    companion object {
        val AUTHORITY: String = BuildConfig.APPLICATION_ID + ".artwork"
        /** The first path segment of a local cover's URI. */
        const val PATH = "local"
        /** The size placeholder of a cover template (as Jellyfin's, `toCoverUrl` fills it). */
        const val SIZE = "{px}"
        const val SMALL_PX = 256
        const val LARGE_PX = 1000
        private const val KIND_TRACK = 't'
        private const val KIND_DIR = 'd'
        private const val RADIX = 36
        private const val PREFS = "local_art"
        private const val KEY_SECRET = "secret"
        private const val SECRET_BYTES = 16
        private const val SIGNATURE_HEX = 16
        private const val CACHE_DIR = "local-art"
        private const val MAX_FILES = 600
        private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        private const val JPEG_QUALITY = 85
        private val KEY = Regex("^([td])(\\d+)-([0-9a-z]+)-([0-9a-f]{16})$")

        /**
         * [write]s into a temporary file of its own beside [target] and renames it over [target]: two callers making
         * the same thumbnail (a widget and the notification) never write one file, a reader never sees half of one.
         */
        internal fun writeAtomically(target: File, write: (File) -> Boolean): Boolean {
            val part = File.createTempFile(target.name, ".part", target.parentFile)
            val ok = try {
                write(part) && part.renameTo(target)
            } finally {
                if (part.exists()) part.delete()
            }
            return ok
        }

        private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).hex()

        private fun shortHash(text: String): String = sha256(text).take(8)

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

        /** `readNBytes` is API 33+ on Android: up to [max] bytes. */
        private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER)
            var total = 0
            while (total < max) {
                val n = read(buffer, 0, minOf(buffer.size, max - total))
                if (n < 0) break
                out.write(buffer, 0, n)
                total += n
            }
            return out.toByteArray()
        }

        private const val BUFFER = 64 * 1024
    }
}
