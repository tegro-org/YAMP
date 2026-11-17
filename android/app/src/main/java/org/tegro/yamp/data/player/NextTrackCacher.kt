package org.tegro.yamp.data.player

import androidx.core.net.toUri
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

    data class Result(val trackId: String, val outcome: Outcome)

    /** Starts writing [stream] of [trackId] (another track's write is cancelled first). */
    fun prefetch(trackId: String, stream: ResolvedStream)

    /** Stops the running write: the next track changed. What was written so far stays. */
    fun cancel()

    /** Every byte of [stream] is on disk. */
    fun isOnDisk(stream: ResolvedStream): Boolean

    /** What of [trackId] (played under [account]) is on disk already, whatever link it came by. */
    fun cachedState(account: String?, trackId: String): StreamCache.CachedState = StreamCache.CachedState.NONE

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

    private var job: Job? = null

    @Volatile
    private var writer: CacheWriter? = null

    /** The track being written now (its id), or null. */
    @Volatile
    override var writingTrackId: String? = null
        private set

    override fun isOnDisk(stream: ResolvedStream): Boolean = stream.cacheKey?.let { streamCache.isComplete(it) } == true

    override fun cachedState(account: String?, trackId: String): StreamCache.CachedState =
        streamCache.cachedState(StreamCache.keyPrefix(account, trackId))

    override fun prefetch(trackId: String, stream: ResolvedStream) {
        val key = stream.cacheKey ?: return
        if (!streamCache.enabled || stream.localFile) return
        if (writingTrackId == trackId && job?.isActive == true) return
        cancel()
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
            val w = CacheWriter(streamCache.writingSource(upstream.createDataSource()), spec, null, null)
            writer = w
            // A cancelled job ends here without a result: the next track changed, nobody waits for this one
            val outcome = try {
                w.cache()
                currentCoroutineContext().ensureActive()
                NextTrackFetch.Outcome.WRITTEN
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
            if (writingTrackId == trackId) writingTrackId = null
            _results.tryEmit(NextTrackFetch.Result(trackId, outcome))
        }
    }

    private companion object {
        const val TAG = "NextTrackCacher"
    }

    override fun cancel() {
        writer?.cancel()
        job?.cancel()
        job = null
        writingTrackId = null
    }
}
