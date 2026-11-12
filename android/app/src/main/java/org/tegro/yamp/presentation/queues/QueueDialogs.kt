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
import org.tegro.yamp.presentation.hifi.PanelSide
import org.tegro.yamp.presentation.main.components.ContextMenuItem

private const val MAX_NAME_LENGTH = 60

/** "New queue" and "Rename": a name field; [hint] is the note under it (the canvas `PhoneQueueNew`). */
@Composable
internal fun QueueNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String = "",
    hint: String? = null,
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
 * (not editable, one per account) offers Clear only. The canvas `PhoneQueueMenu`.
 */
@Composable
internal fun QueueMenuSheet(
    row: QueueRowState,
    side: PanelSide,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    HiFiSheetPanel(onDismiss = onDismiss, side = side, sheetHeight = null) {
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
        ContextMenuItem(Icons.Rounded.PlaylistRemove, stringResource(R.string.queue_clear), onClick = onClear)
        if (row.editable) {
            ContextMenuItem(Icons.Rounded.Delete, stringResource(R.string.queue_delete), iconTint = HiFi.Danger, textColor = HiFi.Danger, onClick = onDelete)
        }
        Spacer(Modifier.height(16.dp))
    }
}
