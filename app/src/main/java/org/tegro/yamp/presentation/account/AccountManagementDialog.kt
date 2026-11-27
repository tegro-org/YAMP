package org.tegro.yamp.presentation.account

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.TextButton
import org.tegro.yamp.presentation.hifi.HiFiDialogDefaults
import org.tegro.yamp.presentation.hifi.HiFiDialogActionKind
import org.tegro.yamp.presentation.hifi.HiFiDialogAction
import org.tegro.yamp.presentation.hifi.HiFiAlertDialog
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import org.tegro.yamp.presentation.hifi.AppDialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import org.tegro.yamp.domain.model.UserAccount

import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder

@Composable
fun AccountManagementDialog(
    accounts: List<UserAccount>,
    defaultAccountUid: String? = null,
    onSetDefaultAccount: (String?) -> Unit = {},
    onRemoveAccount: (String) -> Unit,
    onAddNewAccount: () -> Unit,
    onLogoutCurrent: () -> Unit,
    onLogoutAll: () -> Unit,
    onDismiss: () -> Unit,
    debugActions: AccountDebugActions? = null,
    jellyfinAccounts: List<JellyfinAccountRow> = emptyList(),
    onRemoveJellyfin: (String) -> Unit = {},
    /** Quality per account (canvas `qa-*`): a tap on an account opens its sheet; null hides it. */
    quality: AccountQualityUi? = null,
    /** The accounts' keys in the user's order (D90); empty: This device, Jellyfin, then Yandex, as given. */
    order: List<String> = emptyList(),
    /** A drop after a drag, or Move up / down: the account's key and its new place in the list. */
    onMove: (key: String, index: Int) -> Unit = { _, _ -> },
    /** The head unit's hand side: the ≡ handle and the row menu sit on the wheel side (the phone: ≡ leading). */
    wheelLeft: Boolean = true,
    /** "This device" while it exists (D92); a tap on its card opens its Sources. */
    local: LocalAccountRow? = null,
    onOpenLocal: () -> Unit = {},
    /** Developer option on (D101): an account without Plus carries a tag instead of the warning. */
    allowWithoutPlus: Boolean = false
) {
    // The account whose quality sheet is open: (account key, name, is Jellyfin)
    var qualityFor by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    val big = with(androidx.compose.ui.platform.LocalDensity.current) {
        org.tegro.yamp.utils.currentWindowSizePx().width.toDp().value >= HEAD_UNIT_MIN_WIDTH_DP
    }
    if (quality != null) qualityFor?.let { (key, name, jellyfin) ->
        AccountQualitySheet(name, key, jellyfin, quality, big, onDismiss = { qualityFor = null })
    }
    var confirmLogoutCurrent by remember { mutableStateOf(false) }
    var confirmLogoutAll by remember { mutableStateOf(false) }
    var accountToDelete by remember { mutableStateOf<UserAccount?>(null) }
    var jellyfinToDelete by remember { mutableStateOf<JellyfinAccountRow?>(null) }
    // The account whose row menu is open (⋮ or hold)
    var menuFor by remember { mutableStateOf<String?>(null) }
    // Several accounts are all live (D56): no "active" one to switch to; each can be removed on its own
    val multi = accounts.size > 1

    AppDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    // A margin above and below: on a 480 dp tall head unit the dialog would touch the edges
                    .padding(vertical = 16.dp)
                    .fillMaxWidth(0.92f)
                    .wrapContentHeight()
                    .clickable(enabled = false) {}
                    .clip(RoundedCornerShape(28.dp))
                    .border(
                        BorderStroke(1.dp, Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f))
                        )),
                        RoundedCornerShape(28.dp)
                    ),
                color = HiFiDialogDefaults.Container,
                tonalElevation = 0.dp
            ) {
                // One scroll for the whole dialog: the account groups are plain sections, so a short screen
                // (the head unit) reaches the Jellyfin group and the buttons below the Yandex one
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(22.dp)
                ) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.account_management_upravlenie_akkauntami),
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = stringResource(if (multi) R.string.account_management_all_live else R.string.account_management_pereklyuchenie_profiley_i_nastroyki),
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.07f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.cd_zakryt),
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // One flat list in the user's order (D90, canvas `ac-order`): a service badge on every row,
                    // ≡ to drag and ⋮ / hold for Move up / down, Make default and Remove
                    val entries = accountEntries(accounts, jellyfinAccounts, order, defaultAccountUid, local)
                    if (entries.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.account_saved_accounts_header, entries.size),
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        AccountOrderList(
                            entries = entries,
                            big = big,
                            handleOnLeft = !big || wheelLeft,
                            quality = quality,
                            onOpen = { entry ->
                                if (entry.local != null) onOpenLocal()
                                else qualityFor = Triple(entry.key, entry.name, entry.provider == org.tegro.yamp.domain.account.Provider.JELLYFIN)
                            },
                            onMenu = { menuFor = it.key },
                            onMove = onMove,
                            allowWithoutPlus = allowWithoutPlus
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (accounts.isNotEmpty()) Text(
                            text = stringResource(R.string.account_management_profil_po_umolchaniyu_avto),
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        if (entries.size > 1) Text(
                            text = stringResource(R.string.account_order_hint),
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = if (big) 15.sp else 11.sp,
                            lineHeight = if (big) 20.sp else 15.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }

                    if (quality != null) {
                        Text(
                            text = stringResource(R.string.quality_tap_account) + if (local != null) " " + stringResource(R.string.local_open_sources) else "",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                        )
                    }

                    debugActions?.let { AccountDebugRows(accounts, it) }

                    Spacer(modifier = Modifier.height(14.dp))

                    // "+ Войти в другой аккаунт" Button
                    Button(
                        onClick = {
                            onAddNewAccount()
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.08f)
                        ),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            // "Add account": it also adds "This device" now, not only a sign-in (decided after the boards)
                            text = stringResource(R.string.add_account_title),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // "Выйти из этого аккаунта": with several accounts each row has its own Remove (D56)
                    if (!multi) Button(
                        onClick = { confirmLogoutCurrent = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0x22FF5252)
                        ),
                        border = BorderStroke(1.dp, Color(0x55FF5252))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            tint = HiFi.Danger,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.account_management_vyyti_iz_etogo_akkaunta),
                            color = HiFi.Danger,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (accounts.size > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { confirmLogoutAll = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.account_management_vyyti_so_vseh_akkauntov),
                                color = Color.White.copy(alpha = 0.45f),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { key ->
        val entries = accountEntries(accounts, jellyfinAccounts, order, defaultAccountUid, local)
        val at = entries.indexOfFirst { it.key == key }
        if (at < 0) {
            menuFor = null
        } else {
            val entry = entries[at]
            AccountRowMenu(
                entry = entry,
                index = at,
                count = entries.size,
                big = big,
                side = if (wheelLeft) org.tegro.yamp.presentation.hifi.PanelSide.LEFT else org.tegro.yamp.presentation.hifi.PanelSide.RIGHT,
                onMove = { to -> onMove(key, to) },
                onToggleDefault = entry.yandex?.let { account -> { onSetDefaultAccount(if (entry.isDefault) null else account.uid) } },
                onRemove = if (entry.local != null) null else ({
                    entry.yandex?.let { account -> if (multi) accountToDelete = account else confirmLogoutCurrent = true }
                    entry.jellyfin?.let { jellyfinToDelete = it }
                }),
                onDismiss = { menuFor = null }
            )
        }
    }

    val yandexUids = accounts.map { it.uid }
    val sourcesLeft = jellyfinAccounts.size + if (local != null) 1 else 0
    val allLeave = leavesNoSource(yandexUids, yandexUids.toSet(), sourcesLeft)

    // Confirmation: Logout Current Account
    if (confirmLogoutCurrent) {
        HiFiAlertDialog(
            onDismissRequest = { confirmLogoutCurrent = false },
            title = stringResource(R.string.account_management_vyyti_iz_akkaunta),
            content = { ConfirmText(stringResource(R.string.account_management_dannye_i_kesh_tekuschego), allLeave) },
            confirm = { HiFiDialogAction(stringResource(R.string.account_management_vyyti), { confirmLogoutCurrent = false; onLogoutCurrent(); onDismiss() }, HiFiDialogActionKind.Danger) },
            dismiss = { HiFiDialogAction(stringResource(R.string.account_management_otmena), { confirmLogoutCurrent = false }, HiFiDialogActionKind.Quiet) }
        )
    }

    // Confirmation: Logout All Accounts
    if (confirmLogoutAll) {
        HiFiAlertDialog(
            onDismissRequest = { confirmLogoutAll = false },
            title = stringResource(R.string.account_management_vyyti_so_vseh_akkauntov_2),
            content = { ConfirmText(stringResource(R.string.account_management_vse_sohranennye_uchetnye_zapisi), allLeave) },
            confirm = { HiFiDialogAction(stringResource(R.string.account_management_vyyti_so_vseh), { confirmLogoutAll = false; onLogoutAll(); onDismiss() }, HiFiDialogActionKind.Danger) },
            dismiss = { HiFiDialogAction(stringResource(R.string.account_management_otmena), { confirmLogoutAll = false }, HiFiDialogActionKind.Quiet) }
        )
    }

    // Confirmation: Remove Individual Account
    accountToDelete?.let { target ->
        HiFiAlertDialog(
            onDismissRequest = { accountToDelete = null },
            title = stringResource(R.string.account_management_udalit_profil),
            content = {
                ConfirmText(
                    stringResource(R.string.account_remove_confirm, target.displayName.ifBlank { target.login }.ifBlank { target.uid }),
                    leavesNoSource(yandexUids, setOf(target.uid), sourcesLeft)
                )
            },
            confirm = { HiFiDialogAction(stringResource(R.string.account_management_udalit), { val uidToRemove = target.uid; accountToDelete = null; onRemoveAccount(uidToRemove) }, HiFiDialogActionKind.Danger) },
            dismiss = { HiFiDialogAction(stringResource(R.string.account_management_otmena), { accountToDelete = null }, HiFiDialogActionKind.Quiet) }
        )
    }
    jellyfinToDelete?.let { target ->
        HiFiAlertDialog(
            onDismissRequest = { jellyfinToDelete = null },
            title = stringResource(R.string.account_management_udalit_profil),
            content = { Text(stringResource(R.string.jellyfin_remove_confirm, target.tag.name), color = HiFi.TextSecondary, fontSize = 15.sp) },
            confirm = { HiFiDialogAction(stringResource(R.string.account_management_udalit), { jellyfinToDelete = null; onRemoveJellyfin(target.tag.key) }, HiFiDialogActionKind.Danger) },
            dismiss = { HiFiDialogAction(stringResource(R.string.account_management_otmena), { jellyfinToDelete = null }, HiFiDialogActionKind.Quiet) }
        )
    }
}

/**
 * Signing out of / removing the [leaving] Yandex accounts of [yandex] would leave no source at all: no staying Yandex
 * account, no Jellyfin server and no "This device" ([otherSources] counts those). The app then shows the first-start
 * screen, which does not offer Yandex (D100), so the confirmation says so.
 */
internal fun leavesNoSource(yandex: List<String>, leaving: Set<String>, otherSources: Int): Boolean =
    otherSources == 0 && yandex.isNotEmpty() && yandex.all { it in leaving }

/** A confirmation's text, with the "this is your only source" line when [leavesNone] (canvas `wl-signout-last-source`). */
@Composable
private fun ConfirmText(text: String, leavesNone: Boolean) {
    Column {
        Text(text, color = HiFi.TextSecondary, fontSize = 15.sp)
        if (leavesNone) {
            Text(stringResource(R.string.account_leaves_no_source), color = HiFi.TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** At least this wide (dp): the head unit's layout — bigger quality switches (the user asked). */
internal const val HEAD_UNIT_MIN_WIDTH_DP = 900

/** The window is wide enough for the head unit's layout ([HEAD_UNIT_MIN_WIDTH_DP]). */
@Composable
internal fun isHeadUnitWidth(): Boolean = with(androidx.compose.ui.platform.LocalDensity.current) {
    org.tegro.yamp.utils.currentWindowSizePx().width.toDp().value >= HEAD_UNIT_MIN_WIDTH_DP
}

// Debug: test sign-out without losing credentials
/** Debug builds only: hide an account the way a sign-out does, keeping its token, and bring it back. */
class AccountDebugActions(
    val hidden: List<UserAccount>,
    val onHide: (String) -> Unit,
    val onUnhide: (String) -> Unit
)

@Composable
private fun AccountDebugRows(accounts: List<UserAccount>, actions: AccountDebugActions) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        accounts.forEach { account ->
            TextButton(onClick = { actions.onHide(account.uid) }) {
                Text(
                    text = stringResource(R.string.debug_account_hide, account.displayName.ifBlank { account.login }),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }
        actions.hidden.forEach { account ->
            TextButton(onClick = { actions.onUnhide(account.uid) }) {
                Text(
                    text = stringResource(R.string.debug_account_unhide, account.displayName.ifBlank { account.login }),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }
    }
}
