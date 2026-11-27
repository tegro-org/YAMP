package org.tegro.yamp.presentation.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.OpenInNew
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
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.ui.draw.drawBehind
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
import org.tegro.yamp.presentation.hifi.HiFiSwitch
import org.tegro.yamp.presentation.hifi.MetalPillButton

/**
 * One network's sound for an account: the best there is (Yandex FLAC / Jellyfin original), compatible (MP3 / compressed,
 * up to 320 kbps), or economy (up to 128 kbps; canvas `qs-eco-*`).
 */
enum class SoundChoice { BEST, COMPATIBLE, ECONOMY }

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
    val onSet: (account: String, metered: Boolean, choice: SoundChoice) -> Unit,
    val onOpenWifi: () -> Unit,
    /** Auto-download per account, keyed by [SettingsRepository.accountAutoDownloadKey]; without a choice [globalAuto]. */
    val autoDownloads: Map<String, Boolean> = emptyMap(),
    val globalAuto: Boolean = false,
    val onSetAuto: (account: String, enabled: Boolean) -> Unit = { _, _ -> },
    /** The Yandex accounts without Plus (D101): their sheet opens with the subscription card. */
    val blocked: Set<String> = emptySet(),
    /** Developer option on: a refused-by-rule account is shown with the tag, not the card. */
    val allowWithoutPlus: Boolean = false
) {
    /** Whether [account]'s liked tracks (Jellyfin: favourites) download by themselves. */
    fun autoDownload(account: String): Boolean = autoDownloads[SettingsRepository.accountAutoDownloadKey(account)] ?: globalAuto

    /** [account]'s "compatible" for [metered] networks as it applies: its own choice, else the global one. */
    fun compatible(account: String, metered: Boolean): Boolean =
        choices[SettingsRepository.accountQualityKey(account, metered)] ?: global

    /** [account]'s sound for [metered] networks as it applies (economy is the account's own choice only). */
    fun choice(account: String, metered: Boolean): SoundChoice = when {
        choices[SettingsRepository.accountEconomyKey(account, metered)] == true -> SoundChoice.ECONOMY
        compatible(account, metered) -> SoundChoice.COMPATIBLE
        else -> SoundChoice.BEST
    }
}

/** The account row's one-line quality summary: "Wi-Fi: FLAC · metered: MP3". */
@Composable
internal fun qualitySummary(quality: AccountQualityUi, account: String, jellyfin: Boolean): String {
    @Composable
    fun short(choice: SoundChoice) = when {
        choice == SoundChoice.ECONOMY -> stringResource(R.string.quality_short_economy)
        jellyfin && choice == SoundChoice.COMPATIBLE -> stringResource(R.string.quality_short_converted)
        jellyfin -> stringResource(R.string.quality_short_original)
        choice == SoundChoice.COMPATIBLE -> "MP3"
        else -> "FLAC"
    }
    return stringResource(R.string.quality_summary, short(quality.choice(account, false)), short(quality.choice(account, true)))
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
                if (account in quality.blocked) {
                    PlusCard(big)
                    Spacer(Modifier.height(if (big) 14.dp else 10.dp))
                }
                AccountQualityContent(account, jellyfin, quality, big)
            }
        }
    }
}

/** "No Yandex Plus subscription" with the way to get one: Yandex's page in the browser (canvas `px-sheet`). */
@Composable
private fun PlusCard(big: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().background(HiFi.Warning.copy(alpha = 0.08f), shape).border(1.dp, HiFi.Warning.copy(alpha = 0.40f), shape)
            .padding(horizontal = if (big) 22.dp else 16.dp, vertical = if (big) 18.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(if (big) 16.dp else 12.dp)
    ) {
        Icon(Icons.Rounded.Block, null, tint = HiFi.Warning, modifier = Modifier.size(if (big) 30.dp else 22.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.plus_missing), color = Color.White, fontSize = if (big) 21.sp else 15.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.plus_missing_hint), color = HiFi.TextSecondary, fontSize = if (big) 17.sp else 13.sp, lineHeight = if (big) 24.sp else 18.sp)
            Row(
                Modifier.padding(top = if (big) 10.dp else 8.dp).clickable {
                    if (!org.tegro.yamp.presentation.common.PlusPage.open(context)) {
                        android.widget.Toast.makeText(context, R.string.plus_no_browser, android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (big) 8.dp else 5.dp)
            ) {
                Text(stringResource(R.string.plus_subscribe), color = HiFi.Accent, fontSize = if (big) 20.sp else 14.sp, fontWeight = FontWeight.Bold)
                Icon(Icons.Rounded.OpenInNew, null, tint = HiFi.Accent, modifier = Modifier.size(if (big) 22.dp else 16.dp))
            }
        }
    }
}

/** The sheet's content: the quality block and the car Wi-Fi hint. Separate so the goldens draw it. */
@Composable
fun AccountQualityContent(account: String, jellyfin: Boolean, quality: AccountQualityUi, big: Boolean) {
    // Three choices per network, each with its cap under the name; one legend under both rows (canvas `qs-eco-*`)
    val options = if (jellyfin) {
        listOf(stringResource(R.string.quality_original), stringResource(R.string.quality_seg_compressed), stringResource(R.string.quality_economy))
    } else {
        listOf(stringResource(R.string.quality_seg_lossless), stringResource(R.string.quality_seg_compatible), stringResource(R.string.quality_economy))
    }
    val captions = if (jellyfin) {
        listOf(stringResource(R.string.quality_cap_server), stringResource(R.string.quality_cap_320), stringResource(R.string.quality_cap_128))
    } else {
        listOf("FLAC", stringResource(R.string.quality_cap_mp3_320), stringResource(R.string.quality_cap_128))
    }
    val legend = if (jellyfin) {
        listOf(
            R.string.quality_original to R.string.quality_original_note,
            R.string.quality_converted to R.string.quality_converted_note,
            R.string.quality_economy_full to R.string.quality_economy_note_jf
        )
    } else {
        listOf(
            R.string.quality_lossless to R.string.quality_lossless_note,
            R.string.quality_compatible to R.string.quality_compatible_note,
            R.string.quality_economy_full to R.string.quality_economy_note_ya
        )
    }
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
            QualityChoiceRow(
                icon = if (metered) Icons.Default.NetworkCell else Icons.Default.Wifi,
                title = stringResource(if (metered) R.string.quality_metered else R.string.quality_unmetered),
                options = options,
                captions = captions,
                selected = quality.choice(account, metered).ordinal,
                active = quality.nowMetered == metered,
                big = big
            ) { index -> quality.onSet(account, metered, SoundChoice.entries[index]) }
        }
        QualityLegend(legend, highlighted = quality.choice(account, quality.nowMetered).ordinal, big = big)
        Text(
            stringResource(if (jellyfin) R.string.quality_next_track_jf else R.string.quality_next_track), color = HiFi.TextTertiary,
            fontSize = if (big) 16.sp else 12.sp, lineHeight = if (big) 22.sp else 16.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )
    }
    AutoDownloadRow(jellyfin, quality.autoDownload(account), big) { quality.onSetAuto(account, it) }
    CarWifiHint(quality.onOpenWifi, big)
}

/** The account's own auto-download: its new likes (Jellyfin: favourites) are saved for offline listening. */
@Composable
private fun AutoDownloadRow(jellyfin: Boolean, checked: Boolean, big: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .padding(top = if (big) 14.dp else 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = if (big) 22.dp else 16.dp, vertical = if (big) 18.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Download, null, tint = HiFi.TextSecondary, modifier = Modifier.size(if (big) 26.dp else 20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (jellyfin) R.string.account_auto_download_favourites else R.string.account_auto_download_liked),
                color = Color.White, fontSize = if (big) 21.sp else 15.sp, fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.account_auto_download_hint), color = HiFi.TextTertiary,
                fontSize = if (big) 16.sp else 12.sp, lineHeight = if (big) 22.sp else 16.sp
            )
        }
        Spacer(Modifier.width(10.dp))
        HiFiSwitch(checked = checked, onCheckedChange = onChange)
    }
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

/** Every option in one line under both rows: its full name, then what it does; the one in use now stands out. */
@Composable
private fun QualityLegend(lines: List<Pair<Int, Int>>, highlighted: Int, big: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = if (big) 4.dp else 2.dp)
            .drawHairline()
            .padding(top = if (big) 10.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (big) 8.dp else 6.dp)
    ) {
        lines.forEachIndexed { index, (name, note) ->
            val nameText = stringResource(name)
            val noteText = stringResource(note)
            val text = androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = if (index == highlighted) Color.White else HiFi.TextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                )
                append(nameText)
                pop()
                append(" — ")
                append(noteText)
            }
            Text(text, color = HiFi.TextTertiary, fontSize = if (big) 16.sp else 12.sp, lineHeight = if (big) 22.sp else 16.sp)
        }
    }
}

/** A hairline across the top, as between the canvas card's rows and its legend. */
private fun Modifier.drawHairline(): Modifier = drawBehind {
    drawLine(Color.White.copy(alpha = 0.08f), androidx.compose.ui.geometry.Offset(0f, 0.5f), androidx.compose.ui.geometry.Offset(size.width, 0.5f), 1f)
}

@Composable
private fun QualityChoiceRow(
    icon: ImageVector,
    title: String,
    options: List<String>,
    captions: List<String>,
    selected: Int,
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
        HiFiSegmented(options, selected, onSelect, fontSize = if (big) 20.sp else 13.sp, height = if (big) 68.dp else 52.dp, captions = captions)
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
