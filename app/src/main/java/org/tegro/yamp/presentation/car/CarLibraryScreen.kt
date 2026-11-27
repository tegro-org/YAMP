package org.tegro.yamp.presentation.car

import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.rounded.UsbOff
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.tegro.yamp.R
import org.tegro.yamp.domain.media.Folder
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.presentation.account.AccountMark
import org.tegro.yamp.presentation.account.AccountTag
import org.tegro.yamp.presentation.catalog.Cover
import org.tegro.yamp.presentation.catalog.FolderPage
import org.tegro.yamp.presentation.folders.Crumbs
import org.tegro.yamp.presentation.folders.FolderArt
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.hifiAnodized
import org.tegro.yamp.presentation.hifi.hifiGlass
import org.tegro.yamp.presentation.hifi.hifiPress
import org.tegro.yamp.presentation.media.MediaMenuRow
import org.tegro.yamp.presentation.media.MediaTarget
import org.tegro.yamp.presentation.queues.QueueRow
import org.tegro.yamp.presentation.queues.QueueRowState
import org.tegro.yamp.presentation.queues.RowDims
import org.tegro.yamp.utils.russianQuantityString

/** The playing track on the capsule (canvas `NowPlayingCapsule`). */
data class CarNowPlaying(
    val title: String,
    val artist: String,
    val cover: String?,
    val isPlaying: Boolean,
    /** The playing track, to mark its row on the album page. */
    val ref: org.tegro.yamp.domain.queue.TrackRef? = null
)

/** What lies over the grid on the wheel side: the album / folder menu, or its "Add to queue…" picker. */
sealed interface CarPanel {
    val target: MediaTarget

    data class Menu(override val target: MediaTarget, val rows: List<MediaMenuRow>) : CarPanel

    /** [count]: the tracks being added, null while they load; [fromMenu]: back returns to the menu (else it closes). */
    data class AddTo(override val target: MediaTarget, val count: Int?, val fromMenu: Boolean = true) : CarPanel
}

/** What the car Library's controls do. */
class CarLibraryCallbacks(
    val onBack: () -> Unit,
    val onTab: (CarTab) -> Unit,
    val onAccount: (AccountTag) -> Unit,
    val onFilter: (String) -> Unit,
    val onTile: (CarTile) -> Unit,
    val onHold: (CarTile) -> Unit,
    val onPlayFolder: (Folder, Boolean) -> Unit,
    val onJump: (FolderPage) -> Unit,
    val onFolderAddTo: () -> Unit,
    val onFolderPin: () -> Unit,
    val onNowPlaying: () -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onMenuRow: (MediaMenuRow) -> Unit,
    val onPanelBack: () -> Unit,
    val onPanelClose: () -> Unit,
    val onAddTo: (QueueRowState) -> Unit,
    val onNewQueue: () -> Unit,
    /** The album page: a big button (the menu's rows), a track row (its index). */
    val onAlbumButton: (MediaMenuRow) -> Unit = {},
    val onAlbumTrack: (Int) -> Unit = {}
)

/** The canvas' car grid (carbrowse.jsx `CB`), in layout units: dp on the 1760×720 board. */
private object Cb {
    const val W = 1760f
    const val H = 720f
    const val PAD = 48f
    const val TOP = 124f
    const val TILE = 230f
    const val GAP = 24f
    const val PANEL = 720f
    const val COLUMN = 470f
    const val ALBUM_COLUMN = 620f
    const val TRACK_ROW = 88f
}

/**
 * The car Library (gap (f), canvas `ca-albums-*`, `cf-*`): the wheel side holds Back, the tabs (or the breadcrumbs in
 * a folder) and Filter, with the A–Z rail under them; the far side the account chip and the now-playing capsule; the
 * tiles fill the rest and scroll. A hold opens the menu on the wheel side; everything mirrors for the right hand.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun CarLibraryScreen(
    state: CarLibraryState,
    controlsOnLeft: Boolean,
    account: AccountTag?,
    accounts: List<AccountTag>,
    nowPlaying: CarNowPlaying?,
    panel: CarPanel?,
    queues: List<QueueRowState>,
    callbacks: CarLibraryCallbacks,
    pressed: String? = null,
    filterOpen: Boolean = false,
    /** The open folder is pinned (its level's Pin / Unpin pill). */
    folderPinned: Boolean = false,
    /** The active queue can take the open album (D84): its page offers Play next and Add to end. */
    albumFits: Boolean = true
) {
    val base = LocalDensity.current
    // The car's type is sized for a glance: Android's font size does not grow it
    CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale.coerceAtMost(1f))) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(HiFi.Ground)
                .background(Brush.verticalGradient(listOf(Color(0xFF3B3355).copy(alpha = 0.55f), Color.Transparent)))
                .systemBarsPadding()
        ) {
            val s = minOf(maxWidth.value / Cb.W, maxHeight.value / Cb.H)
            fun u(v: Float): Dp = (v * s).dp
            val widthU = maxWidth.value / s
            fun x(start: Float, width: Float) = if (controlsOnLeft) start else widthU - start - width
            val provider = state.account?.provider ?: org.tegro.yamp.domain.account.Provider.YANDEX
            val album = state.album
            val inFolder = album == null && state.folder?.folderId != null
            val tiles = state.shown
            val letters = remember(tiles) { railLetters(tiles) }
            val rail = album == null && !inFolder && letters.size > 1
            val railW = if (letters.size > 7) 120f else 56f
            val gridStart = when {
                inFolder -> Cb.PAD + Cb.COLUMN + 40f
                rail -> Cb.PAD + railW + 28f
                else -> Cb.PAD
            }
            val gridW = widthU - gridStart - Cb.PAD
            val grid = rememberLazyGridState()
            val scope = rememberCoroutineScope()
            var showFilter by remember { mutableStateOf(filterOpen || state.filter.isNotEmpty()) }

            // The album page (canvas `ca-album-*`): the head and the buttons on the wheel side, the tracks on the far side
            if (album != null) {
                val listStart = Cb.PAD + Cb.ALBUM_COLUMN + 40f
                val listW = widthU - listStart - Cb.PAD
                CarAlbumTracks(album, nowPlaying?.ref, ::u, callbacks.onAlbumTrack, Modifier.offset(x = u(x(listStart, listW)), y = u(Cb.TOP)).width(u(listW)).fillMaxHeight())
                CarAlbumHead(
                    album, carAlbumButtons(provider, albumFits), provider, ::u, callbacks.onAlbumButton,
                    Modifier.offset(x = u(x(Cb.PAD, Cb.ALBUM_COLUMN)), y = u(Cb.TOP)).width(u(Cb.ALBUM_COLUMN))
                )
            }

            // The grid
            if (album == null) Box(Modifier.offset(x = u(x(gridStart, gridW)), y = u(Cb.TOP)).width(u(gridW)).fillMaxHeight()) {
                when {
                    state.loading && tiles.isEmpty() -> Unit
                    tiles.isEmpty() -> Text(
                        stringResource(if (state.failed) R.string.catalog_load_failed else R.string.car_library_empty),
                        color = HiFi.TextSecondary, fontSize = 22.sp, modifier = Modifier.padding(top = u(24f))
                    )
                    else -> LazyVerticalGrid(
                        state = grid,
                        columns = GridCells.FixedSize(u(Cb.TILE)),
                        horizontalArrangement = Arrangement.spacedBy(u(Cb.GAP), if (controlsOnLeft) Alignment.Start else Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(u(22f)),
                        contentPadding = PaddingValues(top = u(4f), bottom = u(60f)),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(tiles, key = { it.key }) { tile ->
                            CarTileView(tile, ::u, pressed == tile.key, onTap = { callbacks.onTile(tile) }, onHold = { callbacks.onHold(tile) }, onPlay = {
                                (tile as? CarTile.OfFolder)?.let { callbacks.onPlayFolder(it.folder, false) }
                            })
                        }
                    }
                }
            }

            // The rail on the wheel side
            if (rail) {
                Row(Modifier.offset(x = u(x(Cb.PAD, railW)), y = u(Cb.TOP)), horizontalArrangement = Arrangement.spacedBy(u(8f))) {
                    letters.chunked(7).forEach { column ->
                        Column(verticalArrangement = Arrangement.spacedBy(u(8f))) {
                            column.forEach { letter ->
                                Box(
                                    Modifier.size(u(56f)).clip(RoundedCornerShape(u(16f))).hifiGlass(RoundedCornerShape(u(16f)))
                                        .clickable { scope.launch { firstIndexOf(tiles, letter).takeIf { it >= 0 }?.let { grid.animateScrollToItem(it) } } },
                                    contentAlignment = Alignment.Center
                                ) { Text(letter.toString(), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold) }
                            }
                        }
                    }
                }
            }

            // Inside a folder: its head and the big actions on the wheel side (canvas `cf-level`)
            if (inFolder) {
                val page = state.folder
                Column(Modifier.offset(x = u(x(Cb.PAD, Cb.COLUMN)), y = u(Cb.TOP)).width(u(Cb.COLUMN))) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FolderArt(page.title, page.cover, u(132f), radius = u(22f))
                        Spacer(Modifier.width(u(20f)))
                        Column {
                            Text(page.title, color = Color.White, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            state.level?.let { l ->
                                Text(
                                    listOfNotNull(
                                        l.folders.size.takeIf { it > 0 }?.let { russianQuantityString(it, R.string.folders_count_one, R.string.folders_count_few, R.string.folders_count_many) },
                                        l.tracks.size.takeIf { it > 0 }?.let { russianQuantityString(it, R.string.tracks_count_one, R.string.tracks_count_few, R.string.tracks_count_many) }
                                    ).joinToString(" · "),
                                    color = HiFi.TextSecondary, fontSize = 19.sp, modifier = Modifier.padding(top = u(6f))
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(u(26f)))
                    val folder = Folder(page.refOf(page.folderId.orEmpty()), page.title, null)
                    val pinned = folderPinned
                    Row(horizontalArrangement = Arrangement.spacedBy(u(14f))) {
                        CarPill(Icons.Rounded.PlayArrow, stringResource(R.string.cd_slushat), u(84f), metal = true, Modifier.weight(1f)) { callbacks.onPlayFolder(folder, false) }
                        CarPill(Icons.Rounded.Shuffle, stringResource(R.string.media_shuffle), u(84f), metal = false, Modifier.weight(1f)) { callbacks.onPlayFolder(folder, true) }
                    }
                    Spacer(Modifier.height(u(14f)))
                    Row(horizontalArrangement = Arrangement.spacedBy(u(14f))) {
                        CarPill(Icons.Rounded.Layers, stringResource(R.string.track_menu_add_to_queue), u(84f), metal = false, Modifier.weight(1f), callbacks.onFolderAddTo)
                        CarPill(
                            if (pinned) Icons.Outlined.PushPin else Icons.Rounded.PushPin,
                            stringResource(if (pinned) R.string.folders_unpin else R.string.folders_pin), u(84f), metal = false, Modifier.weight(1f), callbacks.onFolderPin
                        )
                    }
                }
            }

            // The wheel side's top row: Back · tabs or breadcrumbs · Filter
            Row(
                Modifier.offset(x = if (controlsOnLeft) u(Cb.PAD) else 0.dp, y = u(26f))
                    .then(if (controlsOnLeft) Modifier else Modifier.fillMaxWidth().padding(end = u(Cb.PAD))),
                horizontalArrangement = Arrangement.spacedBy(u(14f), if (controlsOnLeft) Alignment.Start else Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val back: @Composable () -> Unit = {
                    Box(Modifier.size(u(60f)).hifiGlass(CircleShape).clickable(onClick = callbacks.onBack), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_nazad), tint = Color.White, modifier = Modifier.size(u(28f)))
                    }
                }
                val middle: @Composable () -> Unit = {
                    if (album != null) {
                        CarAlbumCrumbs(stringResource(R.string.jellyfin_lib_albums), album.album.title, ::u, callbacks.onBack)
                    } else if (inFolder) {
                        Box(Modifier.width(u(900f))) {
                            Crumbs(state.folder, account, big = true, onJump = callbacks.onJump)
                        }
                    } else {
                        CarTabs(carTabs(provider), state.tab, provider, ::u, callbacks.onTab)
                    }
                }
                val filter: @Composable () -> Unit = {
                    if (!inFolder && album == null) {
                        if (showFilter) {
                            CarFilterField(state.filter, callbacks.onFilter, ::u) { showFilter = false; callbacks.onFilter("") }
                        } else {
                            CarPill(Icons.Rounded.Search, stringResource(R.string.car_filter), u(60f), metal = false, Modifier) { showFilter = true }
                        }
                    }
                }
                if (controlsOnLeft) { back(); middle(); filter() } else { filter(); middle(); back() }
            }

            // The far side: the account chip and the now-playing capsule
            Row(
                Modifier.offset(x = if (controlsOnLeft) 0.dp else u(Cb.PAD), y = u(24f))
                    .then(if (controlsOnLeft) Modifier.fillMaxWidth().padding(end = u(Cb.PAD)) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(u(14f), if (controlsOnLeft) Alignment.End else Alignment.Start),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val chip: @Composable () -> Unit = { AccountChip(account, accounts, ::u, callbacks.onAccount) }
                val capsule: @Composable () -> Unit = { nowPlaying?.let { NowPlayingCapsule(it, ::u, callbacks) } }
                if (controlsOnLeft) { chip(); capsule() } else { capsule(); chip() }
            }

            // The menu or the picker over a dimmed screen, on the wheel side
            if (panel != null) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = callbacks.onPanelClose)
                )
                Box(
                    Modifier.align(if (controlsOnLeft) Alignment.CenterStart else Alignment.CenterEnd).width(u(Cb.PANEL)).fillMaxHeight()
                        .background(Color(0xFF14161E).copy(alpha = 0.97f))
                        .border(1.dp, HiFi.GlassBorder)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                ) {
                    when (panel) {
                        is CarPanel.Menu -> CarMediaMenu(panel, ::u, callbacks.onMenuRow)
                        is CarPanel.AddTo -> CarAddToQueue(panel, account, queues, ::u, callbacks)
                    }
                }
            }
        }
    }
}

@Composable
private fun CarTabs(tabs: List<CarTab>, selected: CarTab, provider: org.tegro.yamp.domain.account.Provider, u: (Float) -> Dp, onTab: (CarTab) -> Unit) {
    Row(
        Modifier.height(u(64f)).clip(RoundedCornerShape(u(32f))).background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, HiFi.Hairline, RoundedCornerShape(u(32f))).padding(u(4f))
    ) {
        tabs.forEach { tab ->
            val on = tab == selected
            Box(
                Modifier.fillMaxHeight().clip(RoundedCornerShape(u(28f)))
                    .then(if (on) Modifier.hifiAnodized(RoundedCornerShape(u(28f))) else Modifier)
                    .clickable { onTab(tab) }
                    .padding(horizontal = u(22f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(
                        when (tab) {
                            CarTab.FOLDERS -> R.string.folders_title
                            CarTab.ALBUMS -> R.string.jellyfin_lib_albums
                            CarTab.PLAYLISTS -> R.string.jellyfin_lib_playlists
                            CarTab.LIKED -> if (provider != org.tegro.yamp.domain.account.Provider.YANDEX) R.string.library_row_favourites else R.string.library_row_liked
                        }
                    ),
                    color = if (on) Color.White else HiFi.TextSecondary, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun CarPill(icon: ImageVector, label: String, height: Dp, metal: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = CircleShape
    Row(
        modifier.height(height).clip(shape)
            .then(if (metal) Modifier.hifiAnodized(shape) else Modifier.hifiGlass(shape))
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CarFilterField(value: String, onChange: (String) -> Unit, u: (Float) -> Dp, onClose: () -> Unit) {
    Row(
        Modifier.width(u(320f)).height(u(60f)).clip(CircleShape).hifiGlass(CircleShape).padding(start = u(18f), end = u(6f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(u(26f)))
        Spacer(Modifier.width(u(10f)))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 22.sp), cursorBrush = SolidColor(HiFi.Accent),
            modifier = Modifier.weight(1f)
        )
        Box(Modifier.size(u(48f)).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_ochistit), tint = Color.White, modifier = Modifier.size(u(24f)))
        }
    }
}

/** The account the browse belongs to; ▾ picks another one. */
@Composable
private fun AccountChip(account: AccountTag?, accounts: List<AccountTag>, u: (Float) -> Dp, onPick: (AccountTag) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(u(60f)).clip(CircleShape).hifiGlass(CircleShape).clickable(enabled = accounts.size > 1) { open = true }
                .padding(start = u(12f), end = u(16f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            account?.let { AccountMark(it, u(34f)) }
            Spacer(Modifier.width(u(10f)))
            Text(
                account?.name.orEmpty(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = u(190f))
            )
            if (accounts.size > 1) Icon(Icons.Rounded.ExpandMore, contentDescription = stringResource(R.string.car_account_pick), tint = HiFi.TextSecondary, modifier = Modifier.size(u(26f)))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = org.tegro.yamp.presentation.hifi.HiFiDialogDefaults.Container) {
            accounts.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(tag.name, color = Color.White, fontSize = 22.sp) },
                    leadingIcon = { AccountMark(tag, 30.dp) },
                    onClick = { open = false; onPick(tag) },
                    modifier = Modifier.height(64.dp)
                )
            }
        }
    }
}

@Composable
private fun NowPlayingCapsule(now: CarNowPlaying, u: (Float) -> Dp, callbacks: CarLibraryCallbacks) {
    Row(
        Modifier.width(u(380f)).height(u(64f)).clip(CircleShape).background(Color(0xD9101218)).border(1.dp, HiFi.GlassBorder, CircleShape)
            .clickable(onClickLabel = stringResource(R.string.car_back_to_player), onClick = callbacks.onNowPlaying).padding(horizontal = u(8f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(now.cover, Icons.Rounded.MusicNote, CircleShape, u(48f))
        Spacer(Modifier.width(u(12f)))
        Column(Modifier.weight(1f)) {
            Text(now.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(now.artist, color = HiFi.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(u(48f)).clip(CircleShape).hifiAnodized(CircleShape).clickable(onClick = callbacks.onPlayPause), contentAlignment = Alignment.Center) {
            Icon(if (now.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(u(28f)))
        }
        Box(Modifier.size(u(48f)).clip(CircleShape).clickable(onClick = callbacks.onNext), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(u(32f)))
        }
    }
}

/** A tile: the cover (a folder's art or placeholder, with its pin mark and the play knob), the name, the second line. */
@Composable
private fun CarTileView(tile: CarTile, u: (Float) -> Dp, pressed: Boolean, onTap: () -> Unit, onHold: () -> Unit, onPlay: () -> Unit) {
    val size = u(Cb.TILE)
    val shape = RoundedCornerShape(u(24f))
    // A local folder or album on a volume that is not connected (canvas `lo-cf-root-*`, `lo-ca-albums-r`, D96)
    val absent = when (tile) {
        is CarTile.OfFolder -> !tile.folder.available
        is CarTile.OfAlbum -> !tile.album.available
        else -> false
    }
    Column(Modifier.width(size).hifiPress(onTap, onLongClick = onHold)) {
        Box(Modifier.size(size).then(if (pressed) Modifier.border(4.dp, HiFi.Accent, shape) else Modifier)) {
            when (tile) {
                is CarTile.OfFolder -> {
                    val volumeId = org.tegro.yamp.presentation.folders.localVolumeRoot(tile.folder)
                    if (volumeId != null) org.tegro.yamp.presentation.sources.VolumeArt(volumeId, tile.title, size, radius = u(24f), dim = absent)
                    else FolderArt(tile.title, tile.folder.cover?.key, size, Modifier.alpha(if (absent) ABSENT_ALPHA else 1f), radius = u(24f))
                    if (tile.pinned) {
                        Box(Modifier.padding(u(10f)).size(u(36f)).background(Color(0x8C05060A), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PushPin, contentDescription = null, tint = Color.White, modifier = Modifier.size(u(20f)))
                        }
                    }
                    if (absent) {
                        Row(
                            Modifier.align(Alignment.BottomCenter).padding(u(10f)).fillMaxWidth().height(u(44f)).background(Color(0xB305060A), RoundedCornerShape(u(22f))),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Rounded.UsbOff, contentDescription = null, tint = HiFi.Warning, modifier = Modifier.size(u(20f)))
                            Spacer(Modifier.width(u(8f)))
                            Text(stringResource(R.string.local_not_connected_short), color = HiFi.Warning, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    } else {
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(u(10f)).size(u(64f)).clip(CircleShape).hifiAnodized(CircleShape).clickable(onClick = onPlay),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.folders_play_cd), tint = Color.White, modifier = Modifier.size(u(34f))) }
                    }
                }
                is CarTile.OfAlbum -> Box(Modifier.alpha(if (absent) ABSENT_ALPHA else 1f)) { Cover(tile.album.cover?.key, Icons.Rounded.Album, shape, size) }
                is CarTile.OfPlaylist -> Cover(tile.playlist.cover?.key, Icons.AutoMirrored.Rounded.PlaylistPlay, shape, size)
                is CarTile.OfTrack -> Cover(tile.track.cover?.key, Icons.Rounded.MusicNote, shape, size)
            }
        }
        Text(
            tile.title, color = Color.White, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = u(8f)).alpha(if (absent) 0.5f else 1f)
        )
        val unknown = stringResource(R.string.local_unknown_artist)
        val sub = when {
            absent && tile is CarTile.OfFolder -> stringResource(R.string.local_tracks_unavailable)
            tile is CarTile.OfAlbum -> listOfNotNull(
                tile.album.artists.joinToString { it.name }.ifEmpty { if (tile.album.ref.provider == org.tegro.yamp.provider.local.LocalRefs.PROVIDER) unknown else null },
                if (absent) stringResource(R.string.local_not_connected_short) else tile.album.qualifier
            ).joinToString(" · ")
            tile is CarTile.OfPlaylist -> russianQuantityString(tile.playlist.trackCount, R.string.tracks_count_one, R.string.tracks_count_few, R.string.tracks_count_many)
            tile is CarTile.OfTrack -> tile.track.artists.joinToString { it.name }
            tile is CarTile.OfFolder -> tile.folder.trackCount?.let { russianQuantityString(it, R.string.tracks_count_one, R.string.tracks_count_few, R.string.tracks_count_many) }.orEmpty()
            else -> ""
        }
        if (sub.isNotEmpty()) Text(sub, color = if (absent) HiFi.TextTertiary else HiFi.TextSecondary, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val ABSENT_ALPHA = 0.42f

/** The album / folder menu in car sizes (canvas `AmMenu big`): 84 dp rows, the cover 84. */
@Composable
private fun CarMediaMenu(panel: CarPanel.Menu, u: (Float) -> Dp, onRow: (MediaMenuRow) -> Unit) {
    val target = panel.target
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = u(32f), end = u(32f), top = u(22f), bottom = u(22f))) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = u(12f))) {
                if (target.isFolder) FolderArt(target.title, target.cover, u(84f), radius = u(16f))
                else Cover(target.cover, Icons.Rounded.Album, RoundedCornerShape(u(16f)), u(84f))
                Spacer(Modifier.width(u(20f)))
                Column {
                    Text(target.title, color = Color.White, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = u(2f))) {
                        target.provider?.let { org.tegro.yamp.presentation.common.ProviderBadge(it, size = u(20f)); Spacer(Modifier.width(u(8f))) }
                        Text(target.subtitle, color = HiFi.TextSecondary, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        items(panel.rows) { row ->
            val (icon, label) = when (row) {
                MediaMenuRow.PLAY_NOW -> Icons.Rounded.PlayArrow to stringResource(R.string.media_play_now)
                MediaMenuRow.SHUFFLE -> Icons.Rounded.Shuffle to stringResource(R.string.media_shuffle)
                MediaMenuRow.PLAY_NEXT -> Icons.Rounded.SkipNext to stringResource(R.string.track_context_menu_igrat_sleduyuschim)
                MediaMenuRow.ADD_TO_END -> Icons.AutoMirrored.Rounded.PlaylistPlay to stringResource(R.string.track_menu_add_to_end)
                MediaMenuRow.ADD_TO_QUEUE -> Icons.Rounded.Layers to stringResource(R.string.track_menu_add_to_queue)
                MediaMenuRow.ADD_TO_PLAYLIST -> Icons.AutoMirrored.Rounded.PlaylistAdd to stringResource(R.string.track_context_menu_dobavit_v_pleylist)
                MediaMenuRow.START_RADIO -> Icons.Rounded.Sensors to stringResource(R.string.radio_start)
                MediaMenuRow.PIN -> Icons.Rounded.PushPin to stringResource(R.string.folders_pin)
                MediaMenuRow.UNPIN -> Icons.Outlined.PushPin to stringResource(R.string.folders_unpin)
            }
            Row(
                Modifier.fillMaxWidth().height(u(84f)).clip(RoundedCornerShape(u(16f))).clickable { onRow(row) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(u(34f)))
                Spacer(Modifier.width(u(24f)))
                Column {
                    Text(label, color = Color.White, fontSize = 25.sp, lineHeight = 31.sp)
                    if (row == MediaMenuRow.PLAY_NOW) Text(stringResource(R.string.media_play_now_sub), color = HiFi.TextTertiary, fontSize = 17.sp, lineHeight = 22.sp)
                }
            }
        }
    }
}

/**
 * The car "Add to queue…" (canvas `ca-add-*`): the static queues of the album's account only (D84), "New queue" on
 * top (named after the album: no typing in the car), the car switcher's 100 dp rows. Back returns to the menu.
 */
@Composable
private fun CarAddToQueue(panel: CarPanel.AddTo, account: AccountTag?, queues: List<QueueRowState>, u: (Float) -> Dp, callbacks: CarLibraryCallbacks) {
    val d = RowDims(big = true, factor = u(1f).value)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = u(22f))) {
        item {
            Row(Modifier.padding(start = u(24f), end = u(24f), top = u(24f), bottom = u(14f)), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(u(60f)).hifiGlass(CircleShape).clickable(onClick = callbacks.onPanelBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_nazad), tint = Color.White, modifier = Modifier.size(u(28f)))
                }
                Spacer(Modifier.width(u(16f)))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.car_add_to_queue), color = Color.White, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Text(
                        listOfNotNull(
                            panel.target.title, panel.target.subtitle.substringAfter(" · ", "").substringBefore(" · ").ifEmpty { null },
                            panel.count?.let { russianQuantityString(it, R.string.tracks_count_one, R.string.tracks_count_few, R.string.tracks_count_many) }
                        ).joinToString(" · "),
                        color = HiFi.TextSecondary, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = u(2f))
                    )
                }
                Box(Modifier.size(u(60f)).hifiGlass(CircleShape).clickable(onClick = callbacks.onPanelClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_ochistit), tint = Color.White, modifier = Modifier.size(u(28f)))
                }
            }
        }
        item {
            Row(
                Modifier.padding(horizontal = u(14f)).fillMaxWidth().height(u(100f)).clip(RoundedCornerShape(u(24f)))
                    .hifiGlass(RoundedCornerShape(u(24f))).clickable(onClick = callbacks.onNewQueue).padding(start = u(16f), end = u(20f)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(u(68f)).drawBehind {
                        drawRoundRect(
                            Color.White.copy(alpha = 0.3f), cornerRadius = CornerRadius(16.dp.toPx() * u(1f).value),
                            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                        )
                    },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(u(36f))) }
                Spacer(Modifier.width(u(18f)))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.car_new_queue), color = Color.White, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.car_new_queue_sub, panel.target.title, account?.name.orEmpty()),
                        color = HiFi.TextSecondary, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(u(6f)))
            account?.let { org.tegro.yamp.presentation.account.AccountHeader(it, big = true) }
        }
        items(queues, key = { it.id }) { row ->
            Box(Modifier.padding(horizontal = u(14f))) { QueueRow(row.copy(isActive = false), d, onClick = { callbacks.onAddTo(row) }, onLongClick = null) }
        }
        item {
            Text(
                stringResource(R.string.car_queues_only, account?.name.orEmpty()),
                color = HiFi.TextTertiary, fontSize = 17.sp, lineHeight = 22.sp, modifier = Modifier.padding(start = u(34f), end = u(34f), top = u(12f))
            )
        }
    }
}

/** The album page's breadcrumbs (canvas `Crumbs big`): "Albums" (back to the grid) › the album's title. */
@Composable
private fun CarAlbumCrumbs(albums: String, title: String, u: (Float) -> Dp, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(max = u(900f))) {
        Box(Modifier.height(u(60f)).clip(CircleShape).hifiGlass(CircleShape).hifiPress(onBack).padding(horizontal = u(22f)), contentAlignment = Alignment.Center) {
            Text(albums, color = HiFi.TextSecondary, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = HiFi.TextTertiary, modifier = Modifier.size(u(26f)))
        Box(
            Modifier.weight(1f, fill = false).height(u(60f)).background(Color.White.copy(alpha = 0.16f), CircleShape).border(1.dp, HiFi.GlassBorder, CircleShape)
                .padding(horizontal = u(22f)),
            contentAlignment = Alignment.Center
        ) { Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** The album page's wheel side (canvas `CbAlbumPage`): cover, title, artist, "Album · year · tracks · min", the big buttons. */
@Composable
private fun CarAlbumHead(page: CarAlbumPage, buttons: List<MediaMenuRow>, provider: org.tegro.yamp.domain.account.Provider, u: (Float) -> Dp, onButton: (MediaMenuRow) -> Unit, modifier: Modifier) {
    val album = page.album
    val count = if (page.loading) album.trackCount else page.tracks.size
    val minutes = ((page.tracks.sumOf { it.durationMs ?: 0L } + 30_000L) / 60_000L).toInt()
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(album.cover?.key, Icons.Rounded.Album, RoundedCornerShape(u(28f)), u(200f))
            Spacer(Modifier.width(u(24f)))
            Column {
                Text(album.title, color = Color.White, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    album.artists.joinToString { it.name }, color = Color.White, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = u(6f))
                )
                Text(
                    listOfNotNull(
                        stringResource(R.string.media_album_word), album.year?.toString(),
                        count.takeIf { it > 0 }?.let { russianQuantityString(it, R.string.tracks_count_one, R.string.tracks_count_few, R.string.tracks_count_many) },
                        minutes.takeIf { it > 0 }?.let { stringResource(R.string.car_album_minutes, it) }
                    ).joinToString(" · "),
                    color = HiFi.TextSecondary, fontSize = 19.sp, maxLines = 2, modifier = Modifier.padding(top = u(4f))
                )
            }
        }
        Spacer(Modifier.height(u(26f)))
        buttons.chunked(2).forEachIndexed { i, pair ->
            if (i > 0) Spacer(Modifier.height(u(14f)))
            Row(horizontalArrangement = Arrangement.spacedBy(u(14f))) {
                pair.forEach { row ->
                    val (icon, label) = when (row) {
                        MediaMenuRow.PLAY_NOW -> Icons.Rounded.PlayArrow to stringResource(R.string.media_play)
                        MediaMenuRow.SHUFFLE -> Icons.Rounded.Shuffle to stringResource(R.string.media_shuffle)
                        MediaMenuRow.PLAY_NEXT -> Icons.Rounded.SkipNext to stringResource(R.string.track_context_menu_igrat_sleduyuschim)
                        MediaMenuRow.ADD_TO_END -> Icons.AutoMirrored.Rounded.PlaylistPlay to stringResource(R.string.car_add_to_end)
                        MediaMenuRow.START_RADIO -> Icons.Rounded.Sensors to stringResource(R.string.radio_start)
                        else -> Icons.Rounded.Layers to stringResource(R.string.track_menu_add_to_queue)
                    }
                    CarPill(icon, label, u(84f), metal = row == MediaMenuRow.PLAY_NOW, Modifier.weight(1f)) { onButton(row) }
                }
            }
        }
        if (provider == org.tegro.yamp.domain.account.Provider.JELLYFIN) {
            Text(stringResource(R.string.car_album_no_radio), color = HiFi.TextTertiary, fontSize = 17.sp, modifier = Modifier.padding(top = u(16f)))
        }
    }
}

/** The album page's far side: the tracks in 88 dp rows; a tap plays the album from that track. */
@Composable
private fun CarAlbumTracks(page: CarAlbumPage, playing: org.tegro.yamp.domain.queue.TrackRef?, u: (Float) -> Dp, onTrack: (Int) -> Unit, modifier: Modifier) {
    Box(modifier) {
        when {
            page.loading -> Unit
            page.tracks.isEmpty() -> Text(
                stringResource(if (page.failed) R.string.catalog_load_failed else R.string.car_library_empty),
                color = HiFi.TextSecondary, fontSize = 22.sp, modifier = Modifier.padding(top = u(24f))
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = u(4f), bottom = u(60f))) {
                items(page.tracks.size, key = { page.tracks[it].ref.id + "#" + it }) { i ->
                    val track = page.tracks[i]
                    val on = track.ref == playing
                    val shape = RoundedCornerShape(u(22f))
                    Row(
                        Modifier.fillMaxWidth().height(u(Cb.TRACK_ROW)).clip(shape)
                            .then(if (on) Modifier.background(Color.White.copy(alpha = 0.10f), shape) else Modifier.drawBehind {
                                drawLine(HiFi.Hairline, androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f), androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f), 1f)
                            })
                            .hifiPress({ onTrack(i) })
                            .padding(horizontal = u(24f)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(u(40f)), contentAlignment = Alignment.Center) {
                            if (on) Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = HiFi.Accent, modifier = Modifier.size(u(30f)))
                            else Text("${i + 1}", color = HiFi.TextTertiary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(u(22f)))
                        Text(
                            track.title, color = if (track.available) Color.White else HiFi.TextTertiary, fontSize = 25.sp,
                            fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        track.durationMs?.let { ms ->
                            Spacer(Modifier.width(u(22f)))
                            Text(org.tegro.yamp.presentation.hifi.TimelineMath.clock(ms), color = HiFi.TextSecondary, fontSize = 20.sp)
                        }
                    }
                }
            }
        }
    }
}

/** The tracks a hold acts on, as a menu target. */
fun CarTile.target(albumWord: String, folderWord: String, account: org.tegro.yamp.domain.account.AccountId?): MediaTarget? = when (this) {
    is CarTile.OfAlbum -> MediaTarget.of(album, albumWord)
    is CarTile.OfFolder -> MediaTarget(folder.ref, folder.title, folderWord, folder.cover?.key, isArtist = false, account = account, pinned = pinned)
    else -> null
}
