package org.tegro.yamp.presentation.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.tegro.yamp.R
import org.tegro.yamp.presentation.account.AccountTag
import org.tegro.yamp.presentation.common.PlusBlock
import org.tegro.yamp.presentation.common.PlusPage
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiAlertDialog
import org.tegro.yamp.presentation.hifi.HiFiDialogAction
import org.tegro.yamp.presentation.hifi.HiFiDialogActionKind

/**
 * The Plus gate's face (D101): the block the player and the radio read ([org.tegro.yamp.presentation.common.LocalPlusBlock])
 * while the active queue's account is refused — null otherwise — and "Get a subscription" opened in the browser.
 */
@Composable
internal fun rememberPlusBlock(
    blocked: Set<String>,
    activeAccount: String?,
    tags: List<AccountTag>,
    onQueues: () -> Unit,
    onAddSource: () -> Unit,
    onNoBrowser: () -> Unit
): PlusBlock? {
    val context = LocalContext.current
    val account = activeAccount?.takeIf { it in blocked } ?: return null
    val queues by rememberUpdatedState(onQueues)
    val addSource by rememberUpdatedState(onAddSource)
    val noBrowser by rememberUpdatedState(onNoBrowser)
    val name = tags.firstOrNull { it.key == account }?.name.orEmpty()
    // Another queue to play instead: any signed-in account that is not refused
    val canSwitch = tags.any { it.key !in blocked }
    return remember(account, name, canSwitch) {
        PlusBlock(name, canSwitch, onSubscribe = { if (!PlusPage.open(context)) noBrowser() }, onQueues = { queues() }, onAddSource = { addSource() })
    }
}

/**
 * Plus messages over the app: the message bar when a start was refused (with the way out: the queues, or adding a
 * source when there is nothing else to play), and the one-time notice after a sign-in of an account without Plus.
 */
@Composable
internal fun PlusHost(viewModel: MainViewModel, block: PlusBlock?, snackbar: SnackbarHostState) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val current by rememberUpdatedState(block)
    val resources = androidx.compose.ui.platform.LocalResources.current
    val queuesLabel = stringResource(R.string.plus_queues)
    val addLabel = stringResource(R.string.plus_add_source)
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.plusRefused.collect { name ->
                val canSwitch = current?.canSwitch ?: true
                scope.launch {
                    val result = snackbar.showSnackbar(
                        message = resources.getString(R.string.msg_plus_missing, name),
                        actionLabel = if (canSwitch) queuesLabel else addLabel,
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        current?.let { if (canSwitch) it.onQueues() else it.onAddSource() }
                    }
                }
            }
        }
    }
    val notice by viewModel.plusNotice.collectAsStateWithLifecycle()
    notice?.let { name ->
        PlusNoticeDialog(
            name = name,
            onSubscribe = { if (!PlusPage.open(context)) android.widget.Toast.makeText(context, R.string.plus_no_browser, android.widget.Toast.LENGTH_LONG).show() },
            onDismiss = viewModel::dismissPlusNotice
        )
    }
}

/** A sign-in of an account without Plus is allowed; this says what will not play and that nothing is lost (canvas `px-signin`). */
@Composable
internal fun PlusNoticeDialog(name: String, onSubscribe: () -> Unit, onDismiss: () -> Unit) {
    HiFiAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.plus_notice_title, name),
        content = {
            Row(
                Modifier.fillMaxWidth().background(HiFi.Warning.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .border(1.dp, HiFi.Warning.copy(alpha = 0.40f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Rounded.Block, null, tint = HiFi.Warning, modifier = Modifier.size(20.dp))
                Column {
                    Text(stringResource(R.string.plus_missing), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.plus_notice_text), color = HiFi.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        },
        confirm = { HiFiDialogAction(stringResource(R.string.plus_notice_ok), onDismiss, HiFiDialogActionKind.Primary) },
        dismiss = { HiFiDialogAction(stringResource(R.string.plus_subscribe), onSubscribe, HiFiDialogActionKind.Quiet) }
    )
}
