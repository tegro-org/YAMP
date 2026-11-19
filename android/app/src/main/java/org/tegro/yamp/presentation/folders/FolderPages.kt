package org.tegro.yamp.presentation.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tegro.yamp.R
import org.tegro.yamp.data.folders.PinnedFolder
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.media.Folder
import org.tegro.yamp.domain.media.FolderLevel
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.presentation.account.AccountMark
import org.tegro.yamp.presentation.account.AccountTag
import org.tegro.yamp.presentation.catalog.CatalogActions
import org.tegro.yamp.presentation.catalog.CatalogPageState
import org.tegro.yamp.presentation.catalog.FolderPage
import org.tegro.yamp.presentation.catalog.OfflineBanner
import org.tegro.yamp.presentation.hifi.GlassCircleButton
import org.tegro.yamp.presentation.hifi.GlassPillButton
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiEmptyState
import org.tegro.yamp.presentation.hifi.HiFiErrorState
import org.tegro.yamp.presentation.hifi.HiFiSectionHeader
import org.tegro.yamp.presentation.hifi.HiFiSkeletonRows
import org.tegro.yamp.presentation.hifi.HiFiType
import org.tegro.yamp.presentation.hifi.MetalPillButton
import org.tegro.yamp.presentation.hifi.hifiGlass
import org.tegro.yamp.presentation.hifi.hifiMachined
import org.tegro.yamp.presentation.hifi.hifiPress
import org.tegro.yamp.presentation.hifi.predictiveBack
import org.tegro.yamp.presentation.hifi.rememberPredictiveBack
import org.tegro.yamp.presentation.main.components.TrackItem
import org.tegro.yamp.presentation.media.LocalMediaMenu
import org.tegro.yamp.presentation.media.MediaTarget
import org.tegro.yamp.utils.russianQuantityString

/** The Folders root of [account] (Library → the account → Folders). */
fun rootFolderPage(account: AccountId, title: String) = FolderPage(null, title, account)

/**
 * What one level shows (canvas `pf-root`, `pf-level`): the folders and tracks that match [filter] (case-insensitive,
 * this level only), pinned folders first. On the root every pin of the account is in "Pinned" — deeper ones too, the
 * root is the way in; on a level only its own pinned subfolders move up. Pure; unit-tested.
 */
data class FolderLevelView(val pinned: List<Folder>, val others: List<Folder>, val tracks: List<Track>) {
    val isEmpty: Boolean get() = pinned.isEmpty() && others.isEmpty() && tracks.isEmpty()

    companion object {
        fun of(level: FolderLevel, pins: List<PinnedFolder>, root: Boolean, filter: String, ofPin: (PinnedFolder) -> Folder): FolderLevelView {
            val text = filter.trim()
            fun hit(name: String) = text.isEmpty() || name.contains(text, ignoreCase = true)
            val pinIds = pins.map { it.id }.toSet()
            val pinned = if (root) {
                pins.map { pin -> level.folders.firstOrNull { it.ref.id == pin.id } ?: ofPin(pin) }
            } else {
                level.folders.filter { it.ref.id in pinIds }
            }
            return FolderLevelView(
                pinned = pinned.filter { hit(it.title) },
                others = level.folders.filter { it.ref.id !in pinIds && hit(it.title) },
                tracks = level.tracks.filter { hit(it.title) }
            )
        }
    }
}

/** "8 folders" / "97 tracks" lines of a folder (the service gives items inside and tracks under it). */
@Composable
internal fun folderSubtitle(folder: Folder): String = listOfNotNull(
    folder.trackCount?.let { russianQuantityString(it, R.string.tracks_count_one, R.string.tracks_count_few, R.string.tracks_count_many) }
).joinToString(" · ")

/** What the folder page needs beyond the catalog's actions. */
class FolderActions(
    val onPlay: (account: AccountId, folderId: String, shuffle: Boolean) -> Unit,
    val onOpen: (FolderPage) -> Unit,
    val onJump: (FolderPage) -> Unit,
    val onPlayTracks: (account: AccountId, tracks: List<Track>, trackId: String?) -> Unit
)

/**
 * A level of an account's folder tree (Phase 4b, canvas `pf-*`): breadcrumbs, the level's head with Play / Shuffle
 * (the root: title, account and the filter), pinned folders on top, then the rest, then the loose tracks. A row's tap
 * opens it, its play button plays the whole folder, a hold opens the folder menu. Landscape: `pf-land`.
 */
@Composable
fun FolderPageScreen(
    state: CatalogPageState,
    pins: List<PinnedFolder>,
    accountTag: AccountTag?,
    contentBottom: Dp,
    actions: CatalogActions,
    folderActions: FolderActions,
    currentTrackId: String? = null,
    isPlaying: Boolean = false,
    landscape: Boolean = false
) {
    val page = state.page as FolderPage
    val back = rememberPredictiveBack(enabled = true, onBack = actions.onBack)
    var filter by rememberSaveable(page) { mutableStateOf("") }
    val root = page.folderId == null
    val level = state.folder
    val view = remember(level, pins, filter, root) {
        level?.let { l -> FolderLevelView.of(l, pins, root, filter) { Folder(it.ref(page), it.title, it.cover?.let(::KeyCover)) } }
    }
    val menu = LocalMediaMenu.current
    val folderWord = stringResource(R.string.folder_word)
    val pinIds = remember(pins) { pins.map { it.id }.toSet() }
    val tracksWord: @Composable (Folder) -> String = { f -> listOf(folderWord, folderSubtitle(f)).filter(String::isNotEmpty).joinToString(" · ") }
    val holdTargets = view?.let { v -> (v.pinned + v.others).associateWith { f -> MediaTarget.of(f, tracksWord(f), page.account, f.ref.id in pinIds) } }.orEmpty()
    val hold: (Folder) -> (() -> Unit)? = { f -> menu?.let { m -> holdTargets[f]?.let { t -> { m.open(t) } } } }
    val headTarget = page.folderId?.let { id ->
        MediaTarget(page.refOf(id), page.title, folderWord, page.cover, isArtist = false, account = page.account, pinned = id in pinIds)
    }
    val row: @Composable (Folder, Boolean, Boolean) -> Unit = { f, pinned, last ->
        FolderRow(
            folder = f, pinned = pinned, last = last, match = filter,
            onOpen = { folderActions.onOpen(page.child(f.ref.id, f.title, f.cover?.key)) },
            onPlay = { folderActions.onPlay(page.account, f.ref.id, false) },
            onHold = hold(f)
        )
    }

    if (landscape) {
        FolderPageLandscape(page, state, view, accountTag, filter, { filter = it }, contentBottom, actions, folderActions, row, currentTrackId, isPlaying)
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = contentBottom),
        modifier = Modifier.fillMaxSize().predictiveBack(back).navigationBarsPadding()
    ) {
        item(key = "top") {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp)) {
                FolderTopRow(page, accountTag, actions.onBack, folderActions.onJump, more = headTarget?.takeIf { menu != null }?.let { t -> { menu?.open(t) } })
                if (root) {
                    Row(Modifier.padding(start = 58.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        accountTag?.let { AccountMark(it, 16.dp) }
                        Spacer(Modifier.width(6.dp))
                        val count = level?.folders?.size
                        Text(
                            listOfNotNull(accountTag?.name, count?.let { foldersCount(it) }).joinToString(" · "),
                            style = HiFiType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    FilterField(filter, { filter = it }, stringResource(R.string.folders_filter))
                } else {
                    Spacer(Modifier.height(14.dp))
                    LevelHead(page.title, page.cover, level, onPlay = { folderActions.onPlay(page.account, page.folderId.orEmpty(), it) })
                    Spacer(Modifier.height(12.dp))
                    FilterField(filter, { filter = it }, stringResource(R.string.folders_filter_here))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (state.offline) item(key = "offline") { OfflineBanner(state.serverName, actions.onRetry) }
        levelItems(state, view, root, filter, row, folderActions, currentTrackId, isPlaying, actions.onRetry)
    }
}

/** The folder of a pin that is not on this level (a deeper pin on the root): enough to show and open it. */
private fun PinnedFolder.ref(page: FolderPage) = page.refOf(id)

/** The lists of a level, shared by portrait and landscape. */
private fun LazyListScope.levelItems(
    state: CatalogPageState,
    view: FolderLevelView?,
    root: Boolean,
    filter: String,
    row: @Composable (Folder, Boolean, Boolean) -> Unit,
    folderActions: FolderActions,
    currentTrackId: String?,
    isPlaying: Boolean,
    onRetry: () -> Unit
) {
    when {
        state.isLoading -> item(key = "loading") { HiFiSkeletonRows(Modifier.padding(top = 12.dp), count = 6, coverSize = 48.dp) }
        state.failed && view == null -> item(key = "failed") { HiFiErrorState(onRetry = onRetry, message = stringResource(R.string.catalog_load_failed)) }
        view == null -> Unit
        view.isEmpty -> item(key = "empty") {
            HiFiEmptyState(
                icon = Icons.Rounded.Search,
                title = stringResource(if (filter.isBlank()) R.string.folders_empty else R.string.folders_no_match)
            )
        }
        else -> {
            if (root) {
                if (view.pinned.isNotEmpty()) {
                    item(key = "pinned_title") {
                        HiFiSectionHeader(
                            if (filter.isBlank()) stringResource(R.string.folders_pinned) else stringResource(R.string.folders_pinned_n, view.pinned.size),
                            Modifier.padding(start = 4.dp, top = 4.dp)
                        )
                    }
                    items(view.pinned, key = { "p" + it.ref.id }) { f -> row(f, true, f == view.pinned.last()) }
                }
                item(key = "all_title") {
                    HiFiSectionHeader(
                        if (filter.isBlank()) stringResource(R.string.folders_all_n, view.pinned.size + view.others.size)
                        else stringResource(R.string.folders_others_n, view.others.size),
                        Modifier.padding(start = 4.dp, top = 14.dp)
                    )
                }
            } else {
                items(view.pinned, key = { "p" + it.ref.id }) { f -> row(f, true, view.others.isEmpty() && f == view.pinned.last()) }
            }
            items(view.others, key = { "f" + it.ref.id }) { f -> row(f, false, f == view.others.last()) }
            if (view.tracks.isNotEmpty()) {
                item(key = "tracks_title") {
                    HiFiSectionHeader(stringResource(R.string.folders_tracks_n, view.tracks.size), Modifier.padding(start = 4.dp, top = 12.dp))
                }
                items(view.tracks, key = { "t" + it.ref.value }) { track ->
                    TrackItem(
                        track = track,
                        isPlaying = isPlaying && track.ref.id == currentTrackId,
                        isLiked = false,
                        onToggleLike = {},
                        onClick = { state.page.account?.let { a -> folderActions.onPlayTracks(a, view.tracks, track.ref.id) } },
                        showLike = false
                    )
                }
            }
            if (root && filter.isNotBlank()) item(key = "note") {
                Text(stringResource(R.string.folders_filter_note), style = HiFiType.Caption, color = HiFi.TextTertiary, modifier = Modifier.padding(start = 4.dp, top = 14.dp))
            }
        }
    }
}

@Composable
private fun foldersCount(n: Int) = russianQuantityString(n, R.string.folders_count_one, R.string.folders_count_few, R.string.folders_count_many)

/** Back · the title (root) or the breadcrumbs (a level) · ⋮ (the folder menu, on a level). */
@Composable
private fun FolderTopRow(page: FolderPage, accountTag: AccountTag?, onBack: () -> Unit, onJump: (FolderPage) -> Unit, more: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassCircleButton(onClick = onBack, contentDescription = stringResource(R.string.cd_nazad), size = 44.dp) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        if (page.folderId == null) {
            Text(page.title, style = HiFiType.LargeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            Crumbs(page, accountTag, big = false, onJump = onJump, modifier = Modifier.weight(1f))
        }
        if (more != null) {
            Spacer(Modifier.width(8.dp))
            GlassCircleButton(onClick = more, contentDescription = stringResource(R.string.cd_media_menu), size = 44.dp) {
                Icon(Icons.Default.MoreVert, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * The breadcrumbs (canvas `Crumbs`): every level a chip that jumps to it, the current one lit; a long path keeps the
 * first and the last two, the middle collapsed to "…" (the "…" chip goes one level up).
 */
@Composable
fun Crumbs(page: FolderPage, accountTag: AccountTag?, big: Boolean, onJump: (FolderPage) -> Unit, modifier: Modifier = Modifier) {
    val all = page.path.map { it to false } + (FolderPage.Crumb(page.folderId, page.title) to true)
    val shown = if (all.size > MAX_CRUMBS) listOf(all.first(), null) + all.takeLast(2) else all
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        shown.forEachIndexed { i, entry ->
            if (i > 0) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = HiFi.TextTertiary, modifier = Modifier.size(if (big) 26.dp else 16.dp))
            val last = entry?.second == true
            val label = entry?.first?.title ?: stringResource(R.string.folders_crumbs_more)
            val target = entry?.first?.let { crumb -> if (last) null else page.up(crumb) } ?: if (entry == null) page.up(page.path[page.path.size - 2]) else null
            Row(
                Modifier
                    .then(if (last) Modifier.weight(1f, fill = false) else Modifier)
                    .height(if (big) 60.dp else 34.dp)
                    .then(if (last) Modifier.background(Color.White.copy(alpha = 0.16f), CircleShape).border(1.dp, HiFi.GlassBorder, CircleShape) else Modifier.hifiGlass(CircleShape))
                    .hifiPress({ target?.let(onJump) })
                    .padding(horizontal = if (big) 22.dp else 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (i == 0 && accountTag != null) {
                    AccountMark(accountTag, if (big) 28.dp else 16.dp)
                    Spacer(Modifier.width(if (big) 10.dp else 6.dp))
                }
                Text(
                    label, color = if (last) Color.White else HiFi.TextSecondary, fontSize = if (big) 20.sp else 13.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (last) Modifier else Modifier.widthIn(max = if (big) 560.dp else 200.dp)
                )
            }
        }
    }
}

/** A cover known only by its cache key (a pinned folder's). */
internal data class KeyCover(override val key: String) : org.tegro.yamp.domain.media.Cover {
    override fun url(sizePx: Int): String = key.replace("{px}", sizePx.toString())
}

private const val MAX_CRUMBS = 4

/** A level's head: art (or the placeholder), name, counts; Play / Shuffle. */
@Composable
private fun LevelHead(name: String, cover: String?, level: FolderLevel?, onPlay: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FolderArt(name, cover, 96.dp, radius = 16.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            level?.let { l ->
                val parts = listOfNotNull(
                    l.folders.size.takeIf { it > 0 }?.let { foldersCount(it) },
                    l.tracks.size.takeIf { it > 0 }?.let { russianQuantityString(it, R.string.tracks_count_one, R.string.tracks_count_few, R.string.tracks_count_many) }
                )
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = HiFiType.Caption, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetalPillButton(stringResource(R.string.cd_slushat), { onPlay(false) }, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow, height = 48.dp)
        GlassPillButton(stringResource(R.string.media_shuffle), { onPlay(true) }, Modifier.weight(1f), icon = Icons.Rounded.Shuffle, height = 48.dp)
    }
}

/** The filter on every level (canvas `Field`): a machined well with the search glyph and a clear button. */
@Composable
internal fun FilterField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(48.dp).hifiMachined(24.dp).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, color = Color.White.copy(alpha = 0.55f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(HiFi.Accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(36.dp).hifiPress({ onChange("") }), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_ochistit), tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** A folder row (canvas `FolderRow`): art or placeholder, name (the filter's match lit), pin + counts, the play button. */
@Composable
fun FolderRow(folder: Folder, pinned: Boolean, last: Boolean, match: String, onOpen: () -> Unit, onPlay: () -> Unit, onHold: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).hifiPress(onOpen, onLongClick = onHold).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FolderArt(folder.title, folder.cover?.key, 48.dp)
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(highlighted(folder.title, match), style = HiFiType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pinned) {
                        Icon(Icons.Rounded.PushPin, contentDescription = null, tint = HiFi.TextSecondary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(folderSubtitle(folder), style = HiFiType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            GlassCircleButton(onClick = onPlay, contentDescription = stringResource(R.string.folders_play_cd)) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
    if (!last) Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(1.dp).background(HiFi.Hairline))
}

/** [text] with [match] lit (the filter's hit). */
private fun highlighted(text: String, match: String) = buildAnnotatedString {
    val i = if (match.isBlank()) -1 else text.indexOf(match.trim(), ignoreCase = true)
    if (i < 0) {
        append(text)
    } else {
        append(text.substring(0, i))
        withStyle(SpanStyle(color = Color.White, background = Color(0x730A6CFF))) { append(text.substring(i, i + match.trim().length)) }
        append(text.substring(i + match.trim().length))
    }
}

/**
 * Phone landscape (canvas `pf-land`): the title row carries the account and the filter; on the root the pinned folders
 * keep a column of their own on the left and the rest flow in two columns; on a level the head with Play / Shuffle is
 * the left column and the level the right.
 */
@Suppress("LongParameterList")
@Composable
private fun FolderPageLandscape(
    page: FolderPage,
    state: CatalogPageState,
    view: FolderLevelView?,
    accountTag: AccountTag?,
    filter: String,
    onFilter: (String) -> Unit,
    contentBottom: Dp,
    actions: CatalogActions,
    folderActions: FolderActions,
    row: @Composable (Folder, Boolean, Boolean) -> Unit,
    currentTrackId: String?,
    isPlaying: Boolean
) {
    val back = rememberPredictiveBack(enabled = true, onBack = actions.onBack)
    val root = page.folderId == null
    Column(Modifier.fillMaxSize().predictiveBack(back).statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlassCircleButton(onClick = actions.onBack, contentDescription = stringResource(R.string.cd_nazad), size = 44.dp) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            if (root) {
                Text(page.title, style = HiFiType.LargeTitle, maxLines = 1)
                Spacer(Modifier.width(12.dp))
                accountTag?.let { AccountMark(it, 16.dp) }
                Spacer(Modifier.width(6.dp))
                Text(
                    listOfNotNull(accountTag?.name, state.folder?.folders?.size?.let { foldersCount(it) }).joinToString(" · "),
                    style = HiFiType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
            } else {
                Crumbs(page, accountTag, big = false, onJump = folderActions.onJump, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.width(12.dp))
            FilterField(filter, onFilter, stringResource(if (root) R.string.folders_filter else R.string.folders_filter_here), Modifier.width(300.dp))
        }
        if (state.offline) OfflineBanner(state.serverName, actions.onRetry)
        Row(Modifier.fillMaxSize().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            val listPadding = PaddingValues(bottom = contentBottom)
            if (root) {
                if (view != null && view.pinned.isNotEmpty()) {
                    LazyColumn(Modifier.width(280.dp).fillMaxHeight(), contentPadding = listPadding) {
                        item(key = "pinned_title") { HiFiSectionHeader(stringResource(R.string.folders_pinned), Modifier.padding(start = 4.dp)) }
                        items(view.pinned, key = { "p" + it.ref.id }) { f -> row(f, true, f == view.pinned.last()) }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(HiFi.Hairline))
                }
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = listPadding) {
                    if (view == null || state.isLoading || view.isEmpty) {
                        levelItems(state, view, root = false, filter, row, folderActions, currentTrackId, isPlaying, actions.onRetry)
                    } else {
                        item(key = "all_title") {
                            HiFiSectionHeader(stringResource(R.string.folders_all_n, view.pinned.size + view.others.size), Modifier.padding(start = 4.dp))
                        }
                        val pairs = view.others.chunked(2)
                        items(pairs, key = { p -> p.joinToString("|") { it.ref.id } }) { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                pair.forEach { f -> Box(Modifier.weight(1f)) { Column { row(f, false, pair == pairs.last()) } } }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.width(320.dp)) {
                    LevelHead(page.title, page.cover, state.folder, onPlay = { folderActions.onPlay(page.account, page.folderId.orEmpty(), it) })
                }
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = listPadding) {
                    levelItems(state, view, root = false, filter, row, folderActions, currentTrackId, isPlaying, actions.onRetry)
                }
            }
        }
    }
}
