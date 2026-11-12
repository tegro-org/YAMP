package org.tegro.yamp.presentation.queues

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.tegro.yamp.R
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.presentation.hifi.PanelSide

/** Why the switcher opens. The car asks for the big panel on the driver's side. */
sealed interface QueueSwitcherRequest {
    data class Switch(val car: Boolean = false, val side: PanelSide = PanelSide.RIGHT) : QueueSwitcherRequest

    /** "Add to queue…" for [tracks]; [subtitle] names what is being added (a track, an album). */
    data class AddTo(val tracks: List<TrackDetailsDto>, val subtitle: String) : QueueSwitcherRequest
}

/** Opens the queue switcher from anywhere in the app; MainScreen provides it (the `LocalTrackMenu` precedent). */
fun interface QueueSwitcherLauncher {
    fun open(request: QueueSwitcherRequest)
}

val LocalQueueSwitcher = staticCompositionLocalOf<QueueSwitcherLauncher?> { null }

/** What the switcher has open on top of its list. */
private sealed interface QueueDialog {
    data object New : QueueDialog
    data class Menu(val row: QueueRowState) : QueueDialog
    data class Rename(val row: QueueRowState) : QueueDialog
    data class Delete(val row: QueueRowState) : QueueDialog
}

/**
 * The queue switcher and the "Add to queue…" picker, drawn once in MainScreen. The open request lives in the
 * ViewModel, so a turn of the phone re-draws the sheet as a panel with nothing lost.
 */
@Composable
fun QueueSwitcherHost(viewModel: QueueSwitcherViewModel, snackbar: SnackbarHostState) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val request = state.mode ?: return
    var dialog by remember(request) { mutableStateOf<QueueDialog?>(null) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val adding = request as? QueueSwitcherRequest.AddTo
    val car = (request as? QueueSwitcherRequest.Switch)?.car == true
    val side = (request as? QueueSwitcherRequest.Switch)?.side ?: PanelSide.RIGHT
    val addedTo = { name: String -> scope.launch { snackbar.showSnackbar(resources.getString(R.string.queue_added_to, name)) } }
    val defaultName = stringResource(R.string.queue_default_name, state.queues.size + 1)

    QueueSwitcherSheet(
        state = state,
        car = car,
        side = side,
        onDismiss = viewModel::close,
        onSelect = { row ->
            if (adding != null) {
                viewModel.addTo(row.id)
                addedTo(row.name)
            } else {
                viewModel.switchTo(row.id)
            }
            viewModel.close()
        },
        onLongPress = if (car) null else ({ row -> dialog = QueueDialog.Menu(row) }),
        onNew = {
            // In the car nothing is typed (D32): the queue is named on the spot and renamed on the phone
            if (car) viewModel.create(defaultName) else dialog = QueueDialog.New
        }
    )

    when (val open = dialog) {
        null -> Unit
        QueueDialog.New -> QueueNameDialog(
            title = stringResource(R.string.queue_new),
            confirmLabel = stringResource(R.string.queue_create),
            hint = if (adding == null) stringResource(R.string.queue_new_hint) else null,
            onConfirm = { name ->
                if (adding != null) {
                    viewModel.createAndAdd(name)
                    addedTo(name)
                    viewModel.close()
                } else {
                    viewModel.create(name)
                }
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        is QueueDialog.Menu -> {
            val copyName = stringResource(R.string.queue_duplicate_name, open.row.name)
            QueueMenuSheet(
                row = open.row,
                side = side,
                onRename = { dialog = QueueDialog.Rename(open.row) },
                onDuplicate = {
                    viewModel.duplicate(open.row.id, copyName)
                    dialog = null
                },
                onClear = {
                    viewModel.clear(open.row.id)
                    dialog = null
                },
                onDelete = { dialog = QueueDialog.Delete(open.row) },
                onDismiss = { dialog = null }
            )
        }
        is QueueDialog.Rename -> QueueNameDialog(
            title = stringResource(R.string.queue_rename),
            confirmLabel = stringResource(R.string.queue_rename),
            initialName = open.row.name,
            onConfirm = { name ->
                viewModel.rename(open.row.id, name)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        is QueueDialog.Delete -> DeleteQueueDialog(
            name = open.row.name,
            trackCount = open.row.total,
            onConfirm = {
                viewModel.delete(open.row.id)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
    }
}
