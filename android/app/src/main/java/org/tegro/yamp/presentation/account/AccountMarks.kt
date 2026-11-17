package org.tegro.yamp.presentation.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.tegro.yamp.domain.account.accountId
import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.domain.account.ProviderAccount
import org.tegro.yamp.domain.account.toProviderAccount
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.hifiPress
import kotlin.math.abs

/**
 * What the UI shows of an account (docs/phase-2a-accounts.md, D58, D64; canvas `accounts.jsx`): the key a queue
 * stores ([key] = `AccountId.value`), its name, the initial and the colour of its marker.
 */
@Immutable
data class AccountTag(val key: String, val name: String, val initial: String, val color: Color)

/** The marker colours: the account's colour stays put while it is signed in (assigned by key, see [accountTags]). */
private val MarkPalette = listOf(
    Color(0xFF5B86DD), Color(0xFFD96C8A), Color(0xFF3FA88A), Color(0xFFD9A13F), Color(0xFF9A6CD9), Color(0xFF4FA3C7)
)

/**
 * The signed-in accounts as tags, in the order of [accounts]. Colours: each account takes the palette slot its key
 * hashes to, the next free one on a clash (keys sorted, so the result does not depend on the list order).
 */
fun accountTags(accounts: List<UserAccount>): List<AccountTag> = providerAccountTags(accounts.map { it.toProviderAccount() })

/** The signed-in accounts of every provider (Yandex and Jellyfin, D78) as tags, in the order of [accounts]. */
fun providerAccountTags(accounts: List<ProviderAccount>): List<AccountTag> {
    val keys = accounts.map { it.id.value }
    val colors = HashMap<String, Color>()
    val taken = HashSet<Int>()
    for (key in keys.sorted()) {
        var slot = abs(key.hashCode() % MarkPalette.size)
        if (taken.size < MarkPalette.size) {
            while (slot in taken) slot = (slot + 1) % MarkPalette.size
        }
        taken += slot
        colors[key] = MarkPalette[slot]
    }
    return accounts.map { account ->
        val name = account.displayName.ifBlank { account.id.id }
        val key = account.id.value
        AccountTag(key, name, name.firstOrNull()?.uppercaseChar()?.toString() ?: "?", colors.getValue(key))
    }
}

/**
 * A signed-out account's tag (D62), from the name kept for it: the colour its key hashes to — no clash with the
 * signed-in ones to resolve, it is shown dimmed among the removed queues only.
 */
fun removedAccountTag(key: String, name: String?): AccountTag {
    val shown = name?.takeIf { it.isNotBlank() } ?: "?"
    return AccountTag(key, shown, shown.first().uppercaseChar().toString(), MarkPalette[abs(key.hashCode() % MarkPalette.size)])
}

/** The account marker: the initial on the account's colour; dimmed for a removed account's queue (D62). */
@Composable
fun AccountMark(tag: AccountTag, size: Dp, modifier: Modifier = Modifier, dim: Boolean = false) {
    Box(
        modifier.size(size).alpha(if (dim) 0.5f else 1f).clip(CircleShape).background(tag.color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            tag.initial,
            color = Color.White,
            fontSize = (size.value * 0.55f).sp,
            lineHeight = (size.value * 0.55f).sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/** The section header of a grouped queue list (D64): marker and the account's name in section caps. */
@Composable
fun AccountHeader(tag: AccountTag, big: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(if (big) 46.dp else 32.dp).padding(horizontal = if (big) 20.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (big) 12.dp else 8.dp)
    ) {
        AccountMark(tag, if (big) 26.dp else 18.dp)
        Text(
            tag.name.uppercase(),
            color = HiFi.TextSecondary,
            fontSize = if (big) 16.sp else 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.06.em,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The account selector (New queue, New radio; D57): a chip per account, the chosen one filled. */
@Composable
fun AccountChips(tags: List<AccountTag>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier, big: Boolean = false) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(if (big) 14.dp else 8.dp)
    ) {
        tags.forEach { tag -> AccountChip(tag, on = tag.key == selected, big = big, onClick = { onSelect(tag.key) }) }
    }
}

@Composable
private fun AccountChip(tag: AccountTag, on: Boolean, big: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(if (big) 32.dp else 20.dp)
    Row(
        Modifier
            .height(if (big) 64.dp else 40.dp)
            .clip(shape)
            .background(if (on) Color.White else HiFi.GlassFill)
            .border(1.dp, if (on) Color.White else HiFi.GlassBorder, shape)
            .hifiPress(onClick, role = Role.RadioButton)
            .padding(start = if (big) 12.dp else 6.dp, end = if (big) 24.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (big) 12.dp else 8.dp)
    ) {
        AccountMark(tag, if (big) 40.dp else 28.dp)
        Text(
            tag.name,
            color = if (on) Color(0xFF0B0C10) else Color.White,
            fontSize = if (big) 22.sp else 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
