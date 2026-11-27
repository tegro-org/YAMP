package org.tegro.yamp.data.diagnostics

import java.security.MessageDigest

/**
 * A track id as logs and the journal may show it (docs/phase-4d-local-files.md §10): a local file's id is its volume
 * and path (`6917-1912/Music/…`), so it is written as `#<8 hex>` of its hash; a service's id (Yandex, Jellyfin) stays
 * as it is. Local ids are the only ones with a `/`.
 */
fun logId(trackId: String?): String = when {
    trackId == null -> "null"
    '/' in trackId -> "local:track:" + logHash(trackId)
    else -> trackId
}

/** `#<8 hex>` of [text]'s hash: a volume id or a path in the journal (the same text always gives the same tag). */
fun logHash(text: String): String =
    "#" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(HASH_BYTES).joinToString("") { "%02x".format(it) }

private const val HASH_BYTES = 4
