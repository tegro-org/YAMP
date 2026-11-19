package org.tegro.yamp.data.player

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tegro.yamp.domain.provider.Decryption
import org.tegro.yamp.domain.provider.ResolvedStream
import org.tegro.yamp.domain.provider.StreamQuality

/**
 * What [StreamCache] keeps with a cache entry so a fully cached track plays offline after a restart: the link it was
 * fetched by (for Yandex its URI fragment carries the decryption key — it never leaves the app's private cache
 * database and is never logged), its quality, decryption scheme and length. Headers are never kept.
 */
@Serializable
internal data class StoredStream(
    val uri: String,
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val decryption: String? = null,
    val durationMs: Long? = null
) {
    fun toStream(cacheKey: String) = ResolvedStream(
        uri = uri,
        quality = codec?.let { StreamQuality(it, bitrateKbps ?: 0) },
        decryption = decryption?.let { Decryption(it) },
        cacheKey = cacheKey,
        durationMs = durationMs
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(stream: ResolvedStream): String = json.encodeToString(
            serializer(),
            StoredStream(
                uri = stream.uri,
                codec = stream.quality?.codec,
                bitrateKbps = stream.quality?.bitrateKbps,
                decryption = stream.decryption?.scheme,
                durationMs = stream.durationMs
            )
        )

        /** Null when the stored text can't be read (an older or damaged entry): the track then needs the network. */
        fun decode(text: String, cacheKey: String): ResolvedStream? =
            runCatching { json.decodeFromString(serializer(), text).toStream(cacheKey) }.getOrNull()
    }
}
