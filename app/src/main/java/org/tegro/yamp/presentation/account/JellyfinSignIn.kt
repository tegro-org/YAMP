package org.tegro.yamp.presentation.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.tegro.yamp.R
import org.tegro.yamp.presentation.hifi.AppDialog
import org.tegro.yamp.presentation.hifi.GlassPillButton
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiAlertDialog
import org.tegro.yamp.presentation.hifi.HiFiDialogAction
import org.tegro.yamp.presentation.hifi.HiFiDialogActionKind
import org.tegro.yamp.presentation.hifi.HiFiDialogDefaults
import org.tegro.yamp.presentation.hifi.HiFiSegmented
import org.tegro.yamp.presentation.hifi.MetalPillButton
import org.tegro.yamp.provider.jellyfin.ServerCheck
import org.tegro.yamp.presentation.account.JellyfinSignInUiState.AddressCheck
import org.tegro.yamp.presentation.account.JellyfinSignInUiState.Way

/** A Jellyfin account as Settings → Accounts lists it: its marker, "user · server", and the server's version. */
@Immutable
data class JellyfinAccountRow(val tag: AccountTag, val sub: String)

/** "Add account": where the music is — This device, Jellyfin, Yandex Music, in that order (D102; canvas `jf-add`, `lo-add-chooser`). */
@Composable
fun AddAccountChooser(onYandex: () -> Unit, onJellyfin: () -> Unit, onDismiss: () -> Unit, onLocal: () -> Unit) {
    AppDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(min = 280.dp, max = 560.dp)
                .clip(HiFiDialogDefaults.Shape)
                .background(HiFiDialogDefaults.Container)
                .border(1.dp, HiFiDialogDefaults.Rim, HiFiDialogDefaults.Shape)
                .padding(24.dp)
        ) {
            Text(stringResource(R.string.add_account_title), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            Text(stringResource(R.string.add_account_where), color = HiFi.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            // A folder on the device (Phase 4d, canvas `lo-add-chooser`): no account, the pseudo-account "This device"
            ChooserRow(Icons.Default.FolderOpen, stringResource(R.string.local_device_name), stringResource(R.string.add_account_local_sub), onLocal)
            ChooserRow(Icons.Default.Storage, "Jellyfin", stringResource(R.string.add_account_jellyfin_sub), onJellyfin)
            ChooserRow(Icons.Default.MusicNote, stringResource(R.string.add_account_yandex), stringResource(R.string.add_account_yandex_sub), onYandex)
            Text(stringResource(R.string.add_account_note), color = HiFi.TextTertiary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun ChooserRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = HiFi.TextSecondary, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = HiFi.TextTertiary)
    }
}

/** The sign-in sheet with its view model; closes itself once an account is added. */
@Composable
fun JellyfinSignInHost(onDismiss: () -> Unit) {
    val vm: JellyfinSignInViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.reset() }
    LaunchedEffect(state.done) { if (state.done) onDismiss() }
    JellyfinSignInSheet(
        state = state,
        actions = JellyfinSignInActions(
            onAddress = vm::setAddress, onCheck = vm::check, onWay = vm::setWay, onUser = vm::setUser,
            onPassword = vm::setPassword, onSignIn = vm::signInWithPassword, onGetCode = vm::startQuickConnect,
            onCancelCode = vm::cancelQuickConnect, onDismissDuplicate = vm::dismissDuplicate
        ),
        onDismiss = onDismiss
    )
}

class JellyfinSignInActions(
    val onAddress: (String) -> Unit = {},
    val onCheck: () -> Unit = {},
    val onWay: (Way) -> Unit = {},
    val onUser: (String) -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onSignIn: () -> Unit = {},
    val onGetCode: () -> Unit = {},
    val onCancelCode: () -> Unit = {},
    val onDismissDuplicate: () -> Unit = {}
)

/** Stateless, for the screenshots: the address with its check, then Quick Connect or name + password. */
@Composable
fun JellyfinSignInSheet(state: JellyfinSignInUiState, actions: JellyfinSignInActions, onDismiss: () -> Unit) {
    AppDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(HiFiDialogDefaults.Container)
                .border(1.dp, HiFiDialogDefaults.Rim, RoundedCornerShape(28.dp))
                .verticalScroll(rememberScrollState())
                .padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF8E6FD8)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Storage, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Text("Jellyfin", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f).padding(start = 12.dp))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.cd_zakryt), tint = Color.White) }
            }
            Text(stringResource(R.string.jellyfin_intro), color = HiFi.TextSecondary, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            val bad = state.check is AddressCheck.Bad || state.check == AddressCheck.Unreachable || state.check == AddressCheck.NotJellyfin
            OutlinedTextField(
                value = state.address,
                onValueChange = actions.onAddress,
                singleLine = true,
                enabled = state.code == null && !state.busy,
                isError = bad,
                shape = RoundedCornerShape(16.dp),
                label = { Text(stringResource(R.string.jellyfin_server)) },
                placeholder = { Text("https://music.example.org") },
                leadingIcon = { Icon(Icons.Default.Dns, null) },
                trailingIcon = { if (state.found != null) Icon(Icons.Default.CheckCircle, null, tint = HiFi.Success) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = HiFiDialogDefaults.textFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            ServerStatus(state.check)
            when (val check = state.check) {
                AddressCheck.Idle -> MetalPillButton(stringResource(R.string.jellyfin_check), actions.onCheck, Modifier.fillMaxWidth().padding(top = 18.dp), enabled = state.address.isNotBlank())
                AddressCheck.Checking -> Unit
                is AddressCheck.Found -> SignInWays(state, check, actions)
                else -> {
                    GlassPillButton(stringResource(R.string.jellyfin_check_again), actions.onCheck, Modifier.fillMaxWidth().padding(top = 18.dp), icon = Icons.Default.Refresh)
                    if (check == AddressCheck.NotJellyfin) Hint(stringResource(R.string.jellyfin_not_jellyfin_hint))
                }
            }
            state.error?.let { error ->
                val text = when (error) {
                    JellyfinSignInUiState.Failure.WRONG_CREDENTIALS -> R.string.jellyfin_error_credentials
                    JellyfinSignInUiState.Failure.CODE_EXPIRED -> R.string.jellyfin_error_code
                    JellyfinSignInUiState.Failure.UNREACHABLE -> R.string.jellyfin_unreachable
                }
                StatusLine(Icons.Default.Error, stringResource(text), HiFi.Danger)
            }
        }
    }
    state.duplicate?.let { label ->
        HiFiAlertDialog(
            onDismissRequest = actions.onDismissDuplicate,
            title = stringResource(R.string.jellyfin_duplicate_title),
            content = { Text(stringResource(R.string.jellyfin_duplicate_text, label), color = HiFi.TextSecondary, fontSize = 15.sp) },
            confirm = { HiFiDialogAction(stringResource(R.string.jellyfin_duplicate_ok), { actions.onDismissDuplicate(); onDismiss() }) },
            dismiss = { HiFiDialogAction(stringResource(R.string.account_management_otmena), actions.onDismissDuplicate, HiFiDialogActionKind.Quiet) }
        )
    }
}

@Composable
private fun ServerStatus(check: AddressCheck) {
    when (check) {
        AddressCheck.Idle -> Unit
        AddressCheck.Checking -> Row(Modifier.padding(top = 10.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = HiFi.TextSecondary, strokeWidth = 2.dp)
            Text(stringResource(R.string.jellyfin_checking), color = HiFi.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        }
        is AddressCheck.Found -> StatusLine(Icons.Default.CheckCircle, listOf(check.name, check.version.takeIf { it.isNotBlank() }?.let { "Jellyfin $it" }).filterNotNull().joinToString(" · "), HiFi.Success, textColor = Color.White)
        AddressCheck.Unreachable -> StatusLine(Icons.Default.Error, stringResource(R.string.jellyfin_unreachable), HiFi.Danger)
        AddressCheck.NotJellyfin -> StatusLine(Icons.Default.Error, stringResource(R.string.jellyfin_not_jellyfin), HiFi.Danger)
        is AddressCheck.Bad -> StatusLine(
            Icons.Default.Error,
            stringResource(
                when (check.reason) {
                    ServerCheck.BadAddress.Reason.NOT_HTTPS -> R.string.jellyfin_https_only
                    else -> R.string.jellyfin_bad_address
                }
            ),
            HiFi.Danger
        )
    }
}

@Composable
private fun StatusLine(icon: ImageVector, text: String, tint: Color, textColor: Color = tint) {
    Row(Modifier.padding(top = 10.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text, color = textColor, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, color = HiFi.TextTertiary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp, start = 4.dp))

@Composable
private fun SignInWays(state: JellyfinSignInUiState, found: AddressCheck.Found, actions: JellyfinSignInActions) {
    val ways = listOf(Way.QUICK_CONNECT, Way.PASSWORD)
    if (found.quickConnect) {
        HiFiSegmented(
            options = listOf(stringResource(R.string.jellyfin_quick_connect), stringResource(R.string.jellyfin_name_password)),
            selectedIndex = ways.indexOf(state.way),
            onSelect = { actions.onWay(ways[it]) },
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
        )
    }
    if (state.way == Way.QUICK_CONNECT && found.quickConnect) QuickConnect(state, actions) else Password(state, actions)
}

@Composable
private fun QuickConnect(state: JellyfinSignInUiState, actions: JellyfinSignInActions) {
    val code = state.code
    if (code == null) {
        Hint(stringResource(R.string.jellyfin_qc_available))
        MetalPillButton(stringResource(R.string.jellyfin_get_code), actions.onGetCode, Modifier.fillMaxWidth().padding(top = 18.dp), enabled = !state.busy)
        return
    }
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.Center) {
        code.forEachIndexed { i, digit ->
            if (i == code.length / 2) Spacer(Modifier.width(16.dp))
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(width = 44.dp, height = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) { Text(digit.toString(), color = if (state.approved) HiFi.TextTertiary else Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold) }
        }
    }
    if (state.approved) {
        StatusLine(Icons.Default.CheckCircle, stringResource(R.string.jellyfin_qc_confirmed), HiFi.Success, textColor = Color.White)
        Row(Modifier.padding(top = 10.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = HiFi.TextSecondary, strokeWidth = 2.dp)
            Text(stringResource(R.string.jellyfin_signing_in), color = HiFi.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        }
    } else {
        Hint(stringResource(R.string.jellyfin_qc_how))
        Row(Modifier.padding(top = 10.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = HiFi.TextSecondary, strokeWidth = 2.dp)
            Text(stringResource(R.string.jellyfin_qc_waiting), color = HiFi.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
            Text(stringResource(R.string.account_management_otmena), color = HiFi.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = actions.onCancelCode).padding(8.dp))
        }
    }
    Hint(stringResource(R.string.jellyfin_qc_token))
}

@Composable
private fun Password(state: JellyfinSignInUiState, actions: JellyfinSignInActions) {
    OutlinedTextField(
        value = state.user, onValueChange = actions.onUser, singleLine = true, enabled = !state.busy,
        shape = RoundedCornerShape(16.dp), label = { Text(stringResource(R.string.jellyfin_user)) },
        colors = HiFiDialogDefaults.textFieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
    )
    OutlinedTextField(
        value = state.password, onValueChange = actions.onPassword, singleLine = true, enabled = !state.busy,
        shape = RoundedCornerShape(16.dp), label = { Text(stringResource(R.string.jellyfin_password)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = state.error == JellyfinSignInUiState.Failure.WRONG_CREDENTIALS,
        colors = HiFiDialogDefaults.textFieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
    )
    MetalPillButton(
        if (state.busy) stringResource(R.string.jellyfin_signing_in) else stringResource(R.string.jellyfin_sign_in),
        actions.onSignIn, Modifier.fillMaxWidth().padding(top = 18.dp), enabled = state.canSignIn
    )
}
