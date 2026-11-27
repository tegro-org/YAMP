package org.tegro.yamp.provider.local

import java.security.MessageDigest
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.queue.MediaRef

/** A file or folder on a volume: the volume's id (`1A2B-3C4D`, `primary`) and the path from its root, `/`-separated. */
data class LocalPath(val volumeId: String, val relPath: String)

/**
 * The refs of the local provider (docs/phase-4d-local-files.md §3, D95):
 * - track `local:track:<volumeId>/<relPath>`, folder `local:folder:<volumeId>/<relPath>` (the volume root is
 *   `local:folder:<volumeId>/`);
 * - album `local:album:<hash of the album key>`, artist `local:artist:<hash of the normalised name>`, genre likewise.
 *
 * The volume id is part of the ref: the same path on another volume is another track (no cross-volume fallback,
 * user 2026-11-20). `%`, `|` and `:` are percent-escaped (Android Auto ids split at `|`, `:` is kept for Stage 2
 * cue tracks); in the volume id `/` too. Everything else, Cyrillic and spaces included, stays readable.
 */
object LocalRefs {
    val PROVIDER: String = Provider.LOCAL.key
    const val KIND_FOLDER = "folder"
    const val KIND_ALBUM = "album"
    const val KIND_ARTIST = "artist"
    const val KIND_GENRE = "genre"

    private const val HASH_HEX_CHARS = 16

    fun track(volumeId: String, relPath: String): MediaRef =
        MediaRef(PROVIDER, MediaRef.KIND_TRACK, pathId(volumeId, relPath, allowRoot = false))

    fun folder(volumeId: String, relPath: String): MediaRef = MediaRef(PROVIDER, KIND_FOLDER, pathId(volumeId, relPath, allowRoot = true))

    fun album(albumKey: String): MediaRef = MediaRef(PROVIDER, KIND_ALBUM, hash(albumKey))

    fun artist(name: String): MediaRef = MediaRef(PROVIDER, KIND_ARTIST, hash(AlbumGrouping.normalize(name)))

    /** A genre: `local:genre:<hash of the normalised name>`. */
    fun genre(name: String): MediaRef = MediaRef(PROVIDER, KIND_GENRE, hash(AlbumGrouping.normalize(name)))

    /** The volume and path of a local track or folder ref; null for anything else or a malformed id. */
    fun path(ref: MediaRef): LocalPath? {
        if (ref.provider != PROVIDER || (ref.kind != MediaRef.KIND_TRACK && ref.kind != KIND_FOLDER)) return null
        val slash = ref.id.indexOf('/').takeIf { it > 0 } ?: return null
        val volumeId = unescape(ref.id.substring(0, slash)) ?: return null
        val relPath = unescape(ref.id.substring(slash + 1)) ?: return null
        if (!isValidRelPath(relPath, allowRoot = ref.kind == KIND_FOLDER)) return null
        return LocalPath(volumeId, relPath)
    }

    /** [path] of a persisted ref string. */
    fun path(value: String): LocalPath? = MediaRef.parse(value)?.let(::path)

    /**
     * A relative path as the refs accept it: `/`-separated, no leading or trailing `/`, no empty, `.` or `..`
     * segment. The empty path is a volume root (folders only).
     */
    fun isValidRelPath(relPath: String, allowRoot: Boolean): Boolean {
        if (relPath.isEmpty()) return allowRoot
        return relPath.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /** Percent-escapes `%`, `|`, `:` (and `/` when [slash]); every other character stays as it is. */
    fun escape(text: String, slash: Boolean = false): String = buildString(text.length) {
        for (c in text) {
            when {
                c == '%' || c == '|' || c == ':' || (slash && c == '/') -> append('%').append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
                else -> append(c)
            }
        }
    }

    /** The inverse of [escape]; null when a `%` is not followed by two hex digits. */
    fun unescape(text: String): String? {
        if ('%' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '%') {
                out.append(c)
                i++
                continue
            }
            val hi = text.getOrNull(i + 1)?.digitToIntOrNull(16) ?: return null
            val lo = text.getOrNull(i + 2)?.digitToIntOrNull(16) ?: return null
            out.append(((hi shl 4) or lo).toChar())
            i += 3
        }
        return out.toString()
    }

    private fun pathId(volumeId: String, relPath: String, allowRoot: Boolean): String {
        require(volumeId.isNotEmpty()) { "empty volume id" }
        require(isValidRelPath(relPath, allowRoot)) { "not a relative path: $relPath" }
        return escape(volumeId, slash = true) + "/" + escape(relPath)
    }

    private fun hash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_HEX_CHARS)
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
