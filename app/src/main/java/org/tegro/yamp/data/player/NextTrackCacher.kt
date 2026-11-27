package org.tegro.yamp.data.player

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.tegro.yamp.domain.provider.ResolvedStream

/** Fetches the next track's whole file to disk ahead of time (D86); the controller owns when (D74, D80). */
interface NextTrackFetch {
    enum class Outcome { ALREADY_ON_DISK, WRITTEN, FAILED, CANCELLED }

    /** [playing]: the playing track's converted stream finished downloading (D91), not the next one's write. */
    data class Result(val trackId: String, val outcome: Outcome, val playing: Boolean = false)

    /** Starts writing [stream] of [trackId] (another track's write is cancelled first). */
    fun prefetch(trackId: String, stream: ResolvedStream)

    /** Stops the running write: the next track changed. What was written so far stays (a converted stream's goes). */
    fun cancel()

    /** Every byte of [stream] is on disk. */
    fun isOnDisk(stream: ResolvedStream): Boolean

    /** What of [trackId] (played under [account]) is on disk already, whatever link it came by. */
    fun cachedState(account: String?, trackId: String): StreamCache.CachedState = StreamCache.CachedState.NONE

    /** The controller's link (D91): a converted stream's restart is held while there is none. */
    fun linkChanged(up: Boolean) = Unit

    /** The track being written now, or null. */
    val writingTrackId: String?

    val results: Flow<Result>

    /** No cache: nothing is fetched ahead (tests, the cache switched off). */
    object None : NextTrackFetch {
        override fun prefetch(trackId: String, stream: ResolvedStream) = Unit
        override fun cancel() = Unit
        override fun isOnDisk(stream: ResolvedStream): Boolean = false
        override val writingTrackId: String? = null
        override val results: Flow<Result> = emptyFlow()
    }
}

/**
 * Writes the next track to the on-disk cache in full, ahead of time (D86): one track at a time, on [io], and
 * cancelled as soon as the next track changes (a skip, a queue change). With its whole file on disk the next track
 * starts at once even after a dropout. Streams without a cache key (a copy on the device, radio) are never written.
 * A converted stream (no length) is downloaded whole by [StreamCache.progressive] instead (D91): the warm player reads
 * the same download, so it is fetched once.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class NextTrackCacher(
    private val streamCache: StreamCache,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val upstream: DataSource.Factory = EncrawDataSourceFactory.httpFactory()
) : NextTrackFetch {

    private val _results = MutableSharedFlow<NextTrackFetch.Result>(extraBufferCapacity = 16)
    override val results: Flow<NextTrackFetch.Result> = _results.asSharedFlow()

    private val downloads get() = streamCache.progressive

    private var job: Job? = null

    @Volatile
    private var writer: CacheWriter? = null

    /** The track being written now as the next one (its id), or null. */
    @Volatile
    override var writingTrackId: String? = null
        private set

    /** The key the next track's converted stream is downloaded under. */
    @Volatile
    private var downloadKey: String? = null

    init {
        // The playing track's download complete: reported as the playing track's (the bar shows it loaded to the end)
        downloads.onFinished = { key, state ->
            if (key == downloads.playingNow && state == ProgressiveDownloads.State.COMPLETE) {
                _results.tryEmit(NextTrackFetch.Result(trackIdOf(key), NextTrackFetch.Outcome.WRITTEN, playing = true))
            }
        }
    }

    override fun isOnDisk(stream: ResolvedStream): Boolean =
        stream.cacheKey?.let { streamCache.isComplete(it) || downloads.isComplete(it) } == true

    override fun cachedState(account: String?, trackId: String): StreamCache.CachedState =
        streamCache.cachedState(StreamCache.keyPrefix(account, trackId))

    override fun linkChanged(up: Boolean) = downloads.linkChanged(up)

    override fun prefetch(trackId: String, stream: ResolvedStream) {
        val key = stream.cacheKey ?: return
        if (stream.localFile) return
        if (writingTrackId == trackId && (job?.isActive == true || downloadKey == key)) return
        cancel()
        if (stream.progressive || downloads.isProgressive(key)) {
            download(trackId, stream)
            return
        }
        if (!streamCache.enabled) return
        streamCache.remember(stream)
        StreamHeaders.register(stream.uri, stream.headers)
        if (streamCache.isComplete(key)) {
            _results.tryEmit(NextTrackFetch.Result(trackId, NextTrackFetch.Outcome.ALREADY_ON_DISK))
            return
        }
        writingTrackId = trackId
        job = scope.launch(io) {
            val spec = DataSpec.Builder()
                .setUri(EncrawDataSourceFactory.networkUri(stream.uri.toUri()))
                .setKey(key)
                .build()
            // A stream that comes with no length is a conversion the probe did not see: downloaded whole instead (D91)
            var noLength = false
            lateinit var w: CacheWriter
            // (the length is told before the first new bytes; the call at the very start comes before any request)
            val progress = CacheWriter.ProgressListener { requestLength, _, newBytes ->
                if (newBytes > 0L && requestLength == C.LENGTH_UNSET.toLong() && !noLength) {
                    noLength = true
                    w.cancel()
                }
            }
            w = CacheWriter(streamCache.writingSource(upstream.createDataSource()), spec, null, progress)
            writer = w
            // A cancelled job ends here without a result: the track changed, nobody waits for this one
            val outcome = try {
                w.cache()
                currentCoroutineContext().ensureActive()
                if (streamCache.isComplete(key)) NextTrackFetch.Outcome.WRITTEN else NextTrackFetch.Outcome.FAILED
            } catch (e: java.io.InterruptedIOException) {
                // CacheWriter.cancel() surfaces as an interrupted read
                android.util.Log.d(TAG, "Write of $trackId cancelled: ${e.message}")
                NextTrackFetch.Outcome.CANCELLED
            } catch (e: java.io.IOException) {
                val cancelled = !currentCoroutineContext().isActive
                android.util.Log.d(TAG, "Write of $trackId ${if (cancelled) "cancelled" else "failed"}: ${e.message}")
                if (cancelled) NextTrackFetch.Outcome.CANCELLED else NextTrackFetch.Outcome.FAILED
            } finally {
                if (writer === w) writer = null
            }
            if (noLength && currentCoroutineContext().isActive && writingTrackId == trackId) {
                runCatching { streamCache.cache.removeResource(key) }
                job = null
                download(trackId, stream.copy(progressive = true))
                return@launch
            }
            if (writingTrackId == trackId) writingTrackId = null
            _results.tryEmit(NextTrackFetch.Result(trackId, outcome))
        }
    }

    /** The next track's converted stream: its download (started, joined, or queued behind the playing one's). */
    private fun download(trackId: String, stream: ResolvedStream) {
        StreamHeaders.register(stream.uri, stream.headers)
        if (stream.cacheKey?.let(streamCache::isComplete) == true) {
            _results.tryEmit(NextTrackFetch.Result(trackId, NextTrackFetch.Outcome.ALREADY_ON_DISK))
            return
        }
        val d = downloads.prefetch(stream) ?: return
        writingTrackId = trackId
        downloadKey = d.key
        d.whenDone { state ->
            if (downloadKey != d.key || writingTrackId != trackId) return@whenDone
            writingTrackId = null
            val outcome = when (state) {
                ProgressiveDownloads.State.COMPLETE -> NextTrackFetch.Outcome.WRITTEN
                ProgressiveDownloads.State.CANCELLED -> NextTrackFetch.Outcome.CANCELLED
                else -> NextTrackFetch.Outcome.FAILED
            }
            _results.tryEmit(NextTrackFetch.Result(trackId, outcome))
        }
    }

    override fun cancel() {
        writer?.cancel()
        job?.cancel()
        job = null
        writingTrackId = null
        if (downloadKey != null) {
            downloadKey = null
            downloads.next(null)
        }
    }

    private companion object {
        const val TAG = "NextTrackCacher"

        /** "account|track|quality" → track. */
        fun trackIdOf(key: String): String = key.split('|').getOrElse(1) { key }
    }
}
