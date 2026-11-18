package org.tegro.yamp.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.presentation.account.AccountTag
import org.tegro.yamp.presentation.car.LocalCarClock
import org.tegro.yamp.presentation.common.isRadio
import org.tegro.yamp.presentation.player.FullScreenPlayer

/** The pinned clock of the car layout's screenshots: Wed 21 Oct, 10:30 Moscow. */
internal const val CAR_FIXED_TIME = 1_792_567_800_000L

/**
 * The player in its Car layout (canvas `plc-*`), as the retired car screen's screenshots now show it: the
 * Settings → Player choice is "Car" with three next tracks.
 */
@Composable
internal fun CarPlayerForTest(
    track: Track,
    queue: List<Track>,
    upcoming: List<Track> = queue.drop(1),
    steeringWheelOnLeft: Boolean = true,
    waveActive: Boolean = true,
    account: AccountTag? = null,
    queueName: String = "With family",
    nextThree: Boolean = true,
    likeGestures: Boolean = false,
    initialQueueOpen: Boolean = false,
    caption: String = "Очередь · 1 из 4",
    upcomingLoading: Boolean = false,
    showClock: Boolean = true,
    waveTuning: org.tegro.yamp.domain.model.WaveTuning = org.tegro.yamp.domain.model.WaveTuning(),
    trackWaveSeed: Track? = null,
    initialTuneOpen: Boolean = false,
    initialMoreOpen: Boolean = false
) = CompositionLocalProvider(LocalCarClock provides { CAR_FIXED_TIME }) {
    FullScreenPlayer(
        track = track, isPlaying = true, isLiked = true, progress = { 82_000L }, queue = queue,
        onPlayPauseClick = {}, onToggleLike = {}, onNextClick = {}, onPrevClick = {}, onSeekTo = {},
        onTrackSelect = {}, onRemoveFromQueue = {}, onCollapse = {}, onCloseAndClear = {}, onDislikeClick = {},
        controlsOnLeft = steeringWheelOnLeft, queueAccount = account, waveActive = waveActive,
        initialQueueOpen = initialQueueOpen,
        carLayout = true, nextThree = nextThree, likeGestures = likeGestures, upcoming = upcoming,
        caption = if (track.isRadio) "" else caption, queueName = queueName,
        upcomingLoading = upcomingLoading, showClock = showClock, waveTuning = waveTuning, trackWaveSeed = trackWaveSeed,
        initialTuneOpen = initialTuneOpen, initialMoreOpen = initialMoreOpen
    )
}
