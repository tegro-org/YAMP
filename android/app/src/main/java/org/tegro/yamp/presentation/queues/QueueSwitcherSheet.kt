package org.tegro.yamp.presentation.queues

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.tegro.yamp.R
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.presentation.car.carScaleOf
import org.tegro.yamp.presentation.hifi.GlassCircleButton
import org.tegro.yamp.presentation.hifi.GlassPillButton
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiProgressBar
import org.tegro.yamp.presentation.hifi.HiFiSheetPanel
import org.tegro.yamp.presentation.hifi.HiFiSwitch
import org.tegro.yamp.presentation.hifi.PanelSide
import org.tegro.yamp.presentation.hifi.hifiAnodized
import org.tegro.yamp.presentation.hifi.hifiPress
import org.tegro.yamp.utils.CoverSize
import org.tegro.yamp.utils.cover

/** The canvas numbers (Design/yamp/queues.jsx `QueueRow`, `QueueSheetHeader`) for a phone row or a big car row. */
private class RowDims(val big: Boolean, private val factor: Float) {
    fun dp(phone: Float, car: Float): Dp = ((if (big) car else phone) * factor).dp
    fun sp(phone: Float, car: Float): TextUnit = ((if (big) car else phone) * factor).sp
}

/**
 * The queue switcher and the "Add to queue…" picker: a sheet on a phone, a panel on a side (DESIGN_GUIDE §8).
 * In the car ([car]) the panel is the canvas `CarQueuePanel`: 720 wide on the 1280×720 grid, big rows, "+"
 * in the header and no long-press ([onLongPress] is ignored — D32, big targets only).
 */
@Composable
internal fun QueueSwitcherSheet(
    state: QueueSwitcherUiState,
    car: Boolean,
    side: PanelSide,
    onDismiss: () -> Unit,
    onSelect: (QueueRowState) -> Unit,
    onLongPress: ((QueueRowState) -> Unit)?,
    onNew: () -> Unit,
    keepHistory: Boolean = false,
    onKeepHistory: (Boolean) -> Unit = {}
) {
    val window = org.tegro.yamp.utils.currentWindowSizeDp()
    val carScale = if (car) carScaleOf(window.widthDp.dp, window.heightDp.dp, window.heightDp.dp, isLandscape = true) else 1f
    val adding = state.mode as? QueueSwitcherRequest.AddTo
    val radio = state.mode as? QueueSwitcherRequest.StartRadio
    val rows = when {
        adding != null -> state.queues.filter { it.editable }.map { it.copy(isActive = false) }
        // Only radios are re-seeded; the user's queues are never a target (D51)
        radio != null -> state.queues.filter { it.kind == QueueKind.RADIO }
        else -> state.queues
    }
    val newLabel = stringResource(if (radio != null) R.string.radio_new else R.string.queue_new)
    HiFiSheetPanel(onDismiss = onDismiss, side = side, sheetHeight = 0.78f, panelWidth = if (car) (720f * carScale).dp else 460.dp) { landscape ->
        val d = RowDims(big = car && landscape, factor = if (car && landscape) carScale else 1f)
        QueueSheetHeader(
            title = stringResource(
                when {
                    adding != null -> R.string.queue_add_title
                    radio != null -> R.string.radio_picker_title
                    else -> R.string.queue_switcher_title
                }
            ),
            subtitle = adding?.subtitle ?: radio?.seed?.title ?: stringResource(R.string.queue_switcher_hint).takeIf { onLongPress != null && !d.big },
            d = d,
            onClose = onDismiss,
            trailing = if (d.big) {
                {
                    GlassCircleButton(onClick = onNew, contentDescription = newLabel, size = d.dp(44f, 60f)) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(d.dp(20f, 28f)))
                    }
                }
            } else null
        )
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = d.dp(8f, 14f), vertical = d.dp(4f, 6f)),
            verticalArrangement = Arrangement.spacedBy(d.dp(4f, 6f))
        ) {
            if (radio != null) {
                item(key = "keep") { KeepRow(keepHistory, onKeepHistory, d) }
            }
            items(rows, key = { it.id }) { row ->
                QueueRow(row, d, onClick = { onSelect(row) }, onLongClick = if (d.big || onLongPress == null) null else ({ onLongPress(row) }))
            }
        }
        if (!d.big) {
            GlassPillButton(
                text = newLabel,
                onClick = onNew,
                icon = Icons.Rounded.Add,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp)
            )
        }
    }
}

/** The radio picker's Keep switch (D52): a whole-row target, big in the car (§5). */
@Composable
private fun KeepRow(checked: Boolean, onChange: (Boolean) -> Unit, d: RowDims) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(d.dp(64f, 88f))
            .clip(RoundedCornerShape(d.dp(18f, 24f)))
            .hifiPress({ onChange(!checked) })
            .padding(start = d.dp(16f, 20f), end = d.dp(14f, 20f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.dp(12f, 18f))
    ) {
        Icon(Icons.Rounded.History, contentDescription = null, tint = HiFi.TextSecondary, modifier = Modifier.size(d.dp(24f, 32f)))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.radio_keep), color = Color.White, fontSize = d.sp(16f, 22f), fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                stringResource(R.string.radio_keep_hint),
                color = HiFi.TextSecondary,
                fontSize = d.sp(12f, 16f),
                lineHeight = d.sp(16f, 20f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        HiFiSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun QueueSheetHeader(title: String, subtitle: String?, d: RowDims, onClose: () -> Unit, trailing: (@Composable () -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = d.dp(20f, 28f), end = d.dp(16f, 24f), top = d.dp(6f, 26f), bottom = d.dp(10f, 14f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                fontSize = d.sp(26f, 36f),
                lineHeight = d.sp(32f, 42f),
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.02).em,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(subtitle, color = HiFi.TextSecondary, fontSize = d.sp(13f, 17f), lineHeight = d.sp(18f, 22f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(d.dp(10f, 10f)))
            trailing()
        }
        Spacer(Modifier.width(d.dp(10f, 10f)))
        GlassCircleButton(onClick = onClose, contentDescription = stringResource(R.string.cd_zakryt), size = d.dp(44f, 60f)) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(d.dp(18f, 26f)))
        }
    }
}

/** One queue: cover with the kind badge, name, current track, progress; the active one on anodized metal. */
@Composable
private fun QueueRow(row: QueueRowState, d: RowDims, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(d.dp(18f, 24f))
    Row(
        Modifier
            .fillMaxWidth()
            .height(d.dp(78f, 100f))
            .then(if (row.isActive) Modifier.hifiAnodized(shape) else Modifier.clip(shape))
            .hifiPress(onClick, onLongClick = onLongClick)
            .padding(start = d.dp(12f, 16f), end = d.dp(14f, 20f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.dp(12f, 18f))
    ) {
        QueueCover(row, d)
        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                color = Color.White,
                fontSize = d.sp(17f, 24f),
                lineHeight = d.sp(22f, 30f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(row.trackTitle, row.artist?.takeIf { it.isNotBlank() }).joinToString(" · "),
                color = HiFi.TextSecondary,
                fontSize = d.sp(13f, 17f),
                lineHeight = d.sp(17f, 22f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            HiFiProgressBar(
                progress = { row.progress },
                height = d.dp(3f, 4f),
                modifier = Modifier.padding(top = d.dp(7f, 10f)).alpha(if (row.isActive) 1f else 0.7f)
            )
        }
        Box(Modifier.widthIn(min = d.dp(58f, 84f)), contentAlignment = Alignment.CenterEnd) {
            if (row.isActive) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Color.White, modifier = Modifier.size(d.dp(24f, 34f)))
            } else {
                if (row.keepHistory) {
                    Icon(
                        Icons.Rounded.History,
                        contentDescription = stringResource(R.string.cd_radio_keep_on),
                        tint = HiFi.TextTertiary,
                        modifier = Modifier.padding(end = d.dp(20f, 28f)).size(d.dp(14f, 18f))
                    )
                }
                Text(
                    text = when {
                        row.kind != QueueKind.STATIC && row.kind != QueueKind.MIX -> stringResource(R.string.queue_endless)
                        row.position != null -> stringResource(R.string.queue_position, row.position, row.total)
                        else -> row.total.toString()
                    },
                    color = HiFi.TextTertiary,
                    fontSize = d.sp(13f, 17f),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun QueueCover(row: QueueRowState, d: RowDims) {
    val size = d.dp(52f, 68f)
    val badge = d.dp(22f, 28f)
    val shape = RoundedCornerShape(d.dp(10f, 14f))
    Box(Modifier.size(size)) {
        Box(Modifier.size(size).clip(shape).background(HiFi.Placeholder).border(1.dp, HiFi.Hairline, shape), contentAlignment = Alignment.Center) {
            Icon(kindIcon(row.kind), contentDescription = null, tint = HiFi.TextTertiary, modifier = Modifier.size(size / 2))
            if (row.coverUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).cover(row.coverUrl, CoverSize.Small).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size)
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = d.dp(6f, 6f), y = d.dp(6f, 6f))
                .size(badge)
                .clip(CircleShape)
                .background(HiFi.SheetFill)
                .border(1.dp, HiFi.GlassBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(kindIcon(row.kind), contentDescription = null, tint = HiFi.TextSecondary, modifier = Modifier.size(d.dp(14f, 17f)))
        }
    }
}

private fun kindIcon(kind: QueueKind): ImageVector = when (kind) {
    QueueKind.STATIC -> Icons.AutoMirrored.Rounded.QueueMusic
    QueueKind.WAVE -> Icons.Rounded.Waves
    QueueKind.RADIO -> Icons.Rounded.Sensors
    QueueKind.MIX -> Icons.Rounded.AutoAwesome
}
