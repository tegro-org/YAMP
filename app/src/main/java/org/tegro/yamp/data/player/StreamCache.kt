package org.tegro.yamp.data.player

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import org.tegro.yamp.domain.provider.ResolvedStream
import java.io.File
import java.io.InputStream
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
 * to the decoder, above the cache ([EncrawDataSourceFactory]). A stream sent with no length (a server transcode) is
 * never written here while it plays: it is fetched whole into a growing part file ([progressive], D91) and imported
 * once complete.
 */
/** Streams whose bytes are all on disk (D86): what a resolver can fall back on with no network. */
fun interface CachedStreams {
    /** A stream of a track whose bytes are all on disk; [keyPrefix] is [StreamCache.keyPrefix]. */
    fun completeStreamFor(keyPrefix: String): ResolvedStream?

    /**
     * Keeps what plays [stream] with its cache entry, so a track whose bytes all end up on disk plays with no network,
     * even after a restart. No-op where nothing is cached.
     */
    fun remember(stream: ResolvedStream) = Unit

    /**
     * Every byte of [key]'s stream, read from disk — the cache entry or a converted stream's complete download (D91) —
     * for a download that would otherwise fetch it again; null unless all of it is there (never a partial entry).
     */
    fun openComplete(key: String): InputStream? = null
}

@androidx.annotation.OptIn(UnstableApi::class)
class StreamCache internal constructor(
    val cache: Cache,
    private val evictor: Evictor,
    partsDir: File,
    /** Where converted streams are downloaded from (D91): the stream host's HTTP source with the longer read timeout. */
    downloadSource: DataSource.Factory = EncrawDataSourceFactory.httpFactory(PlaybackBuffering.DOWNLOAD_READ_TIMEOUT_MS)
) : CachedStreams {

    /** The downloads of converted streams (D91): they read through [wrap] as route 2 and are imported here when complete. */
    val progressive = ProgressiveDownloads(partsDir, downloadSource, ::importPart)

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

    /** Told the key each time a track is handed out from disk with no link (the journal's `CACHE offline start`). */
    @Volatile
    var onOfflineStart: ((key: String) -> Unit)? = null

    /**
     * Also written into the entry's own metadata in the cache's database, so it outlives the process and goes with the
     * entry when it is evicted or cleared (the metadata of a key is dropped with its last byte). Headers are not kept:
     * they may carry a token, and a stream that needs them is rebuilt by its resolver.
     */
    override fun remember(stream: ResolvedStream) {
        val key = stream.cacheKey ?: return
        if (stream.localFile) return
        streams[key] = stream
        if (!enabled || stream.headers.isNotEmpty()) return
        runCatching {
            cache.applyContentMetadataMutations(
                key,
                ContentMetadataMutations().set(META_STREAM, StoredStream.encode(stream))
            )
        }
    }

    /** How far into each key the players have read (the furthest byte). */
    private val readTo = ConcurrentHashMap<String, Long>()

    /**
     * The share of a converted stream's download on disk, 0..1 (canvas 5e) — its size is known only at its end:
     * estimated from the bytes a player read of [key] for the [bufferedMs] it has loaded, over the track's
     * [durationMs]. 1 once complete; 0 with nothing to go by yet (no percent shown).
     */
    fun downloadShare(key: String, bufferedMs: Long, durationMs: Long): Float {
        if (isComplete(key) || progressive.isComplete(key)) return 1f
        return estimateShare(readTo[key] ?: 0L, bufferedMs, durationMs, progressive.written(key))
    }

    /** Bytes kept on disk now: the cache and the converted streams being downloaded. */
    val usedBytes: Long get() = cache.cacheSpace + progressive.partsBytes

    /** Every byte of [key] is on disk (its length known from a full write). */
    fun isComplete(key: String): Boolean = completeUnder(key)

    private fun completeUnder(key: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0L && cache.isCached(key, 0L, length)
    }

    override fun openComplete(key: String): InputStream? {
        // A complete part not imported (yet, or ever: larger than the cache, the cache off) — else the cache entry
        progressive.openComplete(key)?.let { return it }
        if (!enabled || !completeUnder(key)) return null
        // Cache only: a span evicted meanwhile fails the read rather than reaching any network
        val source = CacheDataSource(cache, null)
        val spec = DataSpec.Builder().setUri(("cache:///" + Uri.encode(key)).toUri()).setKey(key).build()
        return DataSourceInputStream(source, spec).apply { open() }
    }

    /** Journal lines of the converted streams' downloads and imports (`CACHE progressive …`), by key. */
    @Volatile
    var onProgressive: (key: String, detail: String) -> Unit = { _, _ -> }
        set(value) {
            field = value
            progressive.journal = value
        }

    /**
     * A converted stream's complete [part] goes into the cache under its key (one writer, its length known), with the
     * stream kept beside it for offline starts. Not with the cache off, nor when it is larger than the whole cache.
     */
    private fun importPart(stream: ResolvedStream, part: File, length: Long, onCancel: (() -> Unit) -> Unit): Boolean {
        val key = stream.cacheKey ?: return false
        if (!enabled) {
            onProgressive(key, "not imported: the cache is off")
            return false
        }
        if (length > maxBytes) {
            onProgressive(key, "not imported: larger than the cache")
            return false
        }
        // Whatever an older build streamed under the key goes first: it is another transcode's bytes
        runCatching { cache.removeResource(key) }
        val spec = DataSpec.Builder().setUri(Uri.fromFile(part)).setKey(key).build()
        val writer = CacheWriter(CacheDataSource(cache, FileDataSource(), CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR), spec, null, null)
        onCancel { writer.cancel() }
        val error = runCatching { writer.cache() }.exceptionOrNull()
        val ok = error == null && completeUnder(key)
        if (ok) remember(stream)
        onProgressive(key, if (ok) "imported" else "not imported: ${error?.message ?: "incomplete"}")
        return ok
    }

    /**
     * Once at startup (D91): the playing tracks' copies of older builds (`#copy`) and the partial entries of streams
     * sent with no length go — neither can ever be completed. A partial entry of a stream with a length (an original)
     * stays: it resumes by byte range.
     */
    fun dropStaleEntries() {
        runCatching { cache.keys.toList() }.getOrDefault(emptyList()).forEach { key ->
            val length = runCatching { ContentMetadata.getContentLength(cache.getContentMetadata(key)) }.getOrDefault(C.LENGTH_UNSET.toLong())
            if (key.endsWith(OLD_COPY_SUFFIX) || length == C.LENGTH_UNSET.toLong()) runCatching { cache.removeResource(key) }
        }
    }

    /**
     * A stream for a track whose bytes are all on disk, for when no fresh link can be had (offline): its key is
     * what finds the bytes, so the old link is never fetched. [keyPrefix] is "account|track|".
     */
    override fun completeStreamFor(keyPrefix: String): ResolvedStream? {
        if (!enabled) return null
        val found = streams.entries.firstOrNull { it.key.startsWith(keyPrefix) && isComplete(it.key) }?.value
            ?: run {
                // After a restart: what was kept with the entry in the cache's database
                val keys = runCatching { cache.keys.filter { it.startsWith(keyPrefix) } }.getOrDefault(emptyList())
                keys.firstNotNullOfOrNull { key ->
                    if (!isComplete(key)) return@firstNotNullOfOrNull null
                    val stored = runCatching { cache.getContentMetadata(key).get(META_STREAM, null as String?) }.getOrNull()
                    stored?.let { StoredStream.decode(it, key) }?.also { streams[key] = it }
                }
            }
        found?.cacheKey?.let { key -> onOfflineStart?.invoke(key) }
        return found
    }

    /** Every entry of [account] goes (sign-out): its downloads first, then its bytes and what was kept with them. */
    fun removeAccount(account: String) {
        val prefix = "$account|"
        progressive.cancelAll(prefix)
        streams.keys.removeIf { it.startsWith(prefix) }
        readTo.keys.removeIf { it.startsWith(prefix) }
        runCatching { cache.keys.filter { it.startsWith(prefix) } }.getOrDefault(emptyList())
            .forEach { runCatching { cache.removeResource(it) } }
    }

    /**
     * What is on disk of a track, whatever link it came by ([keyPrefix] = "account|track|"): all of it, its start
     * ([START_BYTES] or more from byte 0 — a skip to it sounds at once), or nothing usable.
     */
    fun cachedState(keyPrefix: String): CachedState {
        // A converted stream's download counts too (D91): complete, or with its start written
        val (downloaded, downloading) = progressive.progressOf(keyPrefix)
        if (downloaded) return CachedState.COMPLETE
        if (!enabled) return if (downloading >= START_BYTES) CachedState.START else CachedState.NONE
        val keys = runCatching { cache.keys.filter { it.startsWith(keyPrefix) } }.getOrDefault(emptyList())
        if (keys.any { isComplete(it) }) return CachedState.COMPLETE
        val start = keys.maxOfOrNull { runCatching { cache.getCachedLength(it, 0L, Long.MAX_VALUE) }.getOrDefault(0L) } ?: 0L
        return if (maxOf(start, downloading) >= START_BYTES) CachedState.START else CachedState.NONE
    }

    enum class CachedState { NONE, START, COMPLETE }

    fun clear() {
        progressive.cancelAll()
        streams.clear()
        readTo.clear()
        cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
    }

    /**
     * Wraps [upstream]. A network request with a cache key: (1) all of it in the cache → read from there, seekable;
     * (2) a converted stream ([ProgressiveDownloads.register]) → its growing file ([GrowingFileSource], D91); (3) else
     * through the cache, filling it as it goes — and one that turns out to have no length at its start (the probe
     * missed) becomes (2). One without a key (a copy on the device, radio) goes straight to [upstream].
     */
    fun wrap(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory { Switch(upstream.createDataSource()) }

    /** A cache data source over [upstream] for a full write ([NextTrackCacher]). */
    fun writingSource(upstream: DataSource): CacheDataSource =
        CacheDataSource(cache, upstream, CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private inner class Switch(private val upstream: DataSource) : DataSource {
        private val cached = CacheDataSource(cache, upstream, CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        private var active: DataSource = upstream

        /** The key being read and where the read is now (absolute), for [downloadShare]. */
        private var key: String? = null
        private var at = 0L

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val k = dataSpec.key
            at = dataSpec.position
            key = null
            if (k == null || !dataSpec.uri.scheme.isNetwork()) {
                active = upstream
                return upstream.open(dataSpec)
            }
            key = k
            if (enabled && completeUnder(k)) {
                active = cached
                return cached.open(dataSpec)
            }
            if (progressive.isProgressive(k)) openGrowing(k, dataSpec)?.let { return it }
            if (enabled && completeUnder(k)) { // imported meanwhile
                active = cached
                return cached.open(dataSpec)
            }
            active = if (enabled) cached else upstream
            val length = active.open(dataSpec)
            if (length != C.LENGTH_UNSET.toLong() || dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) return length
            // No length at its start: a conversion the probe did not see. One wasted request; from now on route 2
            try {
                active.close()
            } finally {
                active = upstream
            }
            if (enabled) runCatching { cache.removeResource(k) }
            progressive.register(ResolvedStream(dataSpec.uri.toString(), cacheKey = k, progressive = true))
            onProgressive(k, "no length at its start: downloaded whole")
            return openGrowing(k, dataSpec) ?: throw java.io.IOException("progressive stream $k could not be opened")
        }

        private fun openGrowing(k: String, dataSpec: DataSpec): Long? {
            val reader = progressive.attach(k, dataSpec.uri.toString()) ?: return null
            val source = GrowingFileSource(reader, dataSpec.uri)
            active = source
            return try {
                source.open(dataSpec)
            } catch (e: java.io.IOException) {
                source.close()
                active = upstream
                throw e
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = active.read(buffer, offset, length)
            val k = key
            if (n > 0 && k != null) {
                at += n
                readTo.merge(k, at, ::maxOf)
            }
            return n
        }

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
            createWithLimit(context, bytesOf(maxMb), dir, File(context.cacheDir, "stream_parts"))

        internal fun createWithLimit(
            context: Context,
            maxBytes: Long,
            dir: File,
            partsDir: File = File(dir.path + "_parts"),
            downloadSource: DataSource.Factory? = null
        ): StreamCache {
            val evictor = Evictor(maxBytes)
            val cache = SimpleCache(dir, evictor, StandaloneDatabaseProvider(context))
            return if (downloadSource == null) StreamCache(cache, evictor, partsDir) else StreamCache(cache, evictor, partsDir, downloadSource)
        }

        /** The key for [trackId] of [account] in one quality: the same three always give the same key (D86). */
        fun key(account: String?, trackId: String, quality: String): String = "${account ?: "none"}|$trackId|$quality"

        /** What the playing track's disk copy of older builds (before D91) was kept under: dropped at startup. */
        private const val OLD_COPY_SUFFIX = "#copy"

        /** The start of every key of [trackId] of [account], whatever the quality. */
        fun keyPrefix(account: String?, trackId: String): String = "${account ?: "none"}|$trackId|"

        /** About 15 s of a 256 kbps stream: enough on disk for a skip to the track to start at once. */
        const val START_BYTES = 512L * 1024L

        /** The metadata name under which the stream of an entry is kept (a JSON string). */
        internal const val META_STREAM = "yamp-stream"

        private fun String?.isNetwork(): Boolean = this == "http" || this == "https"

        /**
         * [written] bytes of a file whose size is estimated from [readBytes] read for [bufferedMs] of sound, over
         * [durationMs]; 0 with nothing to go by, at most [MAX_ESTIMATE] (only a complete file is 1).
         */
        fun estimateShare(readBytes: Long, bufferedMs: Long, durationMs: Long, written: Long): Float {
            if (readBytes <= 0L || bufferedMs <= 0L || durationMs <= 0L) return 0f
            val total = readBytes.toDouble() / bufferedMs * durationMs
            return (maxOf(written, readBytes) / total).coerceIn(0.0, MAX_ESTIMATE).toFloat()
        }

        private const val MAX_ESTIMATE = 0.99
    }
}
