package org.tegro.yamp.presentation.queues

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tegro.yamp.R
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiAlertDialog
import org.tegro.yamp.presentation.hifi.HiFiDialogAction
import org.tegro.yamp.presentation.hifi.HiFiDialogActionKind
import org.tegro.yamp.presentation.hifi.HiFiDialogDefaults
import org.tegro.yamp.presentation.hifi.HiFiSheetPanel
import org.tegro.yamp.presentation.hifi.HiFiSwitch
import org.tegro.yamp.domain.queue.QueueKind
import androidx.compose.material.icons.rounded.History
import org.tegro.yamp.presentation.hifi.PanelSide
import org.tegro.yamp.presentation.main.components.ContextMenuItem
import org.tegro.yamp.presentation.account.AccountChips
import org.tegro.yamp.presentation.account.AccountTag
import org.tegro.yamp.presentation.account.AccountMark
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight.Companion.ExtraBold
import androidx.compose.ui.unit.em
import org.tegro.yamp.presentation.hifi.GlassPillButton

private const val MAX_NAME_LENGTH = 60

/** "New queue" and "Rename": a name field; [hint] is the note under it (the canvas `PhoneQueueNew`). */
@Composable
internal fun QueueNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String = "",
    hint: String? = null,
    /** "New queue" with more than one account: the account selector under the name (D57, D58). */
    accounts: List<AccountTag> = emptyList(),
    selectedAccount: String? = null,
    onAccount: (String) -> Unit = {},
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(TextFieldValue(initialName, TextRange(initialName.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = name.text.isNotBlank()
    HiFiAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.text.length <= MAX_NAME_LENGTH) name = it },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text(stringResource(R.string.queue_name_hint)) },
                    colors = HiFiDialogDefaults.textFieldColors(),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
                if (accounts.size > 1) {
                    Text(stringResource(R.string.queue_account), color = HiFi.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                    AccountChips(accounts, selectedAccount, onAccount)
                    Text(stringResource(R.string.queue_account_hint), color = HiFi.TextTertiary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
                }
                if (hint != null) {
                    Text(hint, color = HiFi.TextTertiary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        },
        confirm = { HiFiDialogAction(confirmLabel, { onConfirm(name.text.trim()) }, enabled = valid) },
        dismiss = { HiFiDialogAction(stringResource(R.string.queue_cancel), onDismiss, HiFiDialogActionKind.Quiet) }
    )
}

/** "Delete queue?" with the danger action (the canvas `PhoneQueueDelete`). */
@Composable
internal fun DeleteQueueDialog(name: String, trackCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    HiFiAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.queue_delete_confirm_title),
        content = {
            Text(
                stringResource(R.string.queue_delete_confirm_text, name, pluralStringResource(R.plurals.queue_tracks, trackCount, trackCount)),
                fontSize = 15.sp,
                lineHeight = 21.sp
            )
        },
        confirm = { HiFiDialogAction(stringResource(R.string.queue_delete_action), onConfirm, HiFiDialogActionKind.Danger) },
        dismiss = { HiFiDialogAction(stringResource(R.string.queue_cancel), onDismiss, HiFiDialogActionKind.Quiet) }
    )
}

/**
 * Long-press on a queue (phone and landscape; never in the car): Rename, Duplicate, Clear, Delete — a wave
 * (not editable, one per account) offers Clear only, a radio Keep and Delete (D52). The canvas `PhoneQueueMenu`.
 */
@Composable
internal fun QueueMenuSheet(
    row: QueueRowState,
    side: PanelSide,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onKeepHistory: (Boolean) -> Unit = {},
    /** A removed account's queue (D62): the note how to get it back, and Delete only. */
    removed: Boolean = false,
    /** The removed account's marker, beside the name (canvas `ac-menu-removed`). */
    mark: AccountTag? = null,
    /** The removed account's name, when it was kept (D62); without it the note names no one. */
    removedAccountName: String? = null,
    onDismiss: () -> Unit
) {
    val isRadio = row.kind == QueueKind.RADIO
    HiFiSheetPanel(onDismiss = onDismiss, side = side, sheetHeight = null) {
        if (removed) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (mark != null) AccountMark(mark, 22.dp, dim = true)
                    Text(row.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    removedAccountName?.let { stringResource(R.string.queue_removed_account_note_named, it) }
                        ?: stringResource(R.string.queue_removed_account_note),
                    color = HiFi.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                )
            }
            ContextMenuItem(Icons.Rounded.Delete, stringResource(R.string.queue_delete), iconTint = HiFi.Danger, textColor = HiFi.Danger, onClick = onDelete)
            Spacer(Modifier.height(16.dp))
        } else {
            QueueMenuItems(row, isRadio, onRename, onDuplicate, onClear, onDelete, onKeepHistory)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.QueueMenuItems(
    row: QueueRowState,
    isRadio: Boolean,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onKeepHistory: (Boolean) -> Unit
) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp)) {
        Text(row.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val tracks = pluralStringResource(R.plurals.queue_tracks, row.total, row.total)
        Text(
            row.position?.let { stringResource(R.string.queue_paused_at, tracks, it) } ?: tracks,
            color = HiFi.TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 10.dp)
        )
    }
    if (row.editable) {
        ContextMenuItem(Icons.Rounded.Edit, stringResource(R.string.queue_rename), onClick = onRename)
        ContextMenuItem(Icons.Rounded.ContentCopy, stringResource(R.string.queue_duplicate), onClick = onDuplicate)
    }
    if (isRadio) {
        ContextMenuItem(
            Icons.Rounded.History,
            stringResource(R.string.radio_keep),
            trailing = { HiFiSwitch(checked = row.keepHistory, onCheckedChange = onKeepHistory) },
            onClick = { onKeepHistory(!row.keepHistory) }
        )
    } else {
        ContextMenuItem(Icons.Rounded.PlaylistRemove, stringResource(R.string.queue_clear), onClick = onClear)
    }
    if (row.editable || isRadio) {
        ContextMenuItem(Icons.Rounded.Delete, stringResource(R.string.queue_delete), iconTint = HiFi.Danger, textColor = HiFi.Danger, onClick = onDelete)
    }
    Spacer(Modifier.height(16.dp))
}

/**
 * A removed account's queue opened from the collapsed group (D62, canvas `ac-removed-open`): its name with the
 * dimmed marker, whose it was and where it stands, the tracks from the cursor on — all unavailable, none can be
 * played — and how to get it back. No banner; "Queues" goes back to the switcher.
 */
@Composable
internal fun RemovedQueueSheet(
    row: QueueRowState,
    mark: AccountTag?,
    /** Null while the stored queue is read. */
    view: RemovedQueueView?,
    side: PanelSide,
    removedAccountName: String?,
    onQueues: () -> Unit,
    onDismiss: () -> Unit
) {
    HiFiSheetPanel(onDismiss = onDismiss, side = side, sheetHeight = 0.78f) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (mark != null) AccountMark(mark, 26.dp, dim = true)
                    Text(
                        row.name,
                        color = Color.White,
                        fontSize = 26.sp,
                        lineHeight = 32.sp,
                        fontWeight = ExtraBold,
                        letterSpacing = (-0.02).em,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val total = view?.total ?: row.total
                val place = view?.position?.let { stringResource(R.string.queue_removed_place, it, total) }
                    ?: pluralStringResource(R.plurals.queue_tracks, total, total)
                Text(
                    removedAccountName?.let { stringResource(R.string.queue_removed_open_subtitle_named, it, place) }
                        ?: stringResource(R.string.queue_removed_open_subtitle, place),
                    color = HiFi.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            GlassPillButton(text = stringResource(R.string.queue_switcher_title), onClick = onQueues, icon = Icons.AutoMirrored.Rounded.QueueMusic, height = 44.dp)
        }
        val unavailable = stringResource(R.string.queue_track_unavailable)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp)) {
            item(key = "next") {
                Text(
                    stringResource(R.string.queue_removed_next).uppercase(),
                    color = HiFi.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.06.em,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                )
            }
            itemsIndexed(view?.next.orEmpty(), key = { index, _ -> index }) { _, track ->
                RemovedTrackRow(track, unavailable)
            }
        }
        Text(
            removedAccountName?.let { stringResource(R.string.queue_removed_open_hint_named, it) } ?: stringResource(R.string.queue_removed_open_hint),
            color = HiFi.TextTertiary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp)
        )
    }
}

/** A track of a removed account's queue: dimmed, "unavailable", no action (D22). */
@Composable
private fun RemovedTrackRow(track: RemovedQueueView.Track, unavailable: String) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().height(60.dp).alpha(0.45f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(44.dp).clip(shape).background(HiFi.Placeholder).border(1.dp, HiFi.Hairline, shape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = HiFi.TextTertiary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(track.artist, unavailable).filter { it.isNotBlank() }.joinToString(" · "),
                color = HiFi.TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
