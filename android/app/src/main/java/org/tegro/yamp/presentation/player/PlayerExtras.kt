package org.tegro.yamp.presentation.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay
import org.tegro.yamp.R
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.presentation.common.artistNames
import org.tegro.yamp.presentation.common.coverUri
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiMotion
import org.tegro.yamp.utils.CoverSize
import org.tegro.yamp.utils.cover

/**
 * "Next tracks: 3" on the phone (canvas `pl-player-3`): three compact cards in the place of the single "Далее" plate,
 * the same height. A tap plays that track; the first card shows the next track's state (D81).
 */
@Composable
internal fun NextStrip(tracks: List<Track>, onClick: (Track) -> Unit) {
    val nextLook = LocalPlaybackAux.current.next()
    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { slot ->
            val track = tracks.getOrNull(slot)
            if (track == null) {
                Spacer(Modifier.weight(1f))
            } else {
                NextStripCard(track, look = if (slot == 0) nextLook else NextLook.READY, onClick = { onClick(track) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NextStripCard(track: Track, look: NextLook, onClick: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp)
    val title = track.title.ifEmpty { stringResource(R.string.bez_nazvaniya) }
    Row(
        modifier
            .height(64.dp)
            .clip(shape)
            .background(Color(0x6B080A14))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.car_next_play_cd, title), onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = ImageRequest.Builder(context).cover(track.coverUri, CoverSize.Small).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(43.dp).clip(RoundedCornerShape(10.dp)).background(HiFi.Placeholder)
                    .alpha(if (look == NextLook.FAILED) 0.4f else 1f)
            )
            NextCoverOverlay(look, 43.dp)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artistNames, color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The heart burst over the cover after a double tap (likes by gestures, canvas `pl-likes-gest`): each new [key]
 * shows it for a moment.
 */
@Composable
internal fun LikeBurst(key: Int) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (key == 0) return@LaunchedEffect
        shown = true
        delay(BURST_MS)
        shown = false
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(HiFiMotion.quick()) + scaleIn(HiFiMotion.spring(), initialScale = 0.6f),
        exit = fadeOut(HiFiMotion.standard()) + scaleOut(HiFiMotion.standard(), targetScale = 1.15f),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Favorite, contentDescription = stringResource(R.string.player_liked_gesture), tint = HiFi.Love, modifier = Modifier.size(96.dp))
        }
    }
}

private const val BURST_MS = 700L
