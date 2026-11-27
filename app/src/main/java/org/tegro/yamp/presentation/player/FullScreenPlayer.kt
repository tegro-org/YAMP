package org.tegro.yamp.presentation.player

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import org.tegro.yamp.R
import org.tegro.yamp.data.player.PlaylistSource
import org.tegro.yamp.data.player.SleepTimerState
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.queue.service
import org.tegro.yamp.presentation.common.coverUri
import org.tegro.yamp.presentation.common.plusDim
import org.tegro.yamp.presentation.common.id
import org.tegro.yamp.presentation.common.isRadio
import org.tegro.yamp.presentation.common.supportsLikes
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiBackdrop
import org.tegro.yamp.presentation.hifi.HiFiLiveLine
import org.tegro.yamp.presentation.hifi.HiFiMotion
import org.tegro.yamp.presentation.hifi.rememberPredictiveBack
import org.tegro.yamp.utils.CoverSize
import org.tegro.yamp.utils.toCoverUrl
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.HeartBroken

/**
 * The queue around the current track: what played before it (in play order: what is still in the queue — a radio
 * without Keep holds the last 3, D52), what plays now and what follows it.
 */
data class QueueSplit(
    val current: Track?,
    val next: List<Track>,
    val nextEntryIds: List<Long> = emptyList(),
    val previous: List<Track> = emptyList(),
    val previousEntryIds: List<Long> = emptyList()
)

/**
 * Pure; unit-tested. A current track missing from the queue leaves the whole queue as "next". [cursorIndex]
 * names the current row when the queue knows it (a track in the queue twice); [entryIds] run alongside [queue].
 */
fun queueSplitOf(queue: List<Track>, currentId: String, entryIds: List<Long> = emptyList(), cursorIndex: Int = -1): QueueSplit {
    val i = if (cursorIndex in queue.indices && queue[cursorIndex].id == currentId) cursorIndex else queue.indexOfFirst { it.id == currentId }
    return if (i == -1) QueueSplit(null, queue, entryIds)
    else QueueSplit(queue[i], queue.drop(i + 1), entryIds.drop(i + 1), queue.take(i), entryIds.take(i))
}

/** The source name under "СЕЙЧАС ИГРАЕТ"; null where it would be vague (search, catalog pages). */
fun playerSourceLabelRes(source: PlaylistSource, isRadio: Boolean): Int? = when {
    isRadio || source == PlaylistSource.RADIO -> R.string.car_source_radio
    source == PlaylistSource.VIBE -> R.string.car_source_wave
    source == PlaylistSource.TRENDS -> R.string.car_source_hits
    source == PlaylistSource.LIBRARY -> R.string.library_title
    else -> null
}

/** When a pull-down closes the player. Pure; unit-tested. */
object PlayerGesture {
    /** Share of the player's height it must be pulled down by. */
    const val CLOSE_FRACTION = 0.22f
    /** A downward fling this fast (px/s) closes it from anywhere; an upward one keeps it open. */
    const val CLOSE_VELOCITY = 1800f

    fun shouldClose(offsetPx: Float, heightPx: Float, velocity: Float): Boolean =
        velocity > CLOSE_VELOCITY || (heightPx > 0f && offsetPx / heightPx > CLOSE_FRACTION && velocity > -CLOSE_VELOCITY)

    /** +1 when the new track is further on in the queue (or its place is unknown), −1 when it is earlier. */
    fun direction(previousIndex: Int, newIndex: Int): Int = if (previousIndex >= 0 && newIndex in 0 until previousIndex) -1 else 1

    /** The bottom strip a swipe up may start from (canvas `pl-drag`), besides the next track(s). */
    const val QUEUE_EDGE_DP = 56f
    /** How far up a swipe goes to open the queue; or an upward fling this fast (px/s). */
    const val OPEN_QUEUE_DP = 56f
    const val OPEN_QUEUE_VELOCITY = 1200f

    /** A swipe up opens the queue only from the bottom edge or over the next track(s). */
    fun inQueueZone(x: Float, y: Float, heightPx: Float, edgePx: Float, next: androidx.compose.ui.geometry.Rect?): Boolean =
        (heightPx > 0f && y >= heightPx - edgePx) || next?.contains(Offset(x, y)) == true

    fun shouldOpenQueue(upPx: Float, distancePx: Float, velocity: Float): Boolean = upPx >= distancePx || velocity < -OPEN_QUEUE_VELOCITY

    /**
     * Whether a drag step that started in the queue zone moves the queue sheet (it rises with the finger, and sinks
     * back if the finger goes down again) rather than pulling the player down to close it.
     */
    fun sheetTakesDrag(fromQueueZone: Boolean, pulledPx: Float, delta: Float, sheetFraction: Float): Boolean = when {
        !fromQueueZone || pulledPx > 0f -> false
        else -> delta < 0f || sheetFraction > 0f
    }
}

/**
 * The pull-down's offset and its settling. Every end of a drag lands on an anchor: back at the top, or closed.
 *
 * The finger moves the offset synchronously (no coroutine per step: a step queued behind the release used to
 * cancel the spring back and leave the player stuck part-way down), and the spring back runs in the screen's own
 * scope, so only a new drag stops it — the gesture's end, cancel or interruption cannot.
 */
@androidx.compose.runtime.Stable
internal class PlayerPull(private val scope: kotlinx.coroutines.CoroutineScope) {
    var offset by mutableFloatStateOf(0f)
        private set
    /** A drag is between its start and its end (or cancel). */
    var dragging by mutableStateOf(false)
        private set
    private var settling: kotlinx.coroutines.Job? = null

    fun start() {
        settling?.cancel()
        settling = null
        dragging = true
    }

    fun dragBy(delta: Float) {
        offset = (offset + delta).coerceAtLeast(0f)
    }

    /** The drag ended (lifted, flung or cancelled): closes, or springs back to the top. */
    fun end(velocity: Float, heightPx: Float, onClose: () -> Unit) {
        dragging = false
        if (PlayerGesture.shouldClose(offset, heightPx, velocity)) onClose() else settle(velocity)
    }

    /** Back to the top unless a drag holds it or it is already going there. */
    fun settle(velocity: Float = 0f) {
        if (dragging || offset == 0f || settling?.isActive == true) return
        settling = scope.launch {
            androidx.compose.animation.core.animate(offset, 0f, velocity, HiFiMotion.spring()) { v, _ -> offset = v }
        }
    }
}

/** Likes by gestures (Settings → Player): a double tap likes, never unlikes (Instagram-style). Pure; unit-tested. */
object LikeGesture {
    fun doubleTapLikes(isLiked: Boolean): Boolean = !isLiked
}

/**
 * The full-screen player in the Hi-Fi language: glass header, a large cover with a gloss, the shared
 * timeline with elapsed / remaining time, glass like and dislike around three metal knobs, and a glass
 * "Далее" plate that opens the queue.
 *
 * It follows the finger: pulled down, it slides away with the screen behind showing and closes past a
 * fifth of its height or on a fling; the predictive Back gesture shrinks it towards where Back leads.
 * A new track slides in from the side it came from. [progress] is read only where it is drawn.
 */
// coverModifier carries the shared cover transition; it belongs to the cover, not to the whole screen
@Suppress("ModifierParameter")
@Composable
fun FullScreenPlayer(
    track: Track,
    isPlaying: Boolean,
    isLiked: Boolean,
    progress: () -> Long,
    queue: List<Track>,
    onPlayPauseClick: () -> Unit,
    onToggleLike: () -> Unit,
    onNextClick: () -> Unit,
    onPrevClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onTrackSelect: (String) -> Unit,
    onRemoveFromQueue: (String) -> Unit,
    /** A queue row of a known entry removed (the previous rows: the same track may also be ahead). */
    onRemoveEntry: (Long) -> Unit = {},
    onCollapse: () -> Unit,
    onCloseAndClear: () -> Unit,
    /** A start the user asked for is still loading: the play knob shows a ring instead of pause. */
    isLoading: Boolean = false,
    /** Landscape: the controls column on the driver's side (the car's steering-wheel setting), the cover on the other. */
    controlsOnLeft: Boolean = false,
    onDislikeClick: () -> Unit,
    onMoveInQueue: (trackId: String, targetId: String) -> Unit = { _, _ -> },
    onLoadMoreQueue: () -> Unit = {},
    isFetchingMoreQueue: Boolean = false,
    sourceLabel: String = "",
    /** The playing queue's account in the queue sheet's header, with more than one account (D58). */
    queueAccount: org.tegro.yamp.presentation.account.AccountTag? = null,
    sleepTimer: SleepTimerState? = null,
    onSleepTimer: (Int) -> Unit = {},
    onSleepAtEndOfTrack: () -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
    onTrackWave: (() -> Unit)? = null,
    /** D54: the title row's wave button is "Start radio" off My Wave. */
    waveActive: Boolean = true,
    onQualityClick: () -> Unit = {},
    coverModifier: Modifier = Modifier,
    /** Opens with the queue sheet up (screenshots). */
    initialQueueOpen: Boolean = false,
    /** The active queue's entry ids alongside [queue], its cursor and its modes; empty / -1 for a plain list. */
    queueEntryIds: List<Long> = emptyList(),
    queueCursorIndex: Int = -1,
    queueModes: QueueModes = QueueModes(),
    onEntrySelect: (Long) -> Unit = {},
    /** Settings → Player: the Car layout (on a wide screen), three next tracks, likes by gestures. */
    carLayout: Boolean = false,
    nextThree: Boolean = false,
    likeGestures: Boolean = false,
    /** What really plays next (the player's own rule); null — the queue after the current track. */
    upcoming: List<Track>? = null,
    upcomingLoading: Boolean = false,
    onUpcomingClick: ((Track) -> Unit)? = null,
    /** The caps over the title in the wide layouts: "Очередь · 12 из 48". */
    caption: String = sourceLabel,
    queueName: String = "",
    onQueues: () -> Unit = {},
    onSearch: () -> Unit = {},
    showClock: Boolean = true,
    waveTuning: org.tegro.yamp.domain.model.WaveTuning = org.tegro.yamp.domain.model.WaveTuning(),
    trackWaveSeed: Track? = null,
    onWaveTuningChange: (org.tegro.yamp.domain.model.WaveTuning) -> Unit = {},
    onBackToMyWave: () -> Unit = {},
    /** A hold on the cover (likes by gestures): MainScreen asks for Undo before the dislike goes. */
    onDislikeGesture: () -> Unit = onDislikeClick,
    /** Opens with "Tune the wave" up (screenshots). */
    initialTuneOpen: Boolean = false,
    /** The wide layouts' ⋮ → quality: the playing account's quality sheet. */
    onAccountQuality: () -> Unit = onQualityClick,
    /** Opens with the wide layouts' ⋮ menu down (screenshots). */
    initialMoreOpen: Boolean = false,
    /** The car layout's ⋮ → «Музыка» (gap (f)): the car Library of the active queue's account. */
    onMusic: () -> Unit = {},
    /**
     * False while MainScreen is hiding the player (its exit animation keeps it composed); shown again before that
     * ends, a player left pulled down by the closing gesture returns to the top.
     */
    shown: Boolean = true
) {
    // The queue sheet follows the finger (a drag up from the bottom edge or the next tracks, D: pl-drag)
    val sheet = rememberQueueSheetState(initialQueueOpen)
    val scope = rememberCoroutineScope()
    val showQueue by remember(sheet) { androidx.compose.runtime.derivedStateOf { sheet.isVisible } }
    val openQueue: () -> Unit = { sheet.open(scope) }
    val back = rememberPredictiveBack(enabled = !showQueue, onBack = onCollapse)

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val split = remember(queue, track.id, queueEntryIds, queueCursorIndex) { queueSplitOf(queue, track.id, queueEntryIds, queueCursorIndex) }
    val shownUpcoming = upcoming ?: split.next
    val nextTrack = if (track.isRadio) null else shownUpcoming.firstOrNull()
    val pickUpcoming: (Track) -> Unit = onUpcomingClick ?: { onTrackSelect(it.id) }
    // Likes by gestures: a double tap on the cover likes (a heart burst), a hold dislikes; the buttons go
    val gestures = likeGestures && track.supportsLikes && !track.isRadio
    var burst by remember { mutableIntStateOf(0) }
    val liked by rememberUpdatedState(isLiked)
    val like by rememberUpdatedState(onToggleLike)
    val dislikeGesture by rememberUpdatedState(onDislikeGesture)
    val coverGestures = if (!gestures) Modifier else Modifier.pointerInput(track.id) {
        detectTapGestures(
            onDoubleTap = {
                if (LikeGesture.doubleTapLikes(liked)) like()
                burst++
            },
            onLongPress = { dislikeGesture() }
        )
    }
    // The next track(s): a swipe up from there (or from the bottom edge) opens the queue
    var nextBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var fromQueueZone by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val durationMs = (track.durationMs ?: 0L).coerceAtLeast(0L)
    val direction = rememberTrackDirection(track.id, queue.indexOfFirst { it.id == track.id })

    // Pull-down to close, following the finger; the offset is read only in the layer
    val pull = remember(scope) { PlayerPull(scope) }
    // Shown again while MainScreen was hiding it (a reopen during the exit animation): back to the top
    LaunchedEffect(pull, shown) { if (shown) pull.settle() }
    var heightPx by remember { mutableFloatStateOf(0f) }
    val sheetShare = if (isLandscape) QueueSheetHeight.FULL else QueueSheetHeight.PART
    val cornerPx = with(LocalDensity.current) { 28.dp.toPx() }

    // The active queue's account has no Plus (D101): the panel takes the cover's place
    val plusBlock = org.tegro.yamp.presentation.common.LocalPlusBlock.current
    val cover = @Composable { modifier: Modifier ->
        Box(modifier.then(coverGestures), contentAlignment = Alignment.Center) {
            if (plusBlock != null) {
                org.tegro.yamp.presentation.common.PlusPanel(plusBlock, radio = track.isRadio, big = false, onQueues = onQueues)
            } else {
                PlayerCover(track, direction, isPlaying, coverModifier, onPrevClick, onNextClick, Modifier.fillMaxSize())
                LikeBurst(burst)
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                heightPx = it.height.toFloat()
                // The sheet's own height: a drag moves it by the finger's distance over that height
                sheet.heightPx = heightPx * sheetShare
            }
            .graphicsLayer {
                val pulled = pull.offset
                val p = back.value
                translationY = pulled
                val s = 1f - 0.08f * p - 0.05f * (pulled / size.height.coerceAtLeast(1f))
                scaleX = s
                scaleY = s
                if (pulled > 0f || p > 0f) {
                    shape = RoundedCornerShape(cornerPx)
                    clip = true
                }
            }
            .background(HiFi.Ground)
            .draggable(
                orientation = Orientation.Vertical,
                // A drag that runs is never switched off mid-way (the sheet becoming visible under a drag from the
                // queue zone): a disabled draggable would drop the gesture without its end
                enabled = !showQueue || pull.dragging,
                onDragStarted = { start ->
                    fromQueueZone = !track.isRadio && PlayerGesture.inQueueZone(
                        start.x, start.y, heightPx, with(density) { PlayerGesture.QUEUE_EDGE_DP.dp.toPx() }, nextBounds
                    )
                    pull.start()
                },
                state = rememberDraggableState { delta ->
                    // From the queue zone the sheet rises with the finger (and sinks back if it goes down again)
                    if (PlayerGesture.sheetTakesDrag(fromQueueZone, pull.offset, delta, sheet.fraction)) sheet.dragBy(delta)
                    else pull.dragBy(delta)
                },
                // Lifted, flung or cancelled (velocity 0): the sheet or the player settles at an anchor
                onDragStopped = { velocity ->
                    if (fromQueueZone && sheet.fraction > 0f) {
                        sheet.settle(scope, velocity)
                        pull.end(0f, heightPx, onClose = {})
                    } else {
                        pull.end(velocity, heightPx, onCollapse)
                    }
                }
            )
    ) {
        // Living background: the app tint and (Android 12+) the cover itself, blurred; then a vignette
        // around the cover and a fade to the ground under the controls
        HiFiBackdrop(coverUrl = if (track.isRadio) null else track.coverUri.toCoverUrl(CoverSize.Small), bottomFade = false)
        // Portrait only: the wide layouts shade their own wheel side over the whole screen (no band under the bars)
        if (!isLandscape) Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val vignette = Brush.radialGradient(
                        0.3f to Color.Transparent,
                        1f to HiFi.Ground.copy(alpha = 0.80f),
                        center = Offset(size.width / 2f, size.height * 0.3f),
                        radius = maxOf(size.width, size.height) * 0.62f
                    )
                    val fade = Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to HiFi.Ground,
                        startY = size.height * 0.45f,
                        endY = size.height * 0.84f
                    )
                    onDrawBehind {
                        drawRect(vignette)
                        drawRect(fade)
                    }
                }
        )

        if (isLandscape) {
            WidePlayer(
                spec = if (carLayout) WideSpec.Car else WideSpec.Phone,
                track = track,
                isPlaying = isPlaying,
                isLoading = isLoading,
                isLiked = isLiked,
                progress = progress,
                durationMs = durationMs,
                caption = caption,
                upcoming = shownUpcoming,
                upcomingLoading = upcomingLoading,
                nextThree = nextThree,
                controlsOnLeft = controlsOnLeft,
                likeButtons = !likeGestures,
                queueName = queueName,
                queueAccount = queueAccount,
                onQueues = onQueues,
                onSearch = onSearch,
                onPlayPause = onPlayPauseClick,
                onPrev = onPrevClick,
                onNext = onNextClick,
                onLike = onToggleLike,
                onDislike = onDislikeClick,
                onSeek = onSeekTo,
                onUpcomingClick = pickUpcoming,
                onOpenQueue = openQueue,
                onNextBounds = { nextBounds = it },
                coverGestures = coverGestures,
                coverOverlay = { LikeBurst(burst) },
                showClock = showClock,
                waveActive = waveActive,
                waveTuning = waveTuning,
                trackWaveSeed = trackWaveSeed,
                onWaveTuningChange = onWaveTuningChange,
                onTrackWave = onTrackWave,
                onBackToMyWave = onBackToMyWave,
                initialTuneOpen = initialTuneOpen,
                sleepTimer = sleepTimer,
                onSleepTimer = onSleepTimer,
                onSleepAtEndOfTrack = onSleepAtEndOfTrack,
                onCancelSleepTimer = onCancelSleepTimer,
                onAddToPlaylist = onAddToPlaylist,
                onQuality = onAccountQuality,
                onDislikeWithUndo = onDislikeGesture,
                initialMoreOpen = initialMoreOpen,
                onMusic = onMusic
            )
        } else {
            // Portrait (canvas `pl-player-*`, approved 2026-11-18): the top row ⋮ · queue (everything else in ⋮), the
            // cover, the labels centred between dislike and like, the progress with times, Prev · Play · Next and the
            // next track(s) — one plate, or three cards of the landscape kind. Swipe down closes, swipe up — the queue.
            var tuneOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialTuneOpen) }
            if (tuneOpen) androidx.activity.compose.BackHandler { tuneOpen = false }
            val likes = !likeGestures && track.supportsLikes && !track.isRadio
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 560.dp)
                    .align(Alignment.TopCenter)
                    .systemBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayerMoreButton(
                        items = playerMenuItems(
                            likeGestures = likeGestures, supportsLikes = track.supportsLikes, isRadio = track.isRadio,
                            waveActive = waveActive, canStartRadio = onTrackWave != null && track.supportsLikes, trackWave = trackWaveSeed != null,
                            canAddToPlaylist = track.ref.service != org.tegro.yamp.domain.account.Provider.LOCAL
                        ),
                        size = 44.dp, itemHeight = 48.dp, fontSize = 16.sp,
                        isLiked = isLiked, waveTuning = waveTuning, sleepTimer = sleepTimer,
                        onTune = { tuneOpen = true },
                        onStartRadio = { onTrackWave?.invoke() },
                        onBackToMyWave = onBackToMyWave,
                        onSearch = onSearch,
                        onSleepTimer = onSleepTimer,
                        onSleepAtEndOfTrack = onSleepAtEndOfTrack,
                        onCancelSleepTimer = onCancelSleepTimer,
                        onAddToPlaylist = onAddToPlaylist,
                        onQuality = onAccountQuality,
                        onLike = onToggleLike,
                        onDislike = onDislikeGesture,
                        initialOpen = initialMoreOpen
                    )
                    QueueDropdown(queueName, queueAccount, height = 44.dp, fontSize = 17.sp, onClick = onQueues, modifier = Modifier.weight(1f, fill = false))
                }
                SleepTimerCaption(state = sleepTimer)
                cover(Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp, start = 22.dp, end = 22.dp))
                // On a short screen the dimmed controls give their room to the panel: its actions must stay visible
                val shortScreen = with(LocalDensity.current) { org.tegro.yamp.utils.currentWindowSizePx().height.toDp() } < 600.dp
                if (plusBlock == null || !shortScreen) Column(Modifier.plusDim(plusBlock)) {
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (likes) {
                        GlassRound(44.dp, androidx.compose.ui.res.stringResource(R.string.cd_ne_nravitsya), onDislikeClick) {
                            androidx.compose.material3.Icon(
                                Icons.Rounded.HeartBroken, contentDescription = null,
                                tint = Color.White.copy(alpha = 0.88f), modifier = Modifier.size(20.dp)
                            )
                        }
                    } else Spacer(Modifier.size(44.dp))
                    WideLabels(track, caption, 1f, WideSpec.Phone, Modifier.weight(1f).padding(horizontal = 12.dp))
                    if (likes) {
                        GlassRound(44.dp, androidx.compose.ui.res.stringResource(R.string.cd_nravitsya), onToggleLike) {
                            androidx.compose.material3.Icon(
                                if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = null,
                                tint = if (isLiked) HiFi.Love else Color.White.copy(alpha = 0.88f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else Spacer(Modifier.size(44.dp))
                }
                Spacer(Modifier.height(12.dp))
                if (track.isRadio) {
                    HiFiLiveLine(height = 44f)
                    Spacer(Modifier.height(12.dp))
                } else {
                    PlayerTimeline(progress, durationMs, isPlaying, onSeekTo)
                }
                Spacer(Modifier.height(14.dp))
                // The likes sit beside the title now; the three knobs keep a gap that grows with the width
                PlayerControls(
                    isPlaying = isPlaying,
                    isLoading = isLoading,
                    isRadio = track.isRadio,
                    onPrev = onPrevClick,
                    onPlayPause = onPlayPauseClick,
                    onNext = onNextClick
                )
                if (!track.isRadio) {
                    Spacer(Modifier.height(18.dp))
                    // The place is kept while the queue loads, so the controls do not jump; a swipe up from here opens
                    // the queue
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().onGloballyPositioned { nextBounds = it.boundsInRoot() }) {
                        when {
                            nextTrack == null -> Spacer(Modifier.height(64.dp))
                            nextThree -> {
                                val card = (maxWidth - (NEXT_CARD_GAP * 2).dp) / 3
                                NextCards(shownUpcoming.take(3), upcomingLoading, 1f, WideSpec.Phone, card, onClick = pickUpcoming)
                            }
                            else -> NextPlate(nextTrack, onClick = openQueue)
                        }
                    }
                }
                }
            }
            if (tuneOpen) {
                // The same panel as the main screen
                org.tegro.yamp.presentation.main.components.WaveTuneSheet(waveTuning, onChange = onWaveTuningChange, onDismiss = { tuneOpen = false })
            }
        }

        QueueSheet(
            state = sheet,
            split = split,
            isPlaying = isPlaying,
            sourceLabel = sourceLabel,
            account = queueAccount,
            isFetchingMore = isFetchingMoreQueue,
            fullHeight = isLandscape,
            onDismiss = { sheet.close(scope) },
            onTrackSelect = onTrackSelect,
            onRemove = onRemoveFromQueue,
            onRemoveEntry = onRemoveEntry,
            onMove = onMoveInQueue,
            onLoadMore = onLoadMoreQueue,
            modes = queueModes,
            onEntrySelect = onEntrySelect
        )
    }
}

/** Which way the track changed (see [PlayerGesture.direction]); remembered between compositions, not state. */
@Composable
private fun rememberTrackDirection(trackId: String, queueIndex: Int): Int {
    val last = remember { IntArray(1) { queueIndex } }
    return remember(trackId) {
        val d = PlayerGesture.direction(last[0], queueIndex)
        last[0] = queueIndex
        d
    }
}
