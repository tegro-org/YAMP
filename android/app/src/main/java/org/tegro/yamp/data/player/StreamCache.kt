package org.tegro.yamp.data.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import org.tegro.yamp.domain.provider.ResolvedStream
import java.io.File
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

/**
 * The on-disk stream cache (D86): the bytes of what was played or fetched ahead, kept by account + track + quality
 * ([ResolvedStream.cacheKey]), never by the short-lived link. The next track is written here in full ahead of time
 * ([NextTrackCacher]), so Next is instant even after a dropout; the previous one stays, so Back is instant.
 * Least recently used goes first once [maxBytes] is reached; 0 switches the cache off (nothing read or written).
 * Separate from downloads and liked tracks, which keep their own storage.
 *
 * What is cached is what the network sends: an encrypted stream stays encrypted on disk and is decrypted on the way
 * to the decoder, above the cache ([EncrawDataSourceFactory]).
 */
/** Streams whose bytes are all on disk (D86): what a resolver can fall back on with no network. */
fun interface CachedStreams {
    /** A stream of a track whose bytes are all on disk; [keyPrefix] is [StreamCache.keyPrefix]. */
    fun completeStreamFor(keyPrefix: String): ResolvedStream?
}

@androidx.annotation.OptIn(UnstableApi::class)
class StreamCache internal constructor(
    val cache: Cache,
    private val evictor: Evictor
) : CachedStreams {

    /** The size limit; 0 = off. Lowering it evicts at once. */
    var maxBytes: Long
        get() = evictor.maxBytes
        set(value) {
            evictor.maxBytes = value.coerceAtLeast(0L)
            evictor.evictNow(cache)
        }

    val enabled: Boolean get() = evictor.maxBytes > 0L

    /** The last stream seen for a key: a track whose bytes are all on disk plays from it with no fresh link. */
    private val streams = ConcurrentHashMap<String, ResolvedStream>()

    fun remember(stream: ResolvedStream) {
        stream.cacheKey?.let { streams[it] = stream }
    }

    /** Bytes kept on disk now. */
    val usedBytes: Long get() = cache.cacheSpace

    /** Every byte of [key] is on disk (its length known from a full write). */
    fun isComplete(key: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0L && cache.isCached(key, 0L, length)
    }

    /**
     * A stream for a track whose bytes are all on disk, for when no fresh link can be had (offline): its key is
     * what finds the bytes, so the old link is never fetched. [keyPrefix] is "account|track|".
     */
    override fun completeStreamFor(keyPrefix: String): ResolvedStream? =
        streams.entries.firstOrNull { it.key.startsWith(keyPrefix) && isComplete(it.key) }?.value

    fun clear() {
        streams.clear()
        cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
    }

    /**
     * Wraps [upstream]: a request with a cache key (and the cache on) reads through the cache, filling it as it
     * goes; one without a key (a copy on the device, radio) goes straight to [upstream].
     */
    fun wrap(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory { Switch(upstream.createDataSource()) }

    /** A cache data source over [upstream] for a full write ([NextTrackCacher]). */
    fun writingSource(upstream: DataSource): CacheDataSource =
        CacheDataSource(cache, upstream, CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private inner class Switch(private val upstream: DataSource) : DataSource {
        private val cached = CacheDataSource(cache, upstream, CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        private var active: DataSource = upstream

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            active = if (dataSpec.key != null && enabled && dataSpec.uri.scheme.isNetwork()) cached else upstream
            return active.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active.read(buffer, offset, length)

        override fun getUri(): Uri? = active.uri

        override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders

        override fun close() {
            try {
                active.close()
            } finally {
                active = upstream
            }
        }
    }

    /**
     * Least recently used first, with a limit that can change while the app runs (Media3's own evictor takes it
     * once, at construction). [maxBytes] 0 keeps nothing.
     */
    internal class Evictor(@Volatile var maxBytes: Long) : CacheEvictor {
        private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan>({ it.lastTouchTimestamp }, { it.key }, { it.position }))
        private var size = 0L

        override fun requiresCacheSpanTouches(): Boolean = true
        override fun onCacheInitialized() = Unit

        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            if (length != C_LENGTH_UNSET) evict(cache, length)
        }

        @Synchronized
        override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            spans.add(span)
            size += span.length
            evict(cache, 0L)
        }

        @Synchronized
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
            if (spans.remove(span)) size -= span.length
        }

        @Synchronized
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            onSpanRemoved(cache, oldSpan)
            onSpanAdded(cache, newSpan)
        }

        fun evictNow(cache: Cache) = evict(cache, 0L)

        @Synchronized
        private fun evict(cache: Cache, required: Long) {
            while (size + required > maxBytes && spans.isNotEmpty()) {
                val oldest = spans.first()
                // A span being written or read is locked: try the next one rather than loop on it
                val removed = runCatching { cache.removeSpan(oldest) }.isSuccess
                if (!removed || spans.contains(oldest)) {
                    spans.remove(oldest)
                    size -= oldest.length
                }
            }
        }

        private companion object {
            const val C_LENGTH_UNSET = -1L
        }
    }

    companion object {
        const val DEFAULT_MB = 500
        private const val MB = 1024L * 1024L

        fun bytesOf(mb: Int): Long = mb.coerceAtLeast(0) * MB

        fun create(context: Context, maxMb: Int = DEFAULT_MB, dir: File = File(context.cacheDir, "stream_cache")): StreamCache =
            createWithLimit(context, bytesOf(maxMb), dir)

        internal fun createWithLimit(context: Context, maxBytes: Long, dir: File): StreamCache {
            val evictor = Evictor(maxBytes)
            val cache = SimpleCache(dir, evictor, StandaloneDatabaseProvider(context))
            return StreamCache(cache, evictor)
        }

        /** The key for [trackId] of [account] in one quality: the same three always give the same key (D86). */
        fun key(account: String?, trackId: String, quality: String): String = "${account ?: "none"}|$trackId|$quality"

        /** The start of every key of [trackId] of [account], whatever the quality. */
        fun keyPrefix(account: String?, trackId: String): String = "${account ?: "none"}|$trackId|"

        private fun String?.isNetwork(): Boolean = this == "http" || this == "https"
    }
}
