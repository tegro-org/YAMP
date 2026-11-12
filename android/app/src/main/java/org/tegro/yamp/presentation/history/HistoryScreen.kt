package org.tegro.yamp.presentation.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tegro.yamp.R
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.presentation.hifi.GlassCircleButton
import org.tegro.yamp.presentation.hifi.HiFiAlertDialog
import org.tegro.yamp.presentation.hifi.HiFiDialogAction
import org.tegro.yamp.presentation.hifi.HiFiDialogActionKind
import org.tegro.yamp.presentation.hifi.HiFiEmptyState
import org.tegro.yamp.presentation.hifi.HiFiFilterPill
import org.tegro.yamp.presentation.hifi.HiFiSectionHeader
import org.tegro.yamp.presentation.hifi.HiFiSkeletonRows
import org.tegro.yamp.presentation.hifi.HiFiType
import org.tegro.yamp.presentation.hifi.HiFiWellButton
import org.tegro.yamp.presentation.main.components.TrackItem
import java.time.format.DateTimeFormatter

/**
 * Library → History (docs/phase-1b-history.md, the canvas `history.jsx`): the queue chips, then the rows by day,
 * newest first, each the usual [TrackItem] (its ⋮ menu comes from `LocalTrackMenu`). A tap plays the track now
 * and keeps the queue; "clear" asks first. Two columns in landscape, as the library's pages.
 */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    currentTrackId: String?,
    isPlaying: Boolean,
    likedTrackIds: Set<String>,
    downloadedTrackIds: Set<String>,
    contentBottom: Dp,
    onBack: () -> Unit,
    onOpened: () -> Unit,
    onSelectFilter: (Long?) -> Unit,
    onTrackClick: (TrackDetailsDto) -> Unit,
    onToggleLike: (TrackDetailsDto) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(Unit) { onOpened() }
    val isLandscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var confirmClear by remember { mutableStateOf(false) }
    val allQueues = state.selectedQueueId == null

    LazyColumn(modifier = modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = contentBottom)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassCircleButton(onClick = onBack, contentDescription = stringResource(R.string.cd_nazad)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(R.string.history_title),
                    style = HiFiType.LargeTitle.copy(fontSize = 28.sp, lineHeight = 34.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (state.days.isNotEmpty()) {
                    HiFiWellButton(onClick = { confirmClear = true }, contentDescription = stringResource(R.string.history_clear)) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Color.White.copy(alpha = 0.86f), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        item(key = "filters") {
            LazyRow(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.filters, key = { it.queueId ?: -1L }) { filter ->
                    HiFiFilterPill(
                        text = filterLabel(filter),
                        selected = filter.queueId == state.selectedQueueId,
                        onClick = { onSelectFilter(filter.queueId) }
                    )
                }
            }
        }
        when {
            state.loading -> item(key = "loading") { HiFiSkeletonRows(Modifier.padding(top = 20.dp), count = 5) }
            state.days.isEmpty() -> item(key = "empty") {
                HiFiEmptyState(icon = Icons.Rounded.History, title = stringResource(R.string.history_empty))
            }
            else -> state.days.forEach { section ->
                item(key = "day_${section.day}") {
                    HiFiSectionHeader(dayLabel(section.day), Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp))
                }
                rows(section, isLandscape) { row ->
                    HistoryTrack(row, currentTrackId, isPlaying, likedTrackIds, downloadedTrackIds, allQueues, onToggleLike, onTrackClick)
                }
            }
        }
    }

    if (confirmClear) {
        HiFiAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = stringResource(R.string.history_clear_confirm),
            content = { Text(stringResource(R.string.history_clear_confirm_text), fontSize = 15.sp, lineHeight = 21.sp) },
            confirm = {
                HiFiDialogAction(stringResource(R.string.history_clear), { onClear(); confirmClear = false }, HiFiDialogActionKind.Danger)
            },
            dismiss = { HiFiDialogAction(stringResource(R.string.queue_cancel), { confirmClear = false }, HiFiDialogActionKind.Quiet) }
        )
    }
}

/** The rows of one day: one per line, or two side by side in landscape. */
private fun LazyListScope.rows(section: DaySection, twoColumns: Boolean, row: @Composable (HistoryRow) -> Unit) {
    if (!twoColumns) {
        items(section.rows, key = { it.id }, contentType = { "track" }) { row(it) }
        return
    }
    items(section.rows.chunked(2), key = { pair -> pair.joinToString("|") { it.id.toString() } }, contentType = { "track_row" }) { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            pair.forEach { item -> Box(Modifier.weight(1f)) { row(item) } }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun HistoryTrack(
    row: HistoryRow,
    currentTrackId: String?,
    isPlaying: Boolean,
    likedTrackIds: Set<String>,
    downloadedTrackIds: Set<String>,
    showQueue: Boolean,
    onToggleLike: (TrackDetailsDto) -> Unit,
    onTrackClick: (TrackDetailsDto) -> Unit
) {
    val track = row.track
    val artist = track.artists.joinToString { it.name.orEmpty() }.ifEmpty { stringResource(R.string.player_unknown_artist) }
    TrackItem(
        track = track,
        isPlaying = isPlaying && track.idString == currentTrackId,
        isLiked = track.idString in likedTrackIds,
        isDownloaded = track.idString in downloadedTrackIds,
        // In "All" every row says where it played
        subtitle = if (showQueue && !row.queueName.isNullOrBlank()) "$artist · ${row.queueName}" else null,
        onToggleLike = { onToggleLike(track) },
        onClick = { onTrackClick(track) }
    )
}

@Composable
private fun filterLabel(filter: HistoryFilter): String = when {
    filter.queueId == null -> stringResource(R.string.history_filter_all)
    filter.kind == QueueKind.WAVE -> stringResource(R.string.history_filter_wave)
    filter.kind == QueueKind.RADIO && filter.name.isNullOrBlank() -> stringResource(R.string.history_filter_radio)
    else -> filter.name.orEmpty()
}

@Composable
private fun dayLabel(day: DayKey): String = when (day) {
    DayKey.Today -> stringResource(R.string.history_today)
    DayKey.Yesterday -> stringResource(R.string.history_yesterday)
    is DayKey.Date -> {
        val locale = LocalConfiguration.current.locales[0]
        day.date.format(DateTimeFormatter.ofPattern(if (day.thisYear) "d MMMM" else "d MMMM yyyy", locale))
    }
}
