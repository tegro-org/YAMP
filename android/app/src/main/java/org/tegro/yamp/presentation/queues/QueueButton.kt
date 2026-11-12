package org.tegro.yamp.presentation.queues

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.presentation.hifi.AppScale
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tegro.yamp.R
import org.tegro.yamp.presentation.hifi.GlassCircleButton
import org.tegro.yamp.presentation.hifi.HiFi

/**
 * The list-level "Add to end of queue" / "Add to queue…" of an album or playlist header: a ⋮ dropdown next
 * to Play / Shuffle (docs/phase-1-queues.md, open question 7). [title] names the list in the picker.
 */
@Composable
fun AddToQueueMenu(expanded: Boolean, onDismiss: () -> Unit, tracks: List<TrackDetailsDto>, title: String, onAddToEnd: (List<TrackDetailsDto>) -> Unit) {
    val launcher = LocalQueueSwitcher.current
    val appDensity = LocalDensity.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        AppScale(appDensity) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.track_menu_add_to_end)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onAddToEnd(tracks)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.track_menu_add_to_queue)) },
                leadingIcon = { Icon(Icons.Rounded.Layers, contentDescription = null) },
                onClick = {
                    onDismiss()
                    launcher?.open(QueueSwitcherRequest.AddTo(tracks, title))
                }
            )
        }
    }
}

/**
 * The queue button (DESIGN_GUIDE §8): a glass circle with the stacks glyph and the number of queues. Opens
 * the switcher through [LocalQueueSwitcher]; does nothing where none is provided (previews, tests).
 */
@Composable
fun QueueButton(count: Int, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val launcher = LocalQueueSwitcher.current
    Box(modifier) {
        GlassCircleButton(
            onClick = { launcher?.open(QueueSwitcherRequest.Switch()) },
            contentDescription = stringResource(R.string.cd_queues),
            size = size
        ) {
            Icon(Icons.Rounded.Layers, contentDescription = null, tint = Color.White, modifier = Modifier.size(size / 2))
        }
        if (count > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-3).dp)
                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                    .clip(CircleShape)
                    .background(HiFi.Paper)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(count.toString(), color = HiFi.Ground, fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    }
}
