package org.tegro.yamp.presentation.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.tegro.yamp.R
import org.tegro.yamp.domain.queue.RadioSeed
import org.tegro.yamp.presentation.catalog.Cover
import org.tegro.yamp.presentation.common.ProviderBadge
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiAlertDialog
import org.tegro.yamp.presentation.hifi.HiFiDialogAction
import org.tegro.yamp.presentation.hifi.HiFiDialogActionKind
import org.tegro.yamp.presentation.hifi.HiFiDialogDefaults
import org.tegro.yamp.presentation.hifi.HiFiSwitch
import org.tegro.yamp.presentation.queues.LocalQueueSwitcher
import org.tegro.yamp.presentation.queues.QueueSwitcherRequest
import org.tegro.yamp.utils.russianQuantityString

/**
 * The album / artist menu (canvas `am-search-album`, `am-album-page-menu`, `am-artist-page-menu`…) and, for an
 * artist, the "what to add" dialog (`am-artist-confirm-*`). An album acts at once; an artist asks first.
 */
@Composable
fun MediaMenuHost(
    target: MediaTarget,
    snackbar: SnackbarHostState,
    /** Outlives the menu: the tracks load after it has closed, and their message shows then. */
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit
) {
    val vm: MediaActionsViewModel = hiltViewModel()
    val dialog by vm.artistDialog.collectAsStateWithLifecycle()
    val switcher = LocalQueueSwitcher.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    var menuOpen by remember(target) { mutableStateOf(true) }
    val fits = remember(target) { vm.fitsActiveQueue(target) }
    val failed = stringResource(R.string.media_load_failed)
    val say: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }
    val onAddTo: (List<org.tegro.yamp.domain.media.Track>, String) -> Unit = { tracks, title ->
        switcher?.open(QueueSwitcherRequest.AddTo(tracks, title))
    }
    val onDone: (Int) -> Unit = { count ->
        val counted = resources.getQuantityTextOrPlural(count)
        say(resources.getString(R.string.media_added, counted))
    }
    val onFailed: () -> Unit = { say(failed) }

    // "Add to playlist…" (D84): the picker shows while the tracks load; the add happens once both are there
    val playlistsVm: org.tegro.yamp.presentation.main.PlaylistsViewModel = hiltViewModel()
    var picking by remember(target) { mutableStateOf(false) }
    var naming by remember(target) { mutableStateOf(false) }
    var loaded by remember(target) { mutableStateOf<List<org.tegro.yamp.domain.media.Track>?>(null) }
    var chosen by remember(target) { mutableStateOf<org.tegro.yamp.domain.media.Playlist?>(null) }
    var newTitle by remember(target) { mutableStateOf<String?>(null) }
    val yandexPlaylists by playlistsVm.playlists.collectAsStateWithLifecycle()
    val otherPlaylists by playlistsVm.pickerPlaylists.collectAsStateWithLifecycle()
    val onAddToPlaylist: (List<org.tegro.yamp.domain.media.Track>) -> Unit = { tracks -> loaded = tracks }
    val startPicking = {
        playlistsVm.loadPickerFor(target.ref)
        picking = true
    }
    androidx.compose.runtime.LaunchedEffect(loaded, chosen, newTitle) {
        val tracks = loaded ?: return@LaunchedEffect
        when {
            chosen != null -> playlistsVm.addTracks(chosen!!, tracks)
            newTitle != null -> playlistsVm.createWith(newTitle!!, tracks)
            else -> return@LaunchedEffect
        }
        onDismiss()
    }
    if (picking && !naming) {
        MediaPlaylistPicker(
            title = target.title,
            playlists = if (target.provider == org.tegro.yamp.domain.account.Provider.JELLYFIN) otherPlaylists
            else playlistsVm.pickerFor(target.ref, yandexPlaylists, otherPlaylists),
            onNew = { naming = true },
            onPick = { picking = false; chosen = it; if (loaded == null) say(resources.getString(R.string.media_loading)) },
            onDismiss = { picking = false; onDismiss() }
        )
    }
    if (naming) {
        org.tegro.yamp.presentation.main.components.PlaylistNameDialog(
            title = stringResource(R.string.playlist_new),
            confirmLabel = stringResource(R.string.playlist_create),
            initialName = target.title,
            onConfirm = { name -> naming = false; picking = false; newTitle = name },
            onDismiss = { naming = false }
        )
    }

    if (menuOpen) {
        MediaMenuSheet(
            target = target,
            rows = mediaMenuRows(target, fits),
            onDismiss = { menuOpen = false; if (dialog == null) onDismiss() },
            onRow = { row ->
                menuOpen = false
                when (row) {
                    MediaMenuRow.START_RADIO -> {
                        switcher?.open(QueueSwitcherRequest.StartRadio(RadioSeed(target.ref, target.title)))
                        onDismiss()
                    }
                    MediaMenuRow.PIN, MediaMenuRow.UNPIN -> {
                        vm.togglePin(target)
                        onDismiss()
                    }
                    MediaMenuRow.ADD_TO_PLAYLIST -> {
                        if (target.isArtist) vm.chooseArtist(target, MediaAction.ADD_TO_PLAYLIST)
                        else {
                            vm.albumAction(target, MediaAction.ADD_TO_PLAYLIST, onAddTo, onDone, { onFailed(); onDismiss() }, onAddToPlaylist)
                            startPicking()
                        }
                    }
                    else -> {
                        val action = row.action()
                        if (target.isArtist) vm.chooseArtist(target, action)
                        else {
                            if (action != MediaAction.ADD_TO_QUEUE && action != MediaAction.PLAY_NOW && action != MediaAction.SHUFFLE) say(resources.getString(R.string.media_loading))
                            vm.albumAction(target, action, onAddTo, onDone, onFailed)
                            onDismiss()
                        }
                    }
                }
            }
        )
    }
    dialog?.let { state ->
        ArtistAddDialog(
            state = state,
            onConfirm = { choice, extras ->
                if (state.action == MediaAction.ADD_TO_PLAYLIST) {
                    vm.artistAction(choice, extras, onAddTo, onDone, { onFailed(); onDismiss() }, onAddToPlaylist)
                    startPicking()
                } else {
                    vm.artistAction(choice, extras, onAddTo, onDone, onFailed)
                    onDismiss()
                }
            },
            onDismiss = {
                vm.dismissArtist()
                onDismiss()
            }
        )
    }
}

/** "Added: 12 tracks" — the tracks count in the app's wording. */
private fun android.content.res.Resources.getQuantityTextOrPlural(count: Int): String {
    val index = org.tegro.yamp.utils.russianPluralIndex(count)
    val res = listOf(R.string.tracks_count_one, R.string.tracks_count_few, R.string.tracks_count_many)[index]
    return getString(res, count)
}

internal fun MediaMenuRow.action(): MediaAction = when (this) {
    MediaMenuRow.PLAY_NOW -> MediaAction.PLAY_NOW
    MediaMenuRow.SHUFFLE -> MediaAction.SHUFFLE
    MediaMenuRow.PLAY_NEXT -> MediaAction.PLAY_NEXT
    MediaMenuRow.ADD_TO_END -> MediaAction.ADD_TO_END
    MediaMenuRow.ADD_TO_QUEUE, MediaMenuRow.START_RADIO, MediaMenuRow.PIN, MediaMenuRow.UNPIN -> MediaAction.ADD_TO_QUEUE
    MediaMenuRow.ADD_TO_PLAYLIST -> MediaAction.ADD_TO_PLAYLIST
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaMenuSheet(target: MediaTarget, rows: List<MediaMenuRow>, onDismiss: () -> Unit, onRow: (MediaMenuRow) -> Unit) {
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
            MediaMenuContent(target, rows, onRow)
        }
    }
}

/** The sheet's content: header, rows, the footnote (canvas `AmMenu`). Separate so the goldens draw it. */
@Composable
internal fun MediaMenuContent(target: MediaTarget, rows: List<MediaMenuRow>, onRow: (MediaMenuRow) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp)) {
            if (target.isFolder) {
                org.tegro.yamp.presentation.folders.FolderArt(target.title, target.cover, 56.dp, radius = 12.dp)
            } else Cover(
                uri = target.cover,
                placeholder = if (target.isArtist) Icons.Rounded.Person else Icons.Rounded.Album,
                shape = if (target.isArtist) CircleShape else RoundedCornerShape(12.dp),
                size = 56.dp
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    target.title.ifEmpty { stringResource(R.string.bez_nazvaniya) },
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    target.provider?.let {
                        ProviderBadge(it)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(target.subtitle, color = HiFi.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        rows.forEach { row ->
            when (row) {
                MediaMenuRow.PLAY_NOW -> MenuRow(Icons.Rounded.PlayArrow, stringResource(R.string.media_play_now), stringResource(R.string.media_play_now_sub)) { onRow(row) }
                MediaMenuRow.PLAY_NEXT -> MenuRow(Icons.Rounded.SkipNext, stringResource(R.string.track_context_menu_igrat_sleduyuschim)) { onRow(row) }
                MediaMenuRow.ADD_TO_END -> MenuRow(Icons.AutoMirrored.Rounded.PlaylistPlay, stringResource(R.string.track_menu_add_to_end)) { onRow(row) }
                MediaMenuRow.ADD_TO_QUEUE -> MenuRow(Icons.Rounded.Layers, stringResource(R.string.track_menu_add_to_queue)) { onRow(row) }
                MediaMenuRow.ADD_TO_PLAYLIST -> MenuRow(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.track_context_menu_dobavit_v_pleylist)) { onRow(row) }
                MediaMenuRow.START_RADIO -> MenuRow(Icons.Rounded.Sensors, stringResource(R.string.radio_start)) { onRow(row) }
                MediaMenuRow.SHUFFLE -> MenuRow(Icons.Rounded.Shuffle, stringResource(R.string.media_shuffle)) { onRow(row) }
                MediaMenuRow.PIN -> MenuRow(Icons.Rounded.PushPin, stringResource(R.string.folders_pin)) { onRow(row) }
                MediaMenuRow.UNPIN -> MenuRow(Icons.Outlined.PushPin, stringResource(R.string.folders_unpin)) { onRow(row) }
            }
        }
        if (target.isArtist) {
            Text(
                stringResource(R.string.media_artist_asks),
                color = HiFi.TextTertiary, fontSize = 13.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp)
            )
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, text: String, note: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = if (note != null) 10.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.75f))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(text, fontSize = 16.sp, color = Color.White)
            if (note != null) Text(note, fontSize = 13.sp, color = HiFi.TextTertiary)
        }
    }
}

/** "Add «artist»": all albums (release order, singles and compilations optional) or the popular tracks. */
@Composable
internal fun ArtistAddDialog(state: ArtistDialogState, onConfirm: (ArtistChoice, Boolean) -> Unit, onDismiss: () -> Unit) {
    var choice by rememberSaveable { mutableStateOf(ArtistChoice.ALBUMS) }
    var extras by rememberSaveable { mutableStateOf(false) }
    val play = state.action == MediaAction.PLAY_NOW
    val page = state.page
    val counts = remember(page) { page?.let(::artistCounts) }
    // Nothing in release order (only singles) or no popular list: the other choice is the only one
    val albumsEmpty = counts != null && counts.releases == 0
    val popularEmpty = counts != null && counts.popular == 0
    val effective = when {
        albumsEmpty -> ArtistChoice.POPULAR
        popularEmpty -> ArtistChoice.ALBUMS
        else -> choice
    }
    HiFiAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(if (play) R.string.media_artist_play_title else R.string.media_artist_add_title, state.target.title),
        dismiss = { HiFiDialogAction(stringResource(R.string.queue_cancel), onDismiss, HiFiDialogActionKind.Quiet) },
        confirm = {
            HiFiDialogAction(
                stringResource(if (play) R.string.media_play else R.string.media_add),
                { onConfirm(effective, extras) },
                enabled = counts != null && !(albumsEmpty && popularEmpty)
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(
                    when (state.action) {
                        MediaAction.PLAY_NOW, MediaAction.SHUFFLE -> R.string.media_target_play
                        MediaAction.PLAY_NEXT -> R.string.media_target_next
                        MediaAction.ADD_TO_END -> R.string.media_target_end
                        MediaAction.ADD_TO_QUEUE -> R.string.media_target_queue
                        MediaAction.ADD_TO_PLAYLIST -> R.string.media_target_playlist
                    }
                ),
                fontSize = 14.sp
            )
            when {
                state.failed -> Text(stringResource(R.string.media_load_failed), color = HiFi.Danger, fontSize = 14.sp)
                counts == null -> Text(stringResource(R.string.media_loading), fontSize = 14.sp)
                else -> {
                    val albumsLine = if (extras) {
                        "${russianQuantityString(counts.releases, R.string.media_releases_one, R.string.media_releases_few, R.string.media_releases_many)} · " +
                            russianQuantityString(counts.releaseTracks, R.string.tracks_count_one, R.string.tracks_count_few, R.string.tracks_count_many)
                    } else {
                        "${russianQuantityString(counts.albums, R.string.media_albums_one, R.string.media_albums_few, R.string.media_albums_many)} · " +
                            russianQuantityString(counts.albumTracks, R.string.tracks_count_one, R.string.tracks_count_few, R.string.tracks_count_many)
                    }
                    ChoiceOption(
                        selected = effective == ArtistChoice.ALBUMS,
                        enabled = !albumsEmpty,
                        title = stringResource(R.string.media_all_albums),
                        count = albumsLine,
                        onSelect = { choice = ArtistChoice.ALBUMS }
                    ) {
                        if (counts.hasExtras) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 34.dp, top = 10.dp)
                            ) {
                                Text(stringResource(R.string.media_with_extras), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                HiFiSwitch(checked = extras, onCheckedChange = { extras = it; choice = ArtistChoice.ALBUMS })
                            }
                        }
                    }
                    ChoiceOption(
                        selected = effective == ArtistChoice.POPULAR,
                        enabled = !popularEmpty,
                        title = stringResource(R.string.media_popular),
                        count = russianQuantityString(counts.popular, R.string.tracks_count_one, R.string.tracks_count_few, R.string.tracks_count_many),
                        onSelect = { choice = ArtistChoice.POPULAR }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceOption(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    count: String,
    onSelect: () -> Unit,
    extra: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent, shape)
            .border(1.dp, Color.White.copy(alpha = if (selected) 0.45f else 0.14f), shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) Color.White else HiFi.TextSecondary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = if (enabled) Color.White else HiFi.TextTertiary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(count, color = HiFi.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (extra != null) Box { extra() }
    }
}
