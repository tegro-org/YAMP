package org.tegro.yamp.presentation.sources

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.UsbOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import org.tegro.yamp.R
import org.tegro.yamp.data.provider.local.LocalOverview
import org.tegro.yamp.data.provider.local.SkippedFile
import org.tegro.yamp.data.provider.local.SourceState
import org.tegro.yamp.data.provider.local.SourceStatus
import org.tegro.yamp.presentation.hifi.AppDialog
import org.tegro.yamp.presentation.hifi.GlassCircleButton
import org.tegro.yamp.presentation.hifi.GlassPillButton
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiActivityIndicator
import org.tegro.yamp.presentation.hifi.HiFiAlertDialog
import org.tegro.yamp.presentation.hifi.HiFiDialogAction
import org.tegro.yamp.presentation.hifi.HiFiDialogActionKind
import org.tegro.yamp.presentation.hifi.HiFiDialogDefaults
import org.tegro.yamp.presentation.hifi.HiFiEmptyState
import org.tegro.yamp.presentation.hifi.HiFiProgressBar
import org.tegro.yamp.presentation.hifi.HiFiSheetPanel
import org.tegro.yamp.presentation.hifi.HiFiSwitch
import org.tegro.yamp.presentation.hifi.MetalPillButton
import org.tegro.yamp.presentation.hifi.PanelSide
import org.tegro.yamp.presentation.main.components.ContextMenuItem
import org.tegro.yamp.utils.russianQuantityString

/** What the Sources screen does; the host wires it to [SourcesViewModel]. */
class SourcesActions(
    val onBack: () -> Unit,
    val onDismiss: () -> Unit,
    val onAddFolder: () -> Unit,
    val onRescan: (full: Boolean, sourceId: Long?) -> Unit,
    val onIncludeHidden: (sourceId: Long, include: Boolean) -> Unit,
    val onExclude: (SourceStatus) -> Unit,
    val onRemove: (sourceId: Long) -> Unit
)

/** "6 940 tracks" / "1 source". */
@Composable
internal fun tracksText(n: Int) = russianQuantityString(n, R.string.tracks_count_one, R.string.tracks_count_few, R.string.tracks_count_many)

@Composable
private fun sourcesText(n: Int) = russianQuantityString(n, R.string.local_sources_one, R.string.local_sources_few, R.string.local_sources_many)

@Composable
internal fun unsupportedText(n: Int) = russianQuantityString(n, R.string.local_unsupported_one, R.string.local_unsupported_few, R.string.local_unsupported_many)

/** The account card's lines (canvas `lo-add-result`, `lo-accounts-car`): "N sources · N tracks", then the status. */
@Composable
fun localAccountLines(overview: LocalOverview): Pair<String, String?> {
    val first = listOf(sourcesText(overview.sources.size), tracksText(overview.tracks)).joinToString(" · ")
    val scanning = overview.scanning
    val second = when {
        scanning != null -> scanning.percent?.let { stringResource(R.string.local_scanning_percent, it) } ?: stringResource(R.string.local_scanning)
        overview.absent.isNotEmpty() -> stringResource(R.string.local_volume_not_connected_line, overview.absent.joinToString(", "))
        else -> null
    }
    return first to second
}

/**
 * Settings → Account management → "This device" (canvas `lo-sources`, `lo-sources-car-*`): one card per source —
 * volume, folder, status (available · scanning N % · not connected · needs access), tracks — the skipped files'
 * card, Add folder and Rescan. A card's ⋮ opens its menu: Exclude folders… · Show hidden · Rescan · Full rescan ·
 * Remove. Without sources it is the empty state (`lo-empty`). On the head unit the actions sit in a column on the
 * wheel side and the menu is a panel there.
 */
@Composable
fun SourcesDialog(overview: LocalOverview, big: Boolean, wheelLeft: Boolean, actions: SourcesActions) {
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var removeFor by remember { mutableStateOf<SourceStatus?>(null) }
    var showSkipped by remember { mutableStateOf(false) }
    val side = if (wheelLeft) PanelSide.LEFT else PanelSide.RIGHT
    AppDialog(onDismissRequest = actions.onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)).clickable(onClick = actions.onDismiss), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .padding(vertical = if (big) 12.dp else 28.dp)
                    .fillMaxWidth(if (big) 0.94f else 0.92f)
                    .clip(HiFiDialogDefaults.Shape)
                    .background(HiFiDialogDefaults.Container)
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), HiFiDialogDefaults.Shape)
                    .clickable(enabled = false) {}
                    .padding(if (big) 18.dp else 22.dp)
            ) {
                SourcesHead(overview, big, wheelLeft, actions)
                if (overview.sources.isEmpty()) {
                    HiFiEmptyState(
                        icon = Icons.Rounded.CreateNewFolder,
                        title = stringResource(R.string.local_empty_title),
                        message = stringResource(R.string.local_empty_text),
                        actionLabel = stringResource(R.string.local_add_folder),
                        onAction = actions.onAddFolder
                    )
                } else if (big) {
                    CompositionLocalProvider(LocalLayoutDirection provides if (wheelLeft) LayoutDirection.Ltr else LayoutDirection.Rtl) {
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    MetalPillButton(stringResource(R.string.local_add_folder), actions.onAddFolder, Modifier.fillMaxWidth(), icon = Icons.Rounded.CreateNewFolder, height = 56.dp)
                                    GlassPillButton(stringResource(R.string.local_rescan), { actions.onRescan(false, null) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh, height = 56.dp)
                                    Note(stringResource(R.string.local_sources_note_short), big)
                                }
                            }
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    SourceCards(overview, big, wheelLeft, menuFor, onMenu = { menuFor = it }, onSkipped = { showSkipped = true })
                                }
                            }
                        }
                    }
                } else {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SourceCards(overview, big, wheelLeft, menuFor, onMenu = { menuFor = it }, onSkipped = { showSkipped = true })
                        Note(stringResource(R.string.local_sources_note), big)
                    }
                    Spacer(Modifier.height(14.dp))
                    MetalPillButton(stringResource(R.string.local_add_folder), actions.onAddFolder, Modifier.fillMaxWidth(), icon = Icons.Rounded.CreateNewFolder, height = 48.dp)
                    Spacer(Modifier.height(10.dp))
                    GlassPillButton(stringResource(R.string.local_rescan), { actions.onRescan(false, null) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh, height = 48.dp)
                    Text(
                        stringResource(R.string.local_rescan_note), color = HiFi.TextTertiary, fontSize = 11.5.sp, lineHeight = 16.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
    menuFor?.let { id ->
        val source = overview.sources.firstOrNull { it.id == id }
        if (source == null) menuFor = null
        else SourceMenu(source, big, side, actions, onRemove = { removeFor = source }, onDismiss = { menuFor = null })
    }
    removeFor?.let { source ->
        HiFiAlertDialog(
            onDismissRequest = { removeFor = null },
            title = stringResource(R.string.local_remove_title),
            content = {
                Text(stringResource(R.string.local_remove_text, sourceTitle(source)), color = HiFi.TextSecondary, fontSize = 15.sp, lineHeight = 21.sp)
            },
            confirm = { HiFiDialogAction(stringResource(R.string.account_management_udalit), { removeFor = null; actions.onRemove(source.id) }, HiFiDialogActionKind.Danger) },
            dismiss = { HiFiDialogAction(stringResource(R.string.account_management_otmena), { removeFor = null }, HiFiDialogActionKind.Quiet) }
        )
    }
    if (showSkipped) SkippedSheet(overview.skipped, big, side, onDismiss = { showSkipped = false })
}

/** "SD card · Music". */
private fun sourceTitle(source: SourceStatus) = listOf(source.volumeName, source.folder).filter { it.isNotEmpty() }.joinToString(" · ")

@Composable
private fun SourcesHead(overview: LocalOverview, big: Boolean, wheelLeft: Boolean, actions: SourcesActions) {
    CompositionLocalProvider(LocalLayoutDirection provides if (!big || wheelLeft) LayoutDirection.Ltr else LayoutDirection.Rtl) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCircleButton(onClick = actions.onBack, contentDescription = stringResource(R.string.cd_nazad), size = if (big) 48.dp else 44.dp) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            LocalDeviceMark(if (big) 30.dp else 30.dp)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.local_sources), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (overview.sources.isNotEmpty()) {
                        Text(
                            stringResource(R.string.local_device_name) + " · " + tracksText(overview.tracks),
                            color = HiFi.TextSecondary, fontSize = if (big) 14.sp else 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            GlassCircleButton(onClick = actions.onDismiss, contentDescription = stringResource(R.string.cd_zakryt), size = if (big) 48.dp else 44.dp) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ColumnScope.SourceCards(
    overview: LocalOverview,
    big: Boolean,
    wheelLeft: Boolean,
    pressed: Long?,
    onMenu: (Long) -> Unit,
    onSkipped: () -> Unit
) {
    overview.sources.forEach { source -> SourceCard(source, big, wheelLeft, pressed == source.id) { onMenu(source.id) } }
    if (overview.skippedCount > 0) SkippedCard(overview, big, onSkipped)
}

/** One source (canvas `LoSourceCard`). */
@Composable
private fun SourceCard(source: SourceStatus, big: Boolean, wheelLeft: Boolean, pressed: Boolean, onMenu: () -> Unit) {
    val absent = source.state == SourceState.ABSENT
    val shape = RoundedCornerShape(16.dp)
    val mirror = big && !wheelLeft
    CompositionLocalProvider(LocalLayoutDirection provides if (mirror) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, if (pressed) HiFi.Accent else Color.White.copy(alpha = 0.10f), shape)
                .clickable(onClick = onMenu)
                .padding(start = 12.dp, end = 4.dp, top = if (big) 9.dp else 12.dp, bottom = if (big) 9.dp else 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (big) MenuButton(big, onMenu)
                VolumeArt(source.volumeId, source.volumeName, if (big) 40.dp else 44.dp, dim = absent)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(Modifier.weight(1f), horizontalAlignment = if (mirror) Alignment.End else Alignment.Start) {
                        Row(Modifier.alpha(if (absent) 0.55f else 1f)) {
                            Text(source.volumeName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (source.folder.isNotEmpty()) Text(" · " + source.folder, color = HiFi.TextTertiary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        StatusLine(source)
                        val meta = when (source.state) {
                            SourceState.ABSENT -> listOfNotNull(tracksText(source.tracks), source.lastSeenAt?.let { stringResource(R.string.local_last_seen, ago(it)) }).joinToString(" · ")
                            SourceState.AVAILABLE -> source.lastScanAt?.let { stringResource(R.string.local_scanned_ago, ago(it)) }
                            SourceState.NOT_SCANNED -> stringResource(R.string.local_not_scanned)
                            else -> null
                        }
                        if (meta != null && !(big && source.state == SourceState.AVAILABLE)) {
                            Text(meta, color = HiFi.TextTertiary, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (!big) MenuButton(big, onMenu)
            }
            if (source.state == SourceState.SCANNING) {
                Column(Modifier.padding(start = if (big) 0.dp else 56.dp, end = 8.dp, top = if (big) 6.dp else 8.dp)) {
                    val percent = source.percent
                    HiFiProgressBar(percent?.let { p -> { p / 100f } }, height = 4.dp)
                    source.scanFolder?.takeIf { !big && it.isNotEmpty() }?.let {
                        Text(it, color = HiFi.TextTertiary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuButton(big: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(if (big) 48.dp else 40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.cd_account_menu), tint = HiFi.TextSecondary, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun StatusLine(source: SourceStatus) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        when (source.state) {
            SourceState.AVAILABLE, SourceState.NOT_SCANNED -> {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = HiFi.Success, modifier = Modifier.size(14.dp))
                StatusText(stringResource(R.string.local_available) + " · " + tracksText(source.tracks))
            }
            SourceState.SCANNING -> {
                HiFiActivityIndicator(size = 13.dp)
                val text = source.percent?.let { stringResource(R.string.local_scanning_percent, it) } ?: stringResource(R.string.local_scanning)
                StatusText(if (source.total > 0) "$text · ${source.done} / ${source.total}" else text)
            }
            SourceState.ABSENT -> {
                Icon(Icons.Rounded.UsbOff, contentDescription = null, tint = HiFi.Warning, modifier = Modifier.size(14.dp))
                StatusText(stringResource(R.string.local_not_connected), HiFi.Warning)
            }
            SourceState.NO_ACCESS -> {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = HiFi.Warning, modifier = Modifier.size(14.dp))
                StatusText(stringResource(R.string.local_needs_access), HiFi.Warning)
            }
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color = HiFi.TextSecondary) =
    Text(text, color = color, fontSize = 12.5.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)

/** "5 minutes ago", "yesterday" — in the app's language. */
private fun ago(at: Long): String {
    val now = System.currentTimeMillis()
    // Under a minute the minute resolution says "0 minutes ago": seconds then
    val resolution = if (now - at < DateUtils.MINUTE_IN_MILLIS) DateUtils.SECOND_IN_MILLIS else DateUtils.MINUTE_IN_MILLIS
    return DateUtils.getRelativeTimeSpanString(at, now, resolution).toString().lowercase()
}

/** The skipped files' card (canvas `LoSkippedCard`): "12 files not supported" and the formats. */
@Composable
private fun SkippedCard(overview: LocalOverview, big: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, HiFi.GlassBorder, shape)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 6.dp, top = if (big) 8.dp else 10.dp, bottom = if (big) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.width(if (big) 40.dp else 44.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Block, contentDescription = null, tint = HiFi.TextSecondary, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(unsupportedText(overview.skippedCount), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            val names = overview.skipped.keys.associateWith { formatName(it) }
            val formats = overview.skipped.entries.joinToString(" · ") { (format, files) -> "${files.size} ${names[format]}" }
            Text(stringResource(R.string.local_unsupported_hint, formats), color = HiFi.TextTertiary, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = HiFi.TextTertiary)
    }
}

/** `ape` → APE, `wv` → WavPack, `unreadable` → the word. */
@Composable
private fun formatName(format: String): String = when (format) {
    org.tegro.yamp.data.provider.local.scan.ScanSummary.UNREADABLE -> stringResource(R.string.local_unreadable)
    "wv" -> "WavPack"
    "alac" -> "ALAC"
    else -> format.uppercase()
}

@Composable
private fun Note(text: String, big: Boolean) {
    Text(text, color = HiFi.TextTertiary, fontSize = if (big) 13.sp else 11.5.sp, lineHeight = if (big) 18.sp else 16.sp, modifier = Modifier.padding(horizontal = 4.dp))
}

/** ⋮ on a source (canvas `lo-sources-menu`): Exclude folders… · Show hidden · Rescan · Full rescan · Remove source. */
@Composable
private fun SourceMenu(source: SourceStatus, big: Boolean, side: PanelSide, actions: SourcesActions, onRemove: () -> Unit, onDismiss: () -> Unit) {
    HiFiSheetPanel(onDismiss = onDismiss, side = side, sheetHeight = null, panelWidth = if (big) 520.dp else 460.dp) {
        Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VolumeArt(source.volumeId, source.volumeName, if (big) 40.dp else 44.dp, dim = source.state == SourceState.ABSENT)
            Column {
                Text(sourceTitle(source), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = if (big) 19.sp else 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusLine(source)
            }
        }
        val excludes = source.excludes
        ContextMenuItem(
            Icons.Rounded.FolderOff, stringResource(R.string.local_exclude), big = big,
            note = excludes.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.local_excluded_n, it.size, it.joinToString(", ") { p -> p.substringAfterLast('/') }) },
            onClick = { onDismiss(); actions.onExclude(source) }
        )
        ContextMenuItem(
            Icons.Rounded.Visibility, stringResource(R.string.local_show_hidden), big = big,
            trailing = { HiFiSwitch(source.includeHidden, { actions.onIncludeHidden(source.id, it) }) },
            onClick = { actions.onIncludeHidden(source.id, !source.includeHidden) }
        )
        ContextMenuItem(Icons.Rounded.Refresh, stringResource(R.string.local_rescan), big = big, note = stringResource(R.string.local_rescan_sub), enabled = source.state != SourceState.ABSENT) {
            onDismiss(); actions.onRescan(false, source.id)
        }
        ContextMenuItem(Icons.Rounded.RestartAlt, stringResource(R.string.local_full_rescan), big = big, note = stringResource(R.string.local_full_rescan_sub), enabled = source.state != SourceState.ABSENT) {
            onDismiss(); actions.onRescan(true, source.id)
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).height(1.dp).background(HiFi.Hairline))
        ContextMenuItem(Icons.Rounded.Delete, stringResource(R.string.local_remove_source), iconTint = HiFi.Danger, textColor = HiFi.Danger, big = big) {
            onDismiss(); onRemove()
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** "12 files not supported" → the list by format (canvas `lo-sources-skipped`). */
@Composable
private fun SkippedSheet(skipped: Map<String, List<SkippedFile>>, big: Boolean, side: PanelSide, onDismiss: () -> Unit) {
    HiFiSheetPanel(onDismiss = onDismiss, side = side, sheetHeight = 0.7f, panelWidth = if (big) 640.dp else 460.dp) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Text(unsupportedText(skipped.values.sumOf { it.size }), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(stringResource(R.string.local_unsupported_text), color = HiFi.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
            skipped.forEach { (format, files) ->
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatName(format).uppercase(), color = HiFi.TextTertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.08.sp, modifier = Modifier.weight(1f))
                    Text(russianQuantityString(files.size, R.string.local_audio_files_one, R.string.local_audio_files_few, R.string.local_audio_files_many), color = HiFi.TextTertiary, fontSize = 12.sp)
                }
                files.take(SKIPPED_SHOWN).forEach { file ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.MusicOff, contentDescription = null, tint = HiFi.TextTertiary, modifier = Modifier.size(20.dp))
                        Text("${file.volumeName} › ${file.relPath}", color = HiFi.TextSecondary, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (files.size > SKIPPED_SHOWN) {
                    Text(stringResource(R.string.local_more, files.size - SKIPPED_SHOWN), color = HiFi.TextTertiary, fontSize = 12.5.sp, modifier = Modifier.padding(start = 30.dp, top = 8.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private const val SKIPPED_SHOWN = 20
