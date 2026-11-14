package org.tegro.yamp.data.repository

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * What a player that is not needed does with the silent clip, instead of being stopped.
 *
 * "Keep audio streams open" ([keepStreams], 2026-11-13): it is parked, paused, on the clip ([park]): ExoPlayer
 * then keeps its AudioTrack for the next track of the same format.
 *
 * "Keep both players running" ([enabled], experimental, 2026-11-14): the player that is not needed
 * keeps playing the silent clip in a loop instead of stopping, so its AudioTrack never closes and a head unit
 * sees no stream come or go. The next track is queued behind the silence ([queueBehind]) and the player moves on
 * to it by itself — a gapless transition keeps the AudioTrack (same format: the clip is 44.1 kHz stereo like the
 * streams), where a seek or a new item would flush and close it. The price: a start waits for the silence to
 * play out, at most its one second. Main thread only, like the engine.
 */
internal class SilentIdler(private val silenceUri: String?) {

    @Volatile
    var keepStreams = false

    @Volatile
    var enabled = false

    /**
     * The player whose silence plays out into the track queued behind it: the engine counts it as playing and
     * loading until the track arrives, so nothing on the screen or in the focus logic sees a gap.
     */
    var handover: ExoPlayer? = null

    /** The track queued on [handover] is a warm-up of the next one: it is held paused when it arrives. */
    private var holdOnArrival = false

    fun isIdling(player: ExoPlayer): Boolean =
        enabled && player.currentMediaItem?.mediaId == PlayerRepositoryImpl.PARKED_ID && player.repeatMode == Player.REPEAT_MODE_ONE

    /** Starts [player] looping the silence (or lets a paused loop go on); false without a clip. */
    fun idle(player: ExoPlayer): Boolean {
        val silence = silenceUri ?: return false
        if (handover === player) {
            handover = null
            holdOnArrival = false
        }
        if (isIdling(player) && player.mediaItemCount == 1) {
            player.playWhenReady = true
            return true
        }
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.setMediaItem(MediaItem.Builder().setMediaId(PlayerRepositoryImpl.PARKED_ID).setUri(silence).build(), 0L)
        player.prepare()
        player.playWhenReady = true
        return true
    }

    /** Parks [player] paused on the silence when "keep audio streams" is on; false when it is not. */
    fun park(player: ExoPlayer): Boolean {
        val silence = silenceUri ?: return false
        if (!keepStreams) return false
        player.playWhenReady = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.setMediaItem(MediaItem.Builder().setMediaId(PlayerRepositoryImpl.PARKED_ID).setUri(silence).build(), 0L)
        player.prepare()
        return true
    }

    /** Before [player] gets a new item: it plays it once, not in a loop, and no handover is pending on it. */
    fun forget(player: ExoPlayer) {
        if (handover === player) {
            handover = null
            holdOnArrival = false
        }
        player.repeatMode = Player.REPEAT_MODE_OFF
    }

    /**
     * The next track warmed up on an idling [player]: queued behind the silence and held paused when it arrives,
     * so the AudioTrack carries over into it (a paused one stays open). False when [player] is not idling.
     */
    fun preload(player: ExoPlayer, item: MediaItem): Boolean {
        if (!isIdling(player)) return false
        queueBehind(player, item)
        holdOnArrival = true
        return true
    }

    /** [trackId] is queued behind [player]'s silence and has not arrived yet. */
    fun isQueued(player: ExoPlayer, trackId: String): Boolean =
        handover === player && player.mediaItemCount > 1 && player.getMediaItemAt(player.mediaItemCount - 1).mediaId == trackId

    /** [item] goes right after the looping silence, which plays out and hands over. */
    fun queueBehind(player: ExoPlayer, item: MediaItem) {
        holdOnArrival = false
        if (isQueued(player, item.mediaId)) return // warmed up already: it goes on playing into the track
        if (player.mediaItemCount > 1) player.removeMediaItems(1, player.mediaItemCount)
        player.addMediaItem(item)
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.playWhenReady = true
        handover = player
    }

    /** The queued track plays on [player] now: the silence before it goes. */
    fun handedOver(player: ExoPlayer) {
        handover = null
        if (player.currentMediaItemIndex > 0) player.removeMediaItems(0, player.currentMediaItemIndex)
        if (holdOnArrival) player.playWhenReady = false
        holdOnArrival = false
    }

    /**
     * Pausing the music pauses an idling [standby] too; playing again starts it (an empty one as well) — unless it
     * is a crossfade's [outgoing] side, which the fade stops itself.
     */
    fun setRunning(standby: ExoPlayer, running: Boolean, outgoing: ExoPlayer?) {
        if (standby === outgoing) return
        val parked = standby.currentMediaItem?.mediaId == PlayerRepositoryImpl.PARKED_ID
        val looping = parked && standby.repeatMode == Player.REPEAT_MODE_ONE
        val empty = standby.mediaItemCount == 0 || parked
        // A loop left from before the setting was switched off is paused as well
        if (!running && looping) standby.playWhenReady = false
        else if (running && enabled && empty) idle(standby)
    }
}
