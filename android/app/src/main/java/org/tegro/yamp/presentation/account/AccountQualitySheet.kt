package org.tegro.yamp.presentation.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import org.tegro.yamp.R
import org.tegro.yamp.domain.repository.SettingsRepository
import org.tegro.yamp.presentation.hifi.AppDialog
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiDialogDefaults
import org.tegro.yamp.presentation.hifi.HiFiSegmented
import org.tegro.yamp.presentation.hifi.MetalPillButton

/**
 * What Settings → Accounts needs for quality per account (canvas `qa-*`): every stored choice keyed by
 * [SettingsRepository.accountQualityKey], the global choice an account without its own follows, and which kind of
 * network is in use now.
 */
@Immutable
class AccountQualityUi(
    val choices: Map<String, Boolean>,
    val global: Boolean,
    val nowMetered: Boolean,
    val onSet: (account: String, metered: Boolean, compatible: Boolean) -> Unit,
    val onOpenWifi: () -> Unit
) {
    /** [account]'s "compatible" for [metered] networks as it applies: its own choice, else the global one. */
    fun compatible(account: String, metered: Boolean): Boolean =
        choices[SettingsRepository.accountQualityKey(account, metered)] ?: global
}

/** The account row's one-line quality summary: "Wi-Fi: FLAC · metered: MP3". */
@Composable
internal fun qualitySummary(quality: AccountQualityUi, account: String, jellyfin: Boolean): String {
    @Composable
    fun short(compatible: Boolean) = when {
        jellyfin && compatible -> stringResource(R.string.quality_short_converted)
        jellyfin -> stringResource(R.string.quality_short_original)
        compatible -> "MP3"
        else -> "FLAC"
    }
    return stringResource(R.string.quality_summary, short(quality.compatible(account, false)), short(quality.compatible(account, true)))
}

/** The account sheet with its quality (canvas `qa-sheet-yandex`, `qa-sheet-jellyfin`, `qa-head-*`). */
@Composable
fun AccountQualitySheet(
    name: String,
    account: String,
    jellyfin: Boolean,
    quality: AccountQualityUi,
    big: Boolean,
    onDismiss: () -> Unit
) {
    AppDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .padding(16.dp)
                    .widthIn(max = if (big) 760.dp else 560.dp)
                    .fillMaxWidth()
                    .clip(HiFiDialogDefaults.Shape)
                    .background(HiFiDialogDefaults.Container)
                    .border(1.dp, HiFiDialogDefaults.Rim, HiFiDialogDefaults.Shape)
                    .clickable(enabled = false) {}
                    .verticalScroll(rememberScrollState())
                    .padding(if (big) 28.dp else 22.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (big) 24.sp else 18.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(if (big) 48.dp else 34.dp)) {
                        Icon(Icons.Default.Close, stringResource(R.string.cd_zakryt), tint = Color.White.copy(alpha = 0.8f))
                    }
                }
                Spacer(Modifier.height(12.dp))
                AccountQualityContent(account, jellyfin, quality, big)
            }
        }
    }
}

/** The sheet's content: the quality block and the car Wi-Fi hint. Separate so the goldens draw it. */
@Composable
fun AccountQualityContent(account: String, jellyfin: Boolean, quality: AccountQualityUi, big: Boolean) {
    val options = if (jellyfin) listOf(stringResource(R.string.quality_original), stringResource(R.string.quality_converted))
    else listOf(stringResource(R.string.quality_lossless), stringResource(R.string.quality_compatible))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(horizontal = if (big) 22.dp else 16.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.quality_title).uppercase(), color = HiFi.TextSecondary, fontSize = if (big) 15.sp else 12.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, modifier = Modifier.weight(1f)
            )
            NowChip(quality.nowMetered, big)
        }
        listOf(false, true).forEach { metered ->
            val compatible = quality.compatible(account, metered)
            QualityChoiceRow(
                icon = if (metered) Icons.Default.NetworkCell else Icons.Default.Wifi,
                title = stringResource(if (metered) R.string.quality_metered else R.string.quality_unmetered),
                options = options,
                selected = if (compatible) 1 else 0,
                note = if (jellyfin) stringResource(if (compatible) R.string.quality_converted_note else R.string.quality_original_note) else null,
                active = quality.nowMetered == metered,
                big = big
            ) { index -> quality.onSet(account, metered, index == 1) }
        }
        Text(
            stringResource(R.string.quality_next_track), color = HiFi.TextTertiary, fontSize = if (big) 16.sp else 12.sp,
            lineHeight = if (big) 22.sp else 16.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )
    }
    CarWifiHint(quality.onOpenWifi, big)
}

@Composable
private fun NowChip(metered: Boolean, big: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
            .padding(horizontal = if (big) 16.dp else 12.dp, vertical = if (big) 9.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (metered) Icons.Default.NetworkCell else Icons.Default.Wifi, null,
            tint = if (metered) Color(0xFFF5B94A) else Color(0xFF6FD39A), modifier = Modifier.size(if (big) 22.dp else 16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(if (metered) R.string.quality_now_metered else R.string.quality_now_unmetered),
            color = Color.White, fontSize = if (big) 18.sp else 13.sp, fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun QualityChoiceRow(
    icon: ImageVector,
    title: String,
    options: List<String>,
    selected: Int,
    note: String?,
    active: Boolean,
    big: Boolean,
    onSelect: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = if (big) 14.dp else 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = if (big) 12.dp else 8.dp)) {
            Icon(icon, null, tint = HiFi.TextSecondary, modifier = Modifier.size(if (big) 26.dp else 20.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, color = Color.White, fontSize = if (big) 21.sp else 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (active) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF6FD39A), modifier = Modifier.size(if (big) 24.dp else 16.dp))
        }
        // Bigger on the head unit than on the canvas: the user asked for larger switch text there
        HiFiSegmented(options, selected, onSelect, fontSize = if (big) 20.sp else 14.sp, height = if (big) 60.dp else 44.dp)
        if (note != null) {
            Text(
                note, color = HiFi.TextTertiary, fontSize = if (big) 16.sp else 12.sp, lineHeight = if (big) 22.sp else 16.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun CarWifiHint(onOpenWifi: () -> Unit, big: Boolean) {
    Column(
        Modifier
            .padding(top = if (big) 14.dp else 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(horizontal = if (big) 22.dp else 16.dp, vertical = if (big) 18.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DirectionsCar, null, tint = HiFi.TextSecondary, modifier = Modifier.size(if (big) 26.dp else 20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.quality_car_title), color = Color.White, fontSize = if (big) 21.sp else 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(stringResource(R.string.quality_car_text), color = HiFi.TextSecondary, fontSize = if (big) 16.sp else 12.sp, lineHeight = if (big) 22.sp else 16.sp)
        Text(stringResource(R.string.quality_car_steps), color = Color.White, fontSize = if (big) 16.sp else 12.sp, lineHeight = if (big) 22.sp else 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        MetalPillButton(text = stringResource(R.string.quality_open_wifi), onClick = onOpenWifi, icon = Icons.Default.Settings)
    }
}
