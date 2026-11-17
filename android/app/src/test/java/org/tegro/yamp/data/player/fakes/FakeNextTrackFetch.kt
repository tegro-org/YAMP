package org.tegro.yamp.data.player.fakes

import kotlinx.coroutines.flow.MutableSharedFlow
import org.tegro.yamp.data.player.NextTrackFetch
import org.tegro.yamp.domain.provider.ResolvedStream

/** Records what the controller asked to fetch to disk; [finish] reports a write's outcome (D86). */
class FakeNextTrackFetch : NextTrackFetch {
    val prefetched = mutableListOf<String>()
    var cancels = 0
    val onDisk = mutableSetOf<String>()
    override var writingTrackId: String? = null
    override val results = MutableSharedFlow<NextTrackFetch.Result>(extraBufferCapacity = 16)

    override fun prefetch(trackId: String, stream: ResolvedStream) {
        prefetched += trackId
        writingTrackId = trackId
    }

    override fun cancel() {
        cancels++
        writingTrackId = null
    }

    override fun isOnDisk(stream: ResolvedStream): Boolean = stream.cacheKey in onDisk

    fun finish(trackId: String, outcome: NextTrackFetch.Outcome) {
        if (writingTrackId == trackId) writingTrackId = null
        results.tryEmit(NextTrackFetch.Result(trackId, outcome))
    }
}
