package org.tegro.yamp.data.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import org.tegro.yamp.data.queue.ActiveQueue

/**
 * The active queue as the media session shows it — Android Auto's «Очередь», the platform session's queue
 * (`MediaSessionCompat.setQueue`, through Media3's legacy stub): a window of [BEFORE] tracks before the cursor and
 * [AFTER] after it, in play order. The engine's players hold only the current track (and the next one), so the queue
 * comes from YAMP's model, not from them; a pick in it goes back to the controller by [Entry.entryId].
 *
 * The items carry the title, the artist and a cover the car can load ([BrowseArtwork]); their ids are the queue
 * entries', never a track's path, link or token.
 */
internal data class SessionQueue(val entries: List<Entry>, val currentIndex: Int) {

    data class Entry(val entryId: Long, val trackId: String, val item: MediaItem, val durationMs: Long?)

    fun entryAt(index: Int): Entry? = entries.getOrNull(index)

    companion object {
        const val BEFORE = 20
        const val AFTER = 50
        private const val ART_PX = 400

        /** The session's queue for [queue], or null while there is none to show (no cursor, a station on air). */
        fun of(queue: ActiveQueue?, onAir: Boolean): SessionQueue? {
            if (queue == null || onAir || queue.cursorIndex !in queue.tracks.indices) return null
            val from = (queue.cursorIndex - BEFORE).coerceAtLeast(0)
            val to = (queue.cursorIndex + AFTER + 1).coerceAtMost(minOf(queue.tracks.size, queue.entries.size))
            val entries = (from until to).map { index ->
                val track = queue.tracks[index]
                val entryId = queue.entries[index].entryId
                val item = MediaItem.Builder()
                    .setMediaId(QUEUE_PREFIX + entryId)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artists.joinToString(", ") { it.name }.ifBlank { null })
                            .setArtworkUri(BrowseArtwork.uriFor(track.cover?.url(ART_PX)))
                            .setDurationMs(track.durationMs?.takeIf { it > 0L })
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                            .build()
                    )
                    .build()
                Entry(entryId, track.idString, item, track.durationMs?.takeIf { it > 0L })
            }
            return SessionQueue(entries, queue.cursorIndex - from)
        }

        private const val QUEUE_PREFIX = "queue/"
    }
}

/** What the current window says beyond the queue entry: the engine's own item and what the player knows of it. */
internal data class CurrentWindow(
    val item: MediaItem,
    val durationMs: Long,
    val seekable: Boolean,
    val dynamic: Boolean,
    val live: MediaItem.LiveConfiguration?
)

/** [SessionQueue] as a Media3 timeline: one window (and one period) per entry; [current] fills the cursor's window. */
@UnstableApi
internal class SessionQueueTimeline(private val queue: SessionQueue, private val current: CurrentWindow?) : Timeline() {

    override fun getWindowCount(): Int = queue.entries.size

    override fun getPeriodCount(): Int = queue.entries.size

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        val entry = queue.entries[windowIndex]
        val isCurrent = windowIndex == queue.currentIndex && current != null
        return window.set(
            uidOf(windowIndex),
            if (isCurrent) current.item else entry.item,
            null,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            isCurrent && current.seekable,
            isCurrent && current.dynamic,
            if (isCurrent) current.live else null,
            0L,
            durationUs(windowIndex),
            windowIndex,
            windowIndex,
            0L
        )
    }

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        val uid = uidOf(periodIndex)
        return period.set(if (setIds) uid else null, if (setIds) uid else null, periodIndex, durationUs(periodIndex), 0L)
    }

    override fun getIndexOfPeriod(uid: Any): Int =
        queue.entries.indices.firstOrNull { uidOf(it) == uid } ?: C.INDEX_UNSET

    override fun getUidOfPeriod(periodIndex: Int): Any = uidOf(periodIndex)

    private fun uidOf(index: Int): String = "q" + queue.entries[index].entryId

    private fun durationUs(index: Int): Long {
        val ms = if (index == queue.currentIndex && current != null) current.durationMs else queue.entries[index].durationMs ?: C.TIME_UNSET
        return if (ms == C.TIME_UNSET) C.TIME_UNSET else Util.msToUs(ms)
    }
}
