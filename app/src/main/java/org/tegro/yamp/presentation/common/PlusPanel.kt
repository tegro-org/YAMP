package org.tegro.yamp.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tegro.yamp.R
import org.tegro.yamp.presentation.hifi.GlassPillButton
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.IconPlaque
import org.tegro.yamp.presentation.hifi.MetalPillButton

/**
 * The active queue's account has no Yandex Plus (D101): what the player and the radio show in place of the cover and of
 * the hero (canvas `px-player`, `px-radio`). [canSwitch]: another queue can be played instead — else the second action
 * adds a source (This device, Jellyfin), which only an upgraded user ever needs.
 */
@Immutable
class PlusBlock(
    val accountName: String,
    val canSwitch: Boolean,
    val onSubscribe: () -> Unit,
    val onQueues: () -> Unit,
    val onAddSource: () -> Unit
)

/** Set by the main screen while the active queue's account is refused; null otherwise. */
val LocalPlusBlock = staticCompositionLocalOf<PlusBlock?> { null }

/** The player's controls under a refused account: dimmed, still tappable (Play says why it does nothing). */
fun Modifier.plusDim(block: PlusBlock?): Modifier = if (block == null) this else alpha(0.4f)

/** The panel itself: the cover's place in the player ([radio] false) or the radio hero's ([radio] true). */
@Composable
fun PlusPanel(
    block: PlusBlock,
    radio: Boolean,
    big: Boolean,
    modifier: Modifier = Modifier,
    /** The two actions in a row (a wide hero); stacked otherwise. */
    sideBySide: Boolean = false,
    onQueues: () -> Unit = block.onQueues
) {
    val shape = RoundedCornerShape(if (big) 32.dp else 26.dp)
    BoxWithConstraints(modifier.fillMaxSize().background(HiFi.Placeholder.copy(alpha = 0.92f), shape).border(1.dp, HiFi.GlassBorder, shape)) {
        // A short place (a small phone, a phone on its side): the glyph goes, and what is left scrolls — the actions are never cut off
        val short = maxHeight < 260.dp
        val narrow = maxWidth < 300.dp
        Column(
            Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState())
                .padding(horizontal = if (big) 40.dp else if (maxWidth < 300.dp) 8.dp else 22.dp, vertical = if (short) 12.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        if (!short) {
            IconPlaque(Icons.Rounded.MusicOff, size = if (big) 72.dp else 56.dp)
            Spacer(Modifier.height(16.dp))
        }
        Text(stringResource(R.string.plus_required), color = HiFi.TextPrimary, fontSize = if (big) 26.sp else 20.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        Text(
            when {
                radio -> stringResource(R.string.plus_panel_radio, block.accountName)
                block.canSwitch -> stringResource(R.string.plus_panel_player, block.accountName)
                else -> stringResource(R.string.plus_panel_player_alone, block.accountName)
            },
            color = HiFi.TextSecondary, fontSize = if (big) 19.sp else 13.5.sp, lineHeight = if (big) 26.sp else 19.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = if (big) 8.dp else 6.dp).widthIn(max = if (big) 520.dp else 300.dp)
        )
        Spacer(Modifier.height(if (big) 24.dp else 18.dp))
        val actions: @Composable () -> Unit = {
            MetalPillButton(stringResource(R.string.plus_subscribe), block.onSubscribe, icon = Icons.Rounded.OpenInNew.takeIf { !narrow }, height = if (big) 64.dp else 42.dp)
            if (block.canSwitch) {
                GlassPillButton(
                    stringResource(if (radio) R.string.plus_queues else R.string.plus_other_queue), onQueues,
                    icon = Icons.Rounded.Layers.takeIf { !narrow }, height = if (big) 64.dp else 42.dp
                )
            } else {
                GlassPillButton(stringResource(R.string.plus_add_source), block.onAddSource, icon = Icons.Rounded.Add.takeIf { !narrow }, height = if (big) 64.dp else 42.dp)
            }
        }
        if (sideBySide) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { actions() }
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
    }
}
