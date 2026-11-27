package org.tegro.yamp.data.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.tegro.yamp.domain.queue.TrackRef

/**
 * What the controller asks about local files (docs/phase-4d-local-files.md §4, D96): whether a track's volume is
 * mounted now (no disk), the volume's name for the messages, and when volumes come and go.
 */
interface LocalPlayback {
    /** A local track whose volume is mounted; true for any other track. */
    fun isAvailable(track: TrackRef): Boolean

    /** The name of the volume [track] is on ("USB “KINGSTON”", "SD card"); null for a track that is not local. */
    fun volumeName(track: TrackRef): String?

    /** Ticks whenever a volume is mounted or goes away. */
    val changes: Flow<Unit>

    /** No local files (tests of other providers). */
    object None : LocalPlayback {
        override fun isAvailable(track: TrackRef): Boolean = true
        override fun volumeName(track: TrackRef): String? = null
        override val changes: Flow<Unit> = emptyFlow()
    }
}
