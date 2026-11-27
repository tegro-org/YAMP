package org.tegro.yamp.presentation.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.tegro.yamp.R
import org.tegro.yamp.domain.media.Playlist
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiDialogDefaults
import org.tegro.yamp.presentation.main.components.ContextMenuItem
import org.tegro.yamp.presentation.main.components.PlaylistPickerRow

/**
 * "Add to playlist…" for an album or an artist's selection (canvas `am-add-to-playlist`): the music's own account's
 * playlists (D84) — Yandex's, or its Jellyfin server's — with "New playlist" on top. [playlists] null: loading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaPlaylistPicker(
    title: String,
    playlists: List<Playlist>?,
    onNew: () -> Unit,
    onPick: (Playlist) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val appDensity = androidx.compose.ui.platform.LocalDensity.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = HiFiDialogDefaults.Container,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false)
    ) {
        org.tegro.yamp.presentation.hifi.AppScale(appDensity) {
            BackHandler { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
            MediaPlaylistPickerContent(title, playlists, onNew, onPick)
        }
    }
}

/** The picker's content; separate so the goldens draw it. */
@Composable
internal fun MediaPlaylistPickerContent(title: String, playlists: List<Playlist>?, onNew: () -> Unit, onPick: (Playlist) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
        Text(
            stringResource(R.string.track_context_menu_dobavit_v_pleylist),
            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp)
        )
        Text(
            title.ifEmpty { stringResource(R.string.bez_nazvaniya) },
            color = HiFi.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 8.dp)
        )
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            item(key = "new") { ContextMenuItem(icon = Icons.Default.Add, text = stringResource(R.string.playlist_new)) { onNew() } }
            if (playlists == null) {
                item(key = "loading") {
                    Text(
                        stringResource(R.string.media_loading), color = HiFi.TextTertiary, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                    )
                }
            } else {
                items(playlists, key = { it.ref.value }) { playlist -> PlaylistPickerRow(playlist) { onPick(playlist) } }
            }
        }
    }
}
