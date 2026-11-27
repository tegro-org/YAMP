package org.tegro.yamp.presentation.sources

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.tegro.yamp.R
import org.tegro.yamp.data.provider.local.LocalSources
import org.tegro.yamp.data.provider.local.SourceStatus
import org.tegro.yamp.presentation.folders.FolderPlaceholder
import org.tegro.yamp.presentation.hifi.AppDialog
import org.tegro.yamp.presentation.hifi.GlassCircleButton
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiDialogDefaults
import org.tegro.yamp.presentation.hifi.HiFiSwitch
import org.tegro.yamp.presentation.hifi.IconPlaque
import org.tegro.yamp.presentation.hifi.MetalPillButton
import org.tegro.yamp.utils.russianQuantityString

/**
 * Where the "This device" flow starts: adding a folder (Add account → This device), its Sources (its card), or the
 * first start's add — there is no Account management behind it, so a cancel or a failed add just closes the flow.
 */
enum class LocalStart { ADD, SOURCES, FIRST_RUN }

/**
 * The "This device" flow (canvas section 7b, 1–2): adding a folder — the system folder picker on Android 11+ (a SAF
 * tree grant, no media permission), the storage permission with its one-line reason and the app's own folder chooser on
 * Android 9–10 (D93), where a USB drive, which the file API can't read, is picked in the system picker from the chooser —
 * and the Sources screen with its Exclude screen. A cancelled first add shows the empty Sources
 * (`lo-empty`). [onClose] ends the flow (back to Account management, where the new card shows the first scan).
 */
@Composable
fun LocalSourcesHost(start: LocalStart, big: Boolean, wheelLeft: Boolean, onClose: () -> Unit, vm: SourcesViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val denied = stringResource(R.string.local_permission_denied)
    val overview by vm.overview.collectAsStateWithLifecycle()
    var showSources by remember { mutableStateOf(start == LocalStart.SOURCES) }
    var reason by remember { mutableStateOf(false) }
    var chooser by remember { mutableStateOf(false) }
    var exclude by remember { mutableStateOf<SourceStatus?>(null) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    val firstRun = start == LocalStart.FIRST_RUN
    val afterAdd: (Boolean) -> Unit = { added -> if ((added || firstRun) && !showSources) onClose() }
    val afterCancel = { if (!showSources) { if (overview.sources.isEmpty() && !firstRun) showSources = true else onClose() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addTree(uri, afterAdd) else afterCancel()
    }
    // From the app's chooser (API ≤ 29, a USB drive): a cancel or an unusable tree goes back to the chooser
    val volumePicker = rememberLauncherForActivityResult(OpenVolumeTree()) { uri ->
        vm.notePicker(if (uri == null) "cancelled" else "picked")
        if (uri != null) {
            vm.addTree(uri) { added ->
                if (added) {
                    chooser = false
                    afterAdd(true)
                } else {
                    // A tree YAMP can't use (another provider, or no grant): closes, and its message says why
                    chooser = false
                    afterAdd(false)
                }
            }
        }
    }
    val noPicker = stringResource(R.string.local_no_system_picker)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            chooser = true
        } else {
            Toast.makeText(context, denied, Toast.LENGTH_LONG).show()
            afterCancel()
        }
    }
    val addFolder: () -> Unit = {
        if (vm.usesSystemPicker) {
            runCatching { picker.launch(null) }.onFailure { afterCancel() }
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            chooser = true
        } else {
            reason = true
        }
    }
    LaunchedEffect(Unit) {
        if (!started && start != LocalStart.SOURCES) {
            started = true
            addFolder()
        }
    }
    if (showSources) {
        SourcesDialog(
            overview, big, wheelLeft,
            SourcesActions(
                onBack = onClose,
                onDismiss = onClose,
                onAddFolder = addFolder,
                onRescan = { full, id -> vm.rescan(full, id) },
                onIncludeHidden = vm::setIncludeHidden,
                onExclude = { exclude = it },
                onRemove = vm::remove
            )
        )
    }
    if (reason) {
        LegacyPermissionReason(big, onContinue = {
            reason = false
            permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }, onDismiss = {
            reason = false
            afterCancel()
        })
    }
    if (chooser) {
        FolderChooser(vm, big, wheelLeft, vm.canSystemPicker, onPick = { volumeId, relPath ->
            chooser = false
            vm.addPath(volumeId, relPath, afterAdd)
        }, onSystemPicker = { volumeId, relPath ->
            vm.notePicker("open", volumeId)
            runCatching { volumePicker.launch(LocalSources.volumeDocumentUri(volumeId, relPath)) }
                .onFailure {
                    vm.notePicker("failed: ${it.javaClass.simpleName}")
                    Toast.makeText(context, noPicker, Toast.LENGTH_LONG).show()
                }
        }, onDismiss = {
            chooser = false
            afterCancel()
        })
    }
    exclude?.let { source ->
        // The excludes come from the overview: a switch shows its new state as soon as the store has it
        val live = overview.sources.firstOrNull { it.id == source.id } ?: source
        ExcludeDialog(vm, live, big, onDismiss = { exclude = null })
    }
}

/**
 * The system folder picker starting at its input (`EXTRA_INITIAL_URI`), with the device's storage roots shown
 * (`EXTRA_SHOW_ADVANCED`, honoured by the system's DocumentsUI; without it Android 9 may hide the internal storage).
 */
private class OpenVolumeTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent = super.createIntent(context, input).putExtra(EXTRA_SHOW_ADVANCED, true)

    private companion object {
        const val EXTRA_SHOW_ADVANCED = "android.content.extra.SHOW_ADVANCED"
    }
}

/** A dialog panel as the account dialogs have it. */
@Composable
private fun Panel(big: Boolean, onDismiss: () -> Unit, fill: Boolean = false, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    AppDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .padding(vertical = if (big) 12.dp else 28.dp)
                    .fillMaxWidth(if (big) 0.94f else 0.92f)
                    .let { if (fill) it.fillMaxSize() else it }
                    .clip(HiFiDialogDefaults.Shape)
                    .background(HiFiDialogDefaults.Container)
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), HiFiDialogDefaults.Shape)
                    .clickable(enabled = false) {}
                    .padding(if (big) 18.dp else 22.dp)
            ) { content() }
        }
    }
}

@Composable
private fun DialogHead(title: String, sub: String?, onBack: (() -> Unit)?, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        onBack?.let {
            GlassCircleButton(onClick = it, contentDescription = stringResource(R.string.cd_nazad), size = 44.dp) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            sub?.let { Text(it, color = HiFi.TextSecondary, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        GlassCircleButton(onClick = onClose, contentDescription = stringResource(R.string.cd_zakryt), size = 44.dp) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/** Android 9–10 (canvas `lo-add-legacy-perm`): YAMP's one-line reason before the system storage prompt. */
@Composable
private fun LegacyPermissionReason(big: Boolean, onContinue: () -> Unit, onDismiss: () -> Unit) {
    AppDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(min = 280.dp, max = if (big) 460.dp else 420.dp)
                .clip(HiFiDialogDefaults.Shape)
                .background(HiFiDialogDefaults.Container)
                .border(1.dp, HiFiDialogDefaults.Rim, HiFiDialogDefaults.Shape)
                .padding(22.dp)
        ) {
            DialogHead(stringResource(R.string.local_device_name), null, null, onDismiss)
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconPlaque(Icons.Rounded.FolderOpen, size = 40.dp)
                Text(stringResource(R.string.local_permission_text), color = HiFi.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
            }
            MetalPillButton(stringResource(R.string.local_continue), onContinue, Modifier.fillMaxWidth().padding(top = 16.dp), height = 44.dp)
        }
    }
}

/**
 * YAMP's own folder chooser (API ≤ 29, canvas `lo-add-legacy-pick`, `lo-add-legacy-pick-car-*`): the volumes as chips
 * (the head unit: a column on the wheel side), breadcrumbs, the level's folders, what is in it, "Use this folder". A
 * volume the file API can't read (a USB drive) says so and offers the system picker instead ([onSystemPicker]).
 */
@Composable
private fun FolderChooser(
    vm: SourcesViewModel,
    big: Boolean,
    wheelLeft: Boolean,
    canSystemPicker: Boolean,
    onPick: (String, String) -> Unit,
    onSystemPicker: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val phone = stringResource(R.string.local_volume_phone)
    val volumes = remember { vm.chooserVolumes(phone) }
    var volume by remember { mutableStateOf(volumes.firstOrNull { it.removable } ?: volumes.firstOrNull()) }
    var path by remember { mutableStateOf("") }
    var level by remember { mutableStateOf<ChooserLevel?>(null) }
    LaunchedEffect(volume, path) {
        level = null
        volume?.takeIf { it.readable }?.let { level = vm.chooserLevel(it.id, path) }
    }
    val goUp: (() -> Unit)? = if (path.isNotEmpty()) ({ path = path.substringBeforeLast('/', "") }) else null
    Panel(big, onDismiss, fill = true) {
        DialogHead(stringResource(R.string.local_choose_folder), stringResource(R.string.local_choose_folder_sub).takeIf { !big }, goUp ?: onDismiss, onDismiss)
        if (volumes.isEmpty()) {
            Text(stringResource(R.string.local_no_volumes), color = HiFi.TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(top = 20.dp))
            return@Panel
        }
        val volumeList: @Composable () -> Unit = {
            volumes.forEach { v ->
                val on = v.id == volume?.id
                VolumeChip(v, on, big) { volume = v; path = "" }
            }
        }
        val folderList: @Composable (Modifier) -> Unit = { modifier: Modifier ->
            Column(modifier) {
                val crumbs = listOfNotNull(volume?.name) + path.split('/').filter { it.isNotEmpty() }
                Text(crumbs.joinToString(" › "), color = HiFi.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 8.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    val shown = level
                    when {
                        volume?.readable == false -> Text(stringResource(R.string.local_volume_system_picker), color = HiFi.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                        shown == null -> Unit
                        shown.failed -> Text(stringResource(R.string.local_folder_unreadable), color = HiFi.Warning, fontSize = 14.sp)
                        else -> shown.folders.forEach { (name, items) ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = if (big) 52.dp else 56.dp).clickable { path = if (path.isEmpty()) name else "$path/$name" },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                FolderPlaceholder(name, if (big) 36.dp else 40.dp, radius = 8.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(russianQuantityString(items, R.string.local_items_one, R.string.local_items_few, R.string.local_items_many), color = HiFi.TextTertiary, fontSize = 12.sp)
                                }
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = HiFi.TextTertiary)
                            }
                        }
                    }
                }
                level?.takeIf { !it.failed }?.let { l ->
                    val name = path.substringAfterLast('/').ifEmpty { volume?.name.orEmpty() }
                    val parts = listOfNotNull(
                        name,
                        russianQuantityString(l.audioFiles, R.string.local_audio_files_one, R.string.local_audio_files_few, R.string.local_audio_files_many),
                        if (l.unsupported > 0) unsupportedText(l.unsupported) else null
                    )
                    Text(parts.joinToString(" · "), color = HiFi.TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
                }
            }
        }
        val use: @Composable (Modifier) -> Unit = { modifier ->
            val v = volume
            // The app can't read or list this volume (a USB drive on the head unit): the system picker is the way in,
            // but only where a DocumentsUI can be opened; without one the row is simply unusable.
            val blocked = v != null && chooserNeedsSystemPicker(v.readable, level?.failed == true)
            if (v != null && blocked && canSystemPicker) {
                MetalPillButton(stringResource(R.string.local_choose_in_system_picker), { onSystemPicker(v.id, path) }, modifier, icon = Icons.Rounded.FolderOpen, height = if (big) 52.dp else 48.dp)
            } else {
                MetalPillButton(stringResource(R.string.local_use_this_folder), { v?.let { onPick(it.id, path) } }, modifier, icon = Icons.Rounded.Check, height = if (big) 52.dp else 48.dp, enabled = v != null && level?.failed == false)
            }
        }
        if (big) {
            CompositionLocalProvider(LocalLayoutDirection provides if (wheelLeft) LayoutDirection.Ltr else LayoutDirection.Rtl) {
                Row(Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.local_storage).uppercase(), color = HiFi.TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            volumeList()
                            Spacer(Modifier.weight(1f))
                            use(Modifier.fillMaxWidth())
                        }
                        folderList(Modifier.weight(1f))
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { volumeList() }
            folderList(Modifier.fillMaxWidth().weight(1f))
            use(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun VolumeChip(volume: ChooserVolume, on: Boolean, big: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(if (big) 16.dp else 18.dp)
    Row(
        Modifier
            .let { if (big) it.fillMaxWidth().height(52.dp) else it.height(36.dp) }
            .clip(shape)
            .background(if (on) (if (big) Color.White.copy(alpha = 0.14f) else Color.White) else if (big) Color.Transparent else HiFi.GlassFill)
            .border(1.dp, if (on) (if (big) HiFi.GlassBorder else Color.White) else if (big) Color.Transparent else HiFi.GlassBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (big) 12.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (big) 10.dp else 6.dp)
    ) {
        if (big) {
            VolumeArt(volume.id, volume.name, 34.dp, radius = 9.dp)
        } else {
            Icon(volumeIcon(volume.id, volume.name), contentDescription = null, tint = if (on) Color(0xFF0B0C10) else Color.White, modifier = Modifier.size(17.dp))
        }
        Text(volume.name, color = if (on && !big) Color(0xFF0B0C10) else Color.White, fontSize = if (big) 15.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Exclude folders (canvas `lo-sources-exclude`): the source's folders, a switch each (off = not scanned, its tracks
 * leave Library on the next scan), › to go deeper; hidden folders shown as skipped.
 */
@Composable
private fun ExcludeDialog(vm: SourcesViewModel, source: SourceStatus, big: Boolean, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf(source.folder) }
    var rows by remember { mutableStateOf<List<ExcludeRow>?>(null) }
    LaunchedEffect(path) {
        rows = null
        rows = vm.excludeRows(source.id, path) ?: emptyList()
    }
    val back = if (path != source.folder) ({ path = path.substringBeforeLast('/', "").takeIf { it.length >= source.folder.length } ?: source.folder }) else onDismiss
    Panel(big, onDismiss, fill = true) {
        DialogHead(stringResource(R.string.local_exclude_title), listOf(source.volumeName, path).filter { it.isNotEmpty() }.joinToString(" · "), back, onDismiss)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 14.dp)) {
            rows.orEmpty().forEach { row ->
                val excluded = source.excludes.any { row.relPath == it }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).alpha(if (row.hidden) 0.45f else 1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FolderPlaceholder(row.name.trimStart('.'), 40.dp, radius = 8.dp)
                    Column(Modifier.weight(1f)) {
                        Text(row.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (row.hidden && !source.includeHidden) stringResource(R.string.local_hidden_skipped)
                            else russianQuantityString(row.subfolders, R.string.folders_count_one, R.string.folders_count_few, R.string.folders_count_many),
                            color = HiFi.TextTertiary, fontSize = 12.sp
                        )
                    }
                    if (!row.hidden || source.includeHidden) {
                        HiFiSwitch(!excluded, { on -> vm.setExcluded(source.id, row.relPath, excluded = !on) })
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable(enabled = !excluded && row.subfolders > 0) { path = row.relPath }, contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = HiFi.TextTertiary.copy(alpha = if (!excluded && row.subfolders > 0) 1f else 0.3f))
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.local_exclude_note), color = HiFi.TextTertiary, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 12.dp))
    }
}
