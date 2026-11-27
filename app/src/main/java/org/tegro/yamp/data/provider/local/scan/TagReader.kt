package org.tegro.yamp.data.provider.local.scan

import org.tegro.yamp.data.provider.local.SourceFs
import org.tegro.yamp.provider.local.TrackTags

/** What reading one file gave. */
sealed interface TagRead {
    /** A playable file: its raw tags (before the file-name fallback), duration, whether it has a picture, its codec. */
    data class Ok(val tags: TrackTags, val durationMs: Long, val embeddedArt: Boolean, val format: String) : TagRead

    /** Parsed, but no decoder on this device (ALAC in `.m4a`): counted as [format], not playable. */
    data class Unsupported(val format: String) : TagRead

    /** Opened, but nothing could parse it (a broken or mislabelled file): counted, not retried until it changes. */
    data object Unreadable : TagRead

    /** Couldn't be opened or read (gone, the volume went away): nothing is written, the next scan tries again. */
    data object IoFailed : TagRead
}

/** Reads one file's tags and codec (docs/phase-4d-local-files.md §6). Blocking; called on the scan thread. */
fun interface TagReader {
    fun read(fs: SourceFs, relPath: String): TagRead
}
