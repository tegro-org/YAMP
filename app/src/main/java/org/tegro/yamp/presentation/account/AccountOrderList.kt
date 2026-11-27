package org.tegro.yamp.presentation.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.UsbOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.SubcomposeAsyncImage
import org.tegro.yamp.R
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.presentation.common.ProviderBadge
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiSheetPanel
import org.tegro.yamp.presentation.hifi.PanelSide
import org.tegro.yamp.presentation.main.components.ContextMenuItem

/** One row of Settings → Accounts' flat list (D90, canvas `ac-order`): a Yandex or a Jellyfin account. */
@Immutable
data class AccountEntry(
    /** `AccountId.value`. */
    val key: String,
    val provider: Provider,
    val name: String,
    /** The second line: the Yandex login (when it differs from the name), the Jellyfin server. */
    val sub: String?,
    val isDefault: Boolean = false,
    val yandex: UserAccount? = null,
    val jellyfin: JellyfinAccountRow? = null,
    /** "This device" (D92): its card's lines; its tap opens Sources. */
    val local: LocalAccountRow? = null
)

/** "This device" as Settings → Accounts lists it (canvas `lo-add-result`): "N sources · N tracks", then the scan or a missing stick. */
@Immutable
data class LocalAccountRow(val name: String, val sub: String, val status: String?, val warning: Boolean = false)

/**
 * The accounts of both services as one list in [order] (keys; D90). An account [order] does not hold yet comes after
 * the ordered ones, "This device" before Jellyfin before Yandex, as given: an empty [order] is the default order (D102).
 */
fun accountEntries(
    yandex: List<UserAccount>,
    jellyfin: List<JellyfinAccountRow>,
    order: List<String>,
    defaultUid: String?,
    local: LocalAccountRow? = null
): List<AccountEntry> {
    val yandexEntries = yandex.map { account ->
        val name = account.displayName.ifBlank { account.login }.ifBlank { "UID: ${account.uid}" }
        AccountEntry(
            key = AccountId.yandex(account.uid).value,
            provider = Provider.YANDEX,
            name = name,
            sub = account.login.takeIf { it.isNotBlank() && it != name },
            isDefault = account.uid == defaultUid,
            yandex = account
        )
    }
    val all = listOfNotNull(local?.let { AccountEntry(AccountId.local().value, Provider.LOCAL, it.name, it.sub, local = it) }) +
        jellyfin.map { row -> AccountEntry(row.tag.key, Provider.JELLYFIN, row.tag.name, row.sub, jellyfin = row) } + yandexEntries
    val place = order.withIndex().associate { (i, key) -> key to i }
    return all.withIndex().sortedBy { (i, entry) -> place[entry.key] ?: (order.size + i) }.map { it.value }
}

/** The drag of one row: the shown order, the lifted row and how far it is from its slot. */
private class ReorderDrag(keys: List<String>) {
    var order by mutableStateOf(keys)
    var dragged by mutableStateOf<String?>(null)
    var offset by mutableFloatStateOf(0f)
    val heights = HashMap<String, Int>()

    /** The lifted row moves by [dy]; past half of a neighbour it swaps places with it. */
    fun drag(dy: Float) {
        val key = dragged ?: return
        offset += dy
        val at = order.indexOf(key)
        val neighbour = if (offset > 0) order.getOrNull(at + 1) else order.getOrNull(at - 1)
        val height = neighbour?.let { heights[it] } ?: return
        if (kotlin.math.abs(offset) > height / 2f) {
            val to = if (offset > 0) at + 1 else at - 1
            order = order.toMutableList().apply { add(to, removeAt(at)) }
            offset += if (offset > 0) -height.toFloat() else height.toFloat()
        }
    }
}

/**
 * The flat list (canvas `ac-order`, `ac-order-drag`): every row has its service badge, ⋮ for the menu (hold works
 * too) and — with more than one account — a ≡ handle on [handleOnLeft]'s side to drag it; a drop is saved through
 * [onMove] at once.
 */
@Composable
internal fun AccountOrderList(
    entries: List<AccountEntry>,
    big: Boolean,
    handleOnLeft: Boolean,
    quality: AccountQualityUi?,
    onOpen: (AccountEntry) -> Unit,
    onMenu: (AccountEntry) -> Unit,
    onMove: (key: String, index: Int) -> Unit,
    /** Developer option on (D101): an account without Plus carries the tag instead of the warning. */
    allowWithoutPlus: Boolean = false
) {
    val keys = entries.map { it.key }
    val drag = remember { ReorderDrag(keys) }
    LaunchedEffect(keys) { if (drag.dragged == null) drag.order = keys }
    val currentKeys by rememberUpdatedState(keys)
    val move by rememberUpdatedState(onMove)
    val byKey = entries.associateBy { it.key }
    val reorderable = entries.size > 1
    fun drop() {
        val key = drag.dragged ?: return
        drag.dragged = null
        drag.offset = 0f
        val to = drag.order.indexOf(key)
        if (to >= 0 && to != currentKeys.indexOf(key)) move(key, to)
    }
    Column(Modifier.fillMaxWidth()) {
        drag.order.forEach { rowKey ->
            val entry = byKey[rowKey] ?: return@forEach
            key(rowKey) {
                val lifted = drag.dragged == rowKey
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { drag.heights[rowKey] = it.height }
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer {
                            if (lifted) {
                                translationY = drag.offset
                                scaleX = LIFT_SCALE
                                scaleY = LIFT_SCALE
                            }
                        }
                ) {
                    AccountEntryRow(
                        entry = entry,
                        big = big,
                        handleOnLeft = handleOnLeft,
                        quality = quality,
                        allowWithoutPlus = allowWithoutPlus,
                        lifted = lifted,
                        handle = if (!reorderable) null else {
                            {
                                DragHandle(
                                    big,
                                    Modifier.pointerInput(rowKey) {
                                        detectVerticalDragGestures(
                                            onDragStart = { drag.dragged = rowKey; drag.offset = 0f },
                                            onDragEnd = { drop() },
                                            onDragCancel = { drop() }
                                        ) { change, dy ->
                                            change.consume()
                                            drag.drag(dy)
                                        }
                                    }
                                )
                            }
                        },
                        onOpen = { onOpen(entry) },
                        onMenu = { onMenu(entry) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountEntryRow(
    entry: AccountEntry,
    big: Boolean,
    handleOnLeft: Boolean,
    quality: AccountQualityUi?,
    allowWithoutPlus: Boolean,
    lifted: Boolean,
    /** The ≡ handle with its gesture; null: no handle (one account). */
    handle: (@Composable () -> Unit)?,
    onOpen: () -> Unit,
    onMenu: () -> Unit
) {
    val shape = RoundedCornerShape(if (big) 20.dp else 16.dp)
    // The right-hand head unit mirrors the row: ≡ and ⋮ change sides, the text aligns to the wheel (canvas `ac-order-car-r`)
    CompositionLocalProvider(LocalLayoutDirection provides if (handleOnLeft) LayoutDirection.Ltr else LayoutDirection.Rtl) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(shape)
                .background(
                    if (lifted) Brush.verticalGradient(listOf(HiFi.AnodizedLight, HiFi.AnodizedDark))
                    else Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.03f)))
                )
                .border(BorderStroke(1.dp, if (lifted) Color(0x80C9CED8) else Color.White.copy(alpha = 0.08f)), shape)
                .combinedClickable(onClick = { if (quality != null || entry.local != null) onOpen() }, onLongClick = onMenu)
                .padding(start = if (handle != null) 2.dp else 14.dp, end = 2.dp, top = if (big) 12.dp else 9.dp, bottom = if (big) 12.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            handle?.invoke()
            AccountAvatar(entry, if (big) 52.dp else 40.dp)
            Spacer(Modifier.width(if (big) 16.dp else 12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (big) 8.dp else 6.dp)) {
                    Text(
                        entry.name,
                        color = Color.White,
                        fontSize = if (big) 21.sp else 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    ProviderBadge(entry.provider, size = if (big) 22.dp else 16.dp)
                    if (entry.isDefault) {
                        Icon(Icons.Default.Star, stringResource(R.string.account_is_default), tint = Color.White, modifier = Modifier.size(if (big) 22.dp else 16.dp))
                    }
                }
                entry.sub?.let {
                    Text(it, color = Color.White.copy(alpha = 0.45f), fontSize = if (big) 16.sp else 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                entry.local?.status?.let { status ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (entry.local.warning) Icon(Icons.Rounded.UsbOff, null, tint = HiFi.Warning, modifier = Modifier.size(if (big) 18.dp else 14.dp))
                        else org.tegro.yamp.presentation.hifi.HiFiActivityIndicator(size = if (big) 16.dp else 13.dp)
                        Text(status, color = Color.White.copy(alpha = 0.55f), fontSize = if (big) 16.sp else 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (entry.yandex?.plusMissing == true) {
                    // The Plus gate (D101): the warning (or, with the developer option on, the tag) in place of the quality line
                    if (allowWithoutPlus) org.tegro.yamp.presentation.common.DeveloperTag(Modifier.padding(top = 2.dp))
                    else org.tegro.yamp.presentation.common.PlusMissingLine(fontSize = if (big) 16.sp else 11.5.sp)
                } else if (quality != null && entry.local == null) {
                    Text(
                        qualitySummary(quality, entry.key, jellyfin = entry.provider == Provider.JELLYFIN),
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = if (big) 16.sp else 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onMenu, modifier = Modifier.size(if (big) 56.dp else 40.dp)) {
                Icon(Icons.Rounded.MoreVert, stringResource(R.string.cd_account_menu), tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(if (big) 30.dp else 20.dp))
            }
        }
    }
}

/** The ≡ handle: [modifier] carries the drag. */
@Composable
private fun DragHandle(big: Boolean, modifier: Modifier = Modifier) {
    val drag = stringResource(R.string.cd_account_drag)
    Box(modifier.size(if (big) 56.dp else 40.dp).semantics { contentDescription = drag }, contentAlignment = Alignment.Center) {
        Icon(Icons.Default.DragHandle, contentDescription = null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(if (big) 32.dp else 22.dp))
    }
}

/** The Yandex avatar (its initial on steel without one); a Jellyfin account's marker. */
@Composable
private fun AccountAvatar(entry: AccountEntry, size: androidx.compose.ui.unit.Dp) {
    entry.jellyfin?.let { AccountMark(it.tag, size); return }
    if (entry.local != null) {
        org.tegro.yamp.presentation.sources.LocalDeviceMark(size)
        return
    }
    val initial = (entry.name.firstOrNull() ?: 'Я').uppercaseChar().toString()
    val letter: @Composable () -> Unit = {
        Text(initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(HiFi.SteelLight, HiFi.SteelDark))),
        contentAlignment = Alignment.Center
    ) {
        val url = entry.yandex?.avatarUrl
        if (url.isNullOrBlank()) letter()
        else SubcomposeAsyncImage(
            model = url,
            contentDescription = stringResource(R.string.cd_avatar),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
            error = { letter() }
        )
    }
}

/**
 * The row menu (⋮ or hold; canvas `ac-order-menu`, `ac-order-car-l`): Move up · Move down (only with more than one
 * account; off at the ends) · Make default (Yandex) · Remove. A bottom sheet on the phone, a panel on the wheel side
 * in landscape — the main way to reorder in the car.
 */
@Composable
internal fun AccountRowMenu(
    entry: AccountEntry,
    index: Int,
    count: Int,
    big: Boolean,
    side: PanelSide,
    onMove: (Int) -> Unit,
    /** Null: the account has no default (Jellyfin). */
    onToggleDefault: (() -> Unit)?,
    /** Null: no Remove here ("This device" goes with its last source, in Sources). */
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    HiFiSheetPanel(onDismiss = onDismiss, side = side, sheetHeight = null, panelWidth = if (big) 600.dp else 460.dp) {
        Row(
            Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (big) 16.dp else 12.dp)
        ) {
            AccountAvatar(entry, if (big) 52.dp else 36.dp)
            Column(Modifier.weight(1f, fill = false)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        entry.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (big) 26.sp else 17.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                    )
                    ProviderBadge(entry.provider, size = if (big) 22.dp else 16.dp)
                }
                entry.sub?.let { Text(it, color = HiFi.TextSecondary, fontSize = if (big) 17.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (count > 1) {
            val first = index == 0
            val last = index == count - 1
            ContextMenuItem(
                Icons.Default.ArrowUpward, stringResource(R.string.account_move_up), enabled = !first, big = big,
                note = if (first) stringResource(R.string.account_already_first) else null,
                onClick = { onMove(index - 1) }
            )
            ContextMenuItem(
                Icons.Default.ArrowDownward, stringResource(R.string.account_move_down), enabled = !last, big = big,
                note = if (last) stringResource(R.string.account_already_last) else null,
                onClick = { onMove(index + 1) }
            )
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).height(1.dp).background(HiFi.Hairline))
        }
        onToggleDefault?.let { toggle ->
            ContextMenuItem(
                if (entry.isDefault) Icons.Default.Star else Icons.Default.StarBorder,
                stringResource(if (entry.isDefault) R.string.account_is_default else R.string.account_make_default),
                big = big,
                onClick = { toggle(); onDismiss() }
            )
        }
        onRemove?.let { remove ->
            ContextMenuItem(
                Icons.AutoMirrored.Filled.Logout, stringResource(R.string.account_remove), iconTint = HiFi.Danger, textColor = HiFi.Danger, big = big,
                onClick = { onDismiss(); remove() }
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

private const val LIFT_SCALE = 1.02f
