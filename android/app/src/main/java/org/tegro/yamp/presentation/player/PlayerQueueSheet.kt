package org.tegro.yamp.presentation.player

import org.tegro.yamp.utils.cover
import org.tegro.yamp.utils.CoverSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.tegro.yamp.presentation.hifi.hifiAnodized
import org.tegro.yamp.presentation.hifi.hifiGlass
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import org.tegro.yamp.presentation.hifi.hifiPress
import org.tegro.yamp.presentation.hifi.reorderHandle
import org.tegro.yamp.presentation.hifi.reorderItem
import org.tegro.yamp.presentation.hifi.rememberReorderState
import org.tegro.yamp.presentation.hifi.rememberPredictiveBack
import org.tegro.yamp.presentation.hifi.HiFi
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode as AnimationRepeatMode
import org.tegro.yamp.domain.queue.RepeatMode
import org.tegro.yamp.presentation.queues.LocalQueueSwitcher
import org.tegro.yamp.presentation.queues.QueueSwitcherRequest
import org.tegro.yamp.presentation.hifi.GlassPillButton
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.tegro.yamp.R
import org.tegro.yamp.presentation.account.AccountMark
import org.tegro.yamp.presentation.account.AccountTag
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.presentation.common.artistNames
import org.tegro.yamp.presentation.common.coverUri
import org.tegro.yamp.presentation.common.id
import org.tegro.yamp.presentation.hifi.GlassCircleButton
import org.tegro.yamp.presentation.hifi.HiFiMotion
import org.tegro.yamp.presentation.hifi.LocalHiFiLite
import org.tegro.yamp.presentation.player.components.AudioVisualizer

// --- Queue sheet ------------------------------------------------------------------------------------

/** How a drag of the queue sheet ends: open or closed, by the fling's speed or by how far up it is. Pure; unit-tested. */
object QueueDrag {
    /** A fling this fast (px/s) decides on its own; slower, the sheet goes where it is nearer. */
    const val FLING_VELOCITY = 1200f
    const val HALF = 0.5f

    /** [velocity] is the finger's vertical speed, positive downwards (Compose's convention). */
    fun settlesOpen(fraction: Float, velocity: Float): Boolean = when {
        velocity < -FLING_VELOCITY -> true
        velocity > FLING_VELOCITY -> false
        else -> fraction >= HALF
    }

    /** The new open fraction after the finger moved [deltaPx] (positive = down) over a sheet [heightPx] tall. */
    fun dragged(fraction: Float, deltaPx: Float, heightPx: Float): Float =
        if (heightPx <= 0f) fraction else (fraction - deltaPx / heightPx).coerceIn(0f, 1f)
}

/**
 * Where the queue sheet is: [fraction] 0 = hidden, 1 = fully up. A drag moves it with the finger (from the player's
 * bottom edge or next tracks, from the sheet's header, or from its list once scrolled to the top); a release
 * settles it ([QueueDrag.settlesOpen]). [heightPx] is the sheet's full height, set by the screen that holds it.
 */
@androidx.compose.runtime.Stable
class QueueSheetState(initiallyOpen: Boolean) {
    var fraction by mutableFloatStateOf(if (initiallyOpen) 1f else 0f)
        private set
    var heightPx by mutableFloatStateOf(0f)
    private var settling: kotlinx.coroutines.Job? = null

    val isVisible: Boolean get() = fraction > 0f

    fun dragBy(deltaPx: Float) {
        settling?.cancel()
        fraction = QueueDrag.dragged(fraction, deltaPx, heightPx)
    }

    fun settle(scope: kotlinx.coroutines.CoroutineScope, velocity: Float) = animateTo(scope, if (QueueDrag.settlesOpen(fraction, velocity)) 1f else 0f)
    fun open(scope: kotlinx.coroutines.CoroutineScope) = animateTo(scope, 1f)
    fun close(scope: kotlinx.coroutines.CoroutineScope) = animateTo(scope, 0f)

    private fun animateTo(scope: kotlinx.coroutines.CoroutineScope, target: Float) {
        settling?.cancel()
        settling = scope.launch {
            androidx.compose.animation.core.animate(fraction, target, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                fraction = value
            }
        }
    }
}

/** Saved as open / closed only: a recreated screen (back from the background, process death) keeps the queue up. */
val QueueSheetStateSaver: androidx.compose.runtime.saveable.Saver<QueueSheetState, Boolean> =
    androidx.compose.runtime.saveable.Saver(save = { it.fraction >= 0.5f }, restore = { QueueSheetState(it) })

@Composable
fun rememberQueueSheetState(initiallyOpen: Boolean = false): QueueSheetState =
    androidx.compose.runtime.saveable.rememberSaveable(saver = QueueSheetStateSaver) { QueueSheetState(initiallyOpen) }

/** The queue as a dark sheet from the bottom: "Сейчас играет" on anodized metal, then what follows. */
@Composable
internal fun QueueSheet(
    state: QueueSheetState,
    split: QueueSplit,
    isPlaying: Boolean,
    sourceLabel: String,
    isFetchingMore: Boolean,
    fullHeight: Boolean,
    onDismiss: () -> Unit,
    onTrackSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    modes: QueueModes = QueueModes(),
    onEntrySelect: (Long) -> Unit = {},
    account: AccountTag? = null
) {
    // Back closes the sheet first; the predictive gesture lowers it under the finger
    val visible by remember(state) { androidx.compose.runtime.derivedStateOf { state.isVisible } }
    val back = rememberPredictiveBack(enabled = visible, onBack = onDismiss)
    if (!visible) return
    // The scrim darkens with how far up the sheet is
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = state.fraction }
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        QueueSheetContent(
            state = state,
            split = split,
            isPlaying = isPlaying,
            sourceLabel = sourceLabel,
            account = account,
            isFetchingMore = isFetchingMore,
            onDismiss = onDismiss,
            onTrackSelect = onTrackSelect,
            onRemove = onRemove,
            onMove = onMove,
            onLoadMore = onLoadMore,
            modes = modes,
            onEntrySelect = onEntrySelect,
            modifier = Modifier
                // Follows the finger: hidden below the screen at 0, fully up at 1
                .graphicsLayer { translationY = (1f - state.fraction) * size.height + back.value * 160.dp.toPx() }
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .fillMaxHeight(if (fullHeight) QueueSheetHeight.FULL else QueueSheetHeight.PART)
        )
    }
}

/** The sheet's height as a share of the screen: nearly all of it on a wide screen, most of it in portrait. */
internal object QueueSheetHeight {
    const val FULL = 0.94f
    const val PART = 0.78f
}

@Composable
private fun QueueSheetContent(
    state: QueueSheetState,
    split: QueueSplit,
    isPlaying: Boolean,
    sourceLabel: String,
    isFetchingMore: Boolean,
    onDismiss: () -> Unit,
    onTrackSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    modes: QueueModes,
    onEntrySelect: (Long) -> Unit,
    modifier: Modifier,
    account: AccountTag? = null
) {
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(listState)
    // Rows are keyed by their queue entry, so a track that is in the queue twice is two rows (E2); lists that
    // come without entries (the screenshots) fall back to the track id
    val rowKeys = remember(split) { split.next.mapIndexed { i, t -> split.nextEntryIds.getOrNull(i)?.let { "e$it" } ?: t.id } }
    val nextIds = remember(rowKeys) { rowKeys.toHashSet() }
    val trackIdOf = remember(split, rowKeys) { rowKeys.zip(split.next.map { it.id }).toMap() }
    val fetching by rememberUpdatedState(isFetchingMore)
    val loadMore by rememberUpdatedState(onLoadMore)
    // Prefetch as soon as the list is within 12 rows of its end
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (total > 0 && lastVisible >= total - 12 && !fetching) loadMore()
        }
    }

    // The sheet follows the finger: pulled by its header, or by its list once that is scrolled to the top
    val scope = rememberCoroutineScope()
    val listDrag = remember(state, scope) { QueueListDrag(state, scope) }
    val total = split.next.size + if (split.current != null) 1 else 0
    val subtitle = listOfNotNull(
        sourceLabel.takeIf { it.isNotEmpty() },
        pluralStringResource(R.plurals.player_queue_tracks, total, total),
        account?.name
    ).joinToString(" · ")

    Column(
        modifier
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(HiFi.SheetFill)
            .drawBehind { drawLine(Color.White.copy(alpha = 0.14f), Offset(28.dp.toPx(), 0.5f), Offset(size.width - 28.dp.toPx(), 0.5f), 1f) }
            // Swallow taps so they do not reach the scrim behind the sheet
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(
            Modifier.draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> state.dragBy(delta) },
                onDragStopped = { velocity -> state.settle(scope, velocity) }
            )
        ) {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .size(width = 40.dp, height = 5.dp)
                    .align(Alignment.CenterHorizontally)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.28f))
            )
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val title: @Composable () -> Unit = {
                        Text(
                            stringResource(R.string.player_queue_title),
                            color = Color.White,
                            fontSize = 26.sp,
                            lineHeight = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.02).em
                        )
                    }
                    // The playing queue's account, with more than one account (D58)
                    if (account == null) {
                        title()
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AccountMark(account, 26.dp)
                            title()
                        }
                    }
                    Text(subtitle, color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val queues = LocalQueueSwitcher.current
                GlassPillButton(
                    text = stringResource(R.string.queue_switcher_title),
                    onClick = {
                        onDismiss()
                        queues?.open(QueueSwitcherRequest.Switch())
                    },
                    icon = Icons.Rounded.Layers,
                    height = 44.dp
                )
                Spacer(Modifier.width(10.dp))
                GlassCircleButton(onClick = onDismiss, contentDescription = stringResource(R.string.cd_zakryt), size = 44.dp) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).nestedScroll(listDrag),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
            )
        ) {
            split.current?.let { now ->
                item(key = "now_caption") { QueueCaption(stringResource(R.string.full_player_seychas_igraet), top = 12.dp) }
                item(key = "now_row") { NowPlayingRow(now, isPlaying) }
            }
            if (split.next.isNotEmpty()) {
                item(key = "next_caption") {
                    QueueCaption(stringResource(R.string.full_player_dalee), top = 18.dp) {
                        if (modes.shown) QueueModeToggles(modes)
                    }
                }
            }
            itemsIndexed(split.next, key = { i, _ -> rowKeys[i] }) { index, item ->
                val id = item.id
                val key = rowKeys[index]
                val entryId = split.nextEntryIds.getOrNull(index)
                val dragged = reorder.draggedKey == key
                QueueRow(
                    item = item,
                    modifier = Modifier
                        // Neighbours glide into their new places; the dragged row follows the finger instead
                        .animateItem(placementSpec = if (dragged) null else HiFiMotion.spring())
                        .reorderItem(reorder, key)
                        .reorderHandle(reorder, key, canMoveTo = { it in nextIds }, afterLongPress = true) { from, to ->
                            onMove(trackIdOf.getValue(from as String), trackIdOf.getValue(to as String))
                        },
                    moveUp = split.next.getOrNull(index - 1)?.let { prev -> { onMove(id, prev.id) } },
                    moveDown = split.next.getOrNull(index + 1)?.let { nxt -> { onMove(id, nxt.id) } },
                    onClick = {
                        if (entryId != null) onEntrySelect(entryId) else onTrackSelect(id)
                        onDismiss()
                    },
                    onRemove = { onRemove(id) }
                )
            }
            if (isFetchingMore) {
                items(2, key = { "skeleton_$it" }) { QueueSkeletonRow() }
            }
        }
    }
}

/**
 * The list's share of the sheet drag: while the sheet is not fully up, an upward drag lifts it before the list
 * scrolls; a downward drag the list cannot use (it is at the top) pulls the sheet down. A fling on a partly open
 * sheet settles it instead of scrolling the list.
 */
private class QueueListDrag(
    private val state: QueueSheetState,
    private val scope: kotlinx.coroutines.CoroutineScope
) : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
        if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return Offset.Zero
        if (available.y < 0f && state.fraction < 1f) {
            state.dragBy(available.y)
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: androidx.compose.ui.input.nestedscroll.NestedScrollSource
    ): Offset {
        if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        state.dragBy(available.y)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
        if (state.fraction >= 1f) return androidx.compose.ui.unit.Velocity.Zero
        state.settle(scope, available.y)
        return available
    }
}

/** The active queue's modes for the toggles next to "Далее" (docs/phase-1-queues.md: per-queue shuffle and repeat). */
@androidx.compose.runtime.Immutable
data class QueueModes(
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.ALL,
    /** False while the list is not a queue (a station on air, a list without entries): no toggles. */
    val shown: Boolean = false,
    val onShuffle: (Boolean) -> Unit = {},
    val onRepeat: (RepeatMode) -> Unit = {}
)

@Composable
private fun QueueCaption(text: String, top: Dp, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = top, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text.uppercase(),
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.10.em,
            modifier = Modifier.weight(1f)
        )
        trailing()
    }
}

/** Shuffle on/off and repeat Off → All → One: lit when on, as the canvas `PhonePlayerQueue` shows them. */
@Composable
private fun QueueModeToggles(modes: QueueModes) {
    ModeToggle(
        icon = Icons.Rounded.Shuffle,
        on = modes.shuffle,
        description = stringResource(R.string.cd_queue_shuffle),
        onClick = { modes.onShuffle(!modes.shuffle) }
    )
    ModeToggle(
        icon = if (modes.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
        on = modes.repeat != RepeatMode.OFF,
        description = stringResource(R.string.cd_queue_repeat),
        onClick = {
            modes.onRepeat(
                when (modes.repeat) {
                    RepeatMode.OFF -> RepeatMode.ALL
                    RepeatMode.ALL -> RepeatMode.ONE
                    RepeatMode.ONE -> RepeatMode.OFF
                }
            )
        }
    )
}

@Composable
private fun ModeToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(if (on) Modifier.background(HiFi.GlassFill) else Modifier)
            .clickable(role = Role.Switch, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (on) Color.White else HiFi.TextTertiary, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun QueueCover(track: Track) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).cover(track.coverUri, CoverSize.Small).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
    )
}

@Composable
private fun QueueTexts(track: Track, bold: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(
            track.title.ifEmpty { stringResource(R.string.bez_nazvaniya) },
            color = Color.White,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            track.artistNames,
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NowPlayingRow(track: Track, isPlaying: Boolean) {
    Row(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .height(66.dp)
            .hifiAnodized(RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        QueueCover(track)
        QueueTexts(track, bold = true, modifier = Modifier.weight(1f))
        AudioVisualizer(isPlaying = isPlaying, isCompact = true, modifier = Modifier.padding(end = 8.dp))
    }
}

@Composable
private fun QueueRow(
    item: Track,
    modifier: Modifier,
    moveUp: (() -> Unit)?,
    moveDown: (() -> Unit)?,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    // Swipe left to remove: a red "Убрать" plate shows under the row
    val swipe = rememberSwipeToDismissBoxState()
    val remove by rememberUpdatedState(onRemove)
    LaunchedEffect(swipe.currentValue) {
        if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart) remove()
    }
    val upLabel = stringResource(R.string.action_move_up)
    val downLabel = stringResource(R.string.action_move_down)
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        modifier = modifier.padding(start = 12.dp, end = 4.dp),
        backgroundContent = {
            Row(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(HiFi.Danger.copy(alpha = 0.22f))
                    .padding(end = 20.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.queue_remove), color = HiFi.Danger, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    ) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(HiFi.SheetFill)
            .hifiPress(onClick, role = null)
            .semantics {
                customActions = listOfNotNull(
                    moveUp?.let { CustomAccessibilityAction(upLabel) { it(); true } },
                    moveDown?.let { CustomAccessibilityAction(downLabel) { it(); true } }
                )
            }
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        QueueCover(item)
        QueueTexts(item, bold = false, modifier = Modifier.weight(1f))
        val removeDescription = stringResource(R.string.cd_udalit_iz_ocheredi)
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = removeDescription },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(30.dp).hifiGlass(CircleShape, fill = Color.White.copy(alpha = 0.06f), border = Color.White.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
            }
        }
    }
    }
}

/** A row-shaped placeholder while more of the queue loads; the shimmer is read in the draw phase. */
@Composable
private fun QueueSkeletonRow() {
    val lite = LocalHiFiLite.current
    val pulse: State<Float>? = if (!lite) {
        rememberInfiniteTransition(label = "queue_skeleton").animateFloat(
            initialValue = 0.06f,
            targetValue = 0.14f,
            animationSpec = infiniteRepeatable(tween(700), AnimationRepeatMode.Reverse),
            label = "queue_skeleton_alpha"
        )
    } else null
    val loading = stringResource(R.string.player_queue_loading)
    Row(
        Modifier
            .padding(start = 22.dp, end = 16.dp)
            .fillMaxWidth()
            .height(62.dp)
            .semantics { contentDescription = loading },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val block = Modifier.drawBehind {
            drawRoundRect(Color.White.copy(alpha = pulse?.value ?: 0.08f), cornerRadius = CornerRadius(size.height / 2f))
        }
        Box(
            Modifier.size(46.dp).drawBehind {
                drawRoundRect(Color.White.copy(alpha = pulse?.value ?: 0.08f), cornerRadius = CornerRadius(10.dp.toPx()))
            }
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(width = 150.dp, height = 12.dp).then(block))
            Box(Modifier.size(width = 90.dp, height = 10.dp).then(block))
        }
    }
}
