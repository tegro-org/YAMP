package org.tegro.yamp.provider.local

import java.util.Locale

/**
 * Which files the scanner looks at (docs/phase-4d-local-files.md §5: "extensions decide candidates"). A [PLAYABLE]
 * extension is read (tags, codec); its codec may still turn out unplayable (ALAC in `.m4a`), which the reader decides.
 * An [UNSUPPORTED] one is a known audio format Stage 1 can't play: it is only counted ("Skipped: 12 APE"), never
 * read. Anything else (images, `.cue`, `.txt`, video) is not a candidate.
 */
object AudioFormats {
    /** Formats Media3 + the platform decoders play (Stage 1). `m4b` = AAC audiobooks. */
    val PLAYABLE: Set<String> = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav")

    /** Known audio formats without a Stage 1 decoder or extractor (Stage 2: FFmpeg, own extractors). */
    val UNSUPPORTED: Set<String> = setOf("ape", "wv", "dsf", "dff", "wma", "mpc", "tak", "tta", "aif", "aiff")

    /** The extension of [fileName], lowercase, without the dot; empty when there is none (or only a leading dot). */
    fun extension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(dot + 1).lowercase(Locale.ROOT) else ""
    }

    /** Whether the scanner keeps [fileName] as a candidate (playable or counted as unsupported). */
    fun isCandidate(fileName: String): Boolean = extension(fileName).let { it in PLAYABLE || it in UNSUPPORTED }

    /** Whether [fileName]'s extension is one Stage 1 reads. */
    fun isPlayable(fileName: String): Boolean = extension(fileName) in PLAYABLE
}

/**
 * Folder cover detection (docs/phase-4d-local-files.md §7): `cover.*`, `folder.*`, `front.*`, `album.*` in this order
 * (jpg / jpeg / png / webp, any case), else the folder's single image, else none. Detection only: the index stores
 * the file name; thumbnails are step 7.
 */
object FolderArt {
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private val PREFERRED = listOf("cover", "folder", "front", "album")

    fun isImage(fileName: String): Boolean = AudioFormats.extension(fileName) in IMAGE_EXTENSIONS

    /** The cover among a folder's file names; null when there is none or several unnamed images. */
    fun pick(fileNames: List<String>): String? {
        val images = fileNames.filter(::isImage).sorted()
        for (name in PREFERRED) {
            images.firstOrNull { FileNameFallback.baseName(it).lowercase(Locale.ROOT) == name }?.let { return it }
        }
        return images.singleOrNull()
    }
}
