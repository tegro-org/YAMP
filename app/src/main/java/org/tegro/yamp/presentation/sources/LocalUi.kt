package org.tegro.yamp.presentation.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.UsbOff
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.tegro.yamp.data.provider.local.LocalVolume
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.queue.TrackRef
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.provider.local.LocalRefs

/** The "This device" colour (canvas `LoMark`): the marker's disc. */
val LocalDeviceColor = Color(0xFF3E9E6A)

/** The marker of "This device" (canvas `LoMark`): a green disc with a device glyph instead of an initial. */
@Composable
fun LocalDeviceMark(size: Dp, modifier: Modifier = Modifier, dim: Boolean = false) {
    Box(modifier.size(size).alpha(if (dim) 0.5f else 1f).clip(CircleShape).background(LocalDeviceColor), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Smartphone, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.58f))
    }
}

/** A volume's glyph: the phone, an SD card, a USB stick (anything removable that is not an SD card). */
fun volumeIcon(volumeId: String, name: String): ImageVector = when {
    volumeId == LocalVolume.PRIMARY -> Icons.Rounded.Smartphone
    name.contains("SD", ignoreCase = true) -> Icons.Rounded.SdCard
    else -> Icons.Rounded.Usb
}

/** The volume plate (canvas `LoVolArt`): the 4b placeholder's steel with the volume's glyph; dimmed when it is absent. */
@Composable
fun VolumeArt(volumeId: String, name: String, size: Dp, modifier: Modifier = Modifier, radius: Dp = 10.dp, dim: Boolean = false) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .size(size)
            .alpha(if (dim) 0.45f else 1f)
            .clip(shape)
            .background(Brush.linearGradient(listOf(HiFi.SteelLight, HiFi.SteelDark)))
            .background(Brush.radialGradient(listOf(Color(0x4D6FD08C), Color.Transparent), radius = size.value * 2f))
            .border(1.dp, HiFi.GlassBorder, shape),
        contentAlignment = Alignment.Center
    ) {
        Icon(volumeIcon(volumeId, name), contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * The volumes that are not connected now, by id → name (D96): queue rows, track lists and folder pages dim what is on
 * them. Provided at the app's root from the local overview; empty without local files.
 */
val LocalAbsentVolumes = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/** The name of the absent volume [ref] is on; null for a track that can play (or isn't local). */
fun absentVolumeOf(ref: TrackRef, absent: Map<String, String>): String? {
    if (absent.isEmpty()) return null
    val path = LocalRefs.path(ref.value) ?: return null
    return absent[path.volumeId]
}

/** [absentVolumeOf] for a track. */
fun Track.absentVolume(absent: Map<String, String>): String? = absentVolumeOf(ref, absent)

/**
 * The Library section's status line (canvas `lo-lib`, `LoScanLine`): "SD card: scanning 62 %" · "KINGSTON not
 * connected"; nothing when every source is there and idle.
 */
@androidx.compose.runtime.Composable
fun LocalStatusLine(overview: org.tegro.yamp.data.provider.local.LocalOverview, modifier: Modifier = Modifier) {
    val scanning = overview.scanning
    val absent = overview.absent
    if (scanning == null && absent.isEmpty()) return
    androidx.compose.foundation.layout.Row(
        modifier.padding(top = 0.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
    ) {
        if (scanning != null) {
            org.tegro.yamp.presentation.hifi.HiFiActivityIndicator(size = 13.dp)
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(org.tegro.yamp.R.string.local_status_scanning, scanning.volumeName, scanning.percent ?: 0),
                color = HiFi.TextSecondary, fontSize = 12.sp, maxLines = 1
            )
        }
        if (scanning != null && absent.isNotEmpty()) androidx.compose.material3.Text("·", color = HiFi.TextTertiary, fontSize = 12.sp)
        if (absent.isNotEmpty()) {
            Icon(androidx.compose.material.icons.Icons.Rounded.UsbOff, contentDescription = null, tint = HiFi.Warning, modifier = Modifier.size(14.dp))
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(org.tegro.yamp.R.string.local_volume_not_connected_line, absent.joinToString(", ")),
                color = HiFi.TextTertiary, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}
