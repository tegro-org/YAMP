package org.tegro.yamp.data.player.fakes

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * An ExoPlayer the test drives by hand: it holds media items, a play-when-ready flag, a volume and an audio session,
 * and reaches READY only when the test calls [becomeReady] (a pick that loads slowly stays BUFFERING). Listeners hear
 * the same callbacks a real player sends, so the engine (PlayerRepositoryImpl) reacts as on a device.
 */
@UnstableApi
class ScriptedExoPlayer(sessionId: Int) : NoErrorExoPlayer() {
    private val listeners = mutableListOf<Player.Listener>()
    private var items: List<MediaItem> = emptyList()
    private var index = 0
    private var state = Player.STATE_IDLE
    private var wantsToPlay = false
    private var vol = 1f
    private var repeat = Player.REPEAT_MODE_OFF
    private var position = 0L

    var session = sessionId
        private set

    /** Every [setAudioSessionId] call: the engine must never move a player to another session (D70). */
    val sessionChanges = mutableListOf<Int>()

    /** How many times the player was stopped: a stop of the player that sounds is the bug F2 is about. */
    var stops = 0
        private set

    /**
     * AudioTracks this player opened, as Media3 1.11 does it: a new item set, a stop or a seek flushes the sink, which
     * releases the AudioTrack (`DefaultAudioSink.flush`); the next sound opens a new one. A gapless move to the next
     * item of the playlist keeps it (F4).
     */
    var tracksOpened = 0
        private set
    private var trackOpen = false

    val itemId: String? get() = items.getOrNull(index)?.mediaId

    /** The ids in the playlist, in order. */
    val playlist: List<String> get() = items.map { it.mediaId }

    fun becomeReady() = setState(Player.STATE_READY)

    /** The stream broke: the listeners hear the error (the player's own error state stays unset). */
    fun failStream() {
        val error = androidx.media3.common.PlaybackException(
            "dead link", null, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )
        listeners.toList().forEach { it.onPlayerError(error) }
    }

    /** The item has been played up to [ms] (a kept player still holding a track where it was left). */
    fun playedTo(ms: Long) { position = ms }

    /**
     * The current item played to its end: with an item after it the player moves on by itself (gapless, same
     * AudioTrack), otherwise it ends.
     */
    fun finishItem() {
        if (index + 1 < items.size) {
            index++
            position = 0L
            listeners.toList().forEach { it.onMediaItemTransition(items[index], Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
        } else {
            setState(Player.STATE_ENDED)
        }
    }

    private fun flush() { trackOpen = false }

    private fun setState(newState: Int) {
        val wasPlaying = isPlaying
        state = newState
        listeners.toList().forEach { it.onPlaybackStateChanged(newState) }
        if (wasPlaying != isPlaying) listeners.toList().forEach { it.onIsPlayingChanged(isPlaying) }
        openIfSounding()
    }

    /** The sink opens its AudioTrack with the first buffer it writes: once the player is ready with an item. */
    private fun openIfSounding() {
        if (!trackOpen && state == Player.STATE_READY && items.isNotEmpty()) {
            trackOpen = true
            tracksOpened++
        }
    }

    override fun addListener(listener: Player.Listener) { listeners += listener }
    override fun removeListener(listener: Player.Listener) { listeners -= listener }
    override fun addAnalyticsListener(listener: AnalyticsListener) = Unit

    override fun getPlayWhenReady(): Boolean = wantsToPlay
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (wantsToPlay == playWhenReady) return
        val wasPlaying = isPlaying
        wantsToPlay = playWhenReady
        listeners.toList().forEach { it.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        if (wasPlaying != isPlaying) listeners.toList().forEach { it.onIsPlayingChanged(isPlaying) }
    }

    override fun getPlaybackState(): Int = state
    override fun getPlaybackSuppressionReason(): Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) = setMediaItems(mediaItems, 0, 0L)
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        flush()
        items = mediaItems.toList()
        index = 0
        position = startPositionMs.coerceAtLeast(0L)
        loaded = 0L
        listeners.toList().forEach { it.onMediaItemTransition(items.firstOrNull(), Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
        if (state != Player.STATE_IDLE) setState(Player.STATE_BUFFERING)
    }

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        val at = index.coerceIn(0, items.size)
        items = items.take(at) + mediaItems + items.drop(at)
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        val to = toIndex.coerceAtMost(items.size)
        if (fromIndex >= to) return
        val removesCurrent = index in fromIndex until to
        items = items.take(fromIndex) + items.drop(to)
        when {
            removesCurrent -> {
                flush()
                index = fromIndex.coerceAtMost((items.size - 1).coerceAtLeast(0))
                if (items.isEmpty() && state != Player.STATE_IDLE) setState(Player.STATE_ENDED)
            }
            index >= to -> index -= to - fromIndex // earlier items went: the current one keeps playing
        }
    }

    override fun prepare() { if (items.isNotEmpty()) setState(Player.STATE_BUFFERING) }
    override fun stop() {
        stops++
        flush()
        setState(Player.STATE_IDLE)
    }
    override fun release() = Unit

    override fun getCurrentTimeline(): Timeline = ItemsTimeline(items)
    override fun getCurrentMediaItemIndex(): Int = index
    override fun getCurrentPeriodIndex(): Int = index
    override fun seekTo(mediaItemIndex: Int, positionMs: Long, seekCommand: Int, isRepeatingCurrentItem: Boolean) {
        flush()
        position = positionMs
        seeks += positionMs
    }

    override fun getDuration(): Long = if (items.isEmpty()) C.TIME_UNSET else TRACK_MS
    override fun getCurrentPosition(): Long = position
    /** How far the item is loaded; a test raises it to show a slow load that still brings data. */
    var loaded = 0L
    override fun getBufferedPosition(): Long = maxOf(position, loaded)
    override fun getTotalBufferedDuration(): Long = 0L
    override fun isLoading(): Boolean = state == Player.STATE_BUFFERING

    override fun getVolume(): Float = vol
    /** Every volume the engine set, in order (smooth pause / play / seek ramps). */
    val volumes = mutableListOf<Float>()
    override fun setVolume(volume: Float) {
        vol = volume
        volumes += volume
    }
    /** Every position the engine sought to. */
    val seeks = mutableListOf<Long>()

    override fun getAudioSessionId(): Int = session
    override fun setAudioSessionId(audioSessionId: Int) {
        sessionChanges += audioSessionId
        session = audioSessionId
    }

    override fun getRepeatMode(): Int = repeat
    override fun setRepeatMode(repeatMode: Int) { repeat = repeatMode }

    override fun getMediaMetadata(): MediaMetadata = items.getOrNull(index)?.mediaMetadata ?: MediaMetadata.EMPTY

    /** One window and one period per media item; just enough for currentMediaItem and mediaItemCount. */
    private class ItemsTimeline(private val items: List<MediaItem>) : Timeline() {
        override fun getWindowCount() = items.size
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(
                windowIndex, items[windowIndex], null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET,
                true, false, null, 0L, TRACK_MS * 1_000L, windowIndex, windowIndex, 0L
            )
        override fun getPeriodCount() = items.size
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            period.set(periodIndex, periodIndex, periodIndex, TRACK_MS * 1_000L, 0L)
        override fun getIndexOfPeriod(uid: Any): Int = (uid as? Int)?.takeIf { it in items.indices } ?: C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
    }

    companion object {
        const val TRACK_MS = 200_000L
    }
}
