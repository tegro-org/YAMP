package org.tegro.yamp.presentation.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tegro.yamp.R
import org.tegro.yamp.presentation.hifi.GlassCircleButton
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiActivityIndicator
import org.tegro.yamp.presentation.hifi.HiFiEmptyState
import org.tegro.yamp.presentation.hifi.HiFiErrorState
import org.tegro.yamp.presentation.hifi.HiFiSkeletonRows
import org.tegro.yamp.presentation.hifi.HiFiType
import org.tegro.yamp.presentation.hifi.hifiPress
import org.tegro.yamp.presentation.hifi.predictiveBack
import org.tegro.yamp.presentation.hifi.rememberPredictiveBack
import org.tegro.yamp.utils.russianQuantityString

/**
 * A Jellyfin library list (docs/phase-3-providers.md step 22, canvas section 11b): albums as tiles, artists,
 * playlists and genres as rows. Pages load as the end comes into view; while the server is away the last visit
 * shows under the banner (canvas `jf-unreachable`).
 */
@Composable
fun BrowsePageScreen(state: CatalogPageState, contentBottom: Dp, actions: CatalogActions) {
    val page = state.page as CatalogPage.Browse
    val back = rememberPredictiveBack(enabled = true, onBack = actions.onBack)
    val list = rememberLazyListState()
    val items = state.browse?.items.orEmpty()
    val tiles = page.kind == BrowseKind.ALBUMS || page.kind == BrowseKind.GENRE_ALBUMS
    val rows = remember(items, tiles) { if (tiles) items.chunked(2) else items.map { listOf(it) } }

    // The next page once the last row is near
    val nearEnd by remember(rows) {
        derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 3 }
    }
    LaunchedEffect(nearEnd, state.browse?.next) { if (nearEnd && state.browse?.next != null) actions.onLoadMore() }

    LazyColumn(
        state = list,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = contentBottom),
        modifier = Modifier.fillMaxSize().predictiveBack(back).navigationBarsPadding()
    ) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = 8.dp)) {
                GlassCircleButton(onClick = actions.onBack, contentDescription = stringResource(R.string.cd_nazad)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(page.title, style = HiFiType.LargeTitle.copy(fontSize = 28.sp, lineHeight = 34.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                state.serverName?.let {
                    Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        if (state.offline) item(key = "offline") { OfflineBanner(state.serverName, actions.onRetry) }
        when {
            state.isLoading -> item(key = "loading") { HiFiSkeletonRows(Modifier.padding(top = 12.dp), count = 6) }
            state.failed && items.isEmpty() -> item(key = "failed") {
                HiFiErrorState(onRetry = actions.onRetry, message = stringResource(R.string.catalog_load_failed))
            }
            items.isEmpty() -> item(key = "empty") {
                HiFiEmptyState(icon = Icons.Rounded.LibraryMusic, title = stringResource(R.string.jellyfin_list_empty))
            }
            else -> {
                items(rows, key = { row -> row.joinToString("|") { it.key } }) { row ->
                    if (tiles) {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { item -> Box(Modifier.weight(1f)) { BrowseTile(item, page, actions) } }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    } else {
                        BrowseRow(row.single(), page, actions)
                    }
                }
                if (state.browse?.loadingMore == true) item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { HiFiActivityIndicator() }
                }
            }
        }
    }
}

/** An album tile of a browse list. */
@Composable
private fun BrowseTile(item: BrowseItem, page: CatalogPage.Browse, actions: CatalogActions) {
    val album = (item as? BrowseItem.OfAlbum)?.album ?: return
    Column(Modifier.fillMaxWidth().hifiPress({ actions.onOpen(CatalogPage.Album(album.ref.id, album.title, page.account)) })) {
        Box(Modifier.fillMaxWidth()) { Cover(album.cover?.key, Icons.Rounded.Album, RoundedCornerShape(14.dp), TILE) }
        Spacer(Modifier.height(6.dp))
        Text(album.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            album.artists.joinToString { it.name }.ifEmpty { album.year?.toString().orEmpty() },
            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** An artist, playlist or genre row of a browse list. */
@Composable
private fun BrowseRow(item: BrowseItem, page: CatalogPage.Browse, actions: CatalogActions) {
    val account = page.account
    val (title, subtitle, open) = when (item) {
        is BrowseItem.OfArtist -> Triple(item.artist.name, null) { actions.onOpen(CatalogPage.Artist(item.artist.ref.id, item.artist.name, account)) }
        is BrowseItem.OfPlaylist -> Triple(
            item.playlist.title,
            russianQuantityString(item.playlist.trackCount, R.string.tracks_count_one, R.string.tracks_count_few, R.string.tracks_count_many)
        ) { actions.onOpen(CatalogPage.Playlist(item.playlist.ref.id, item.playlist.title, account)) }
        is BrowseItem.OfGenre -> Triple(item.genre.name, null) {
            actions.onOpen(CatalogPage.Browse(BrowseKind.GENRE_ALBUMS, item.genre.name, account, item.genre.ref.id))
        }
        is BrowseItem.OfAlbum -> Triple(item.album.title, null) { actions.onOpen(CatalogPage.Album(item.album.ref.id, item.album.title, account)) }
    }
    Row(
        Modifier.fillMaxWidth().height(64.dp).hifiPress(open),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (item) {
            is BrowseItem.OfArtist -> Cover(item.artist.cover?.key, Icons.Rounded.Person, CircleShape, ROW_COVER)
            is BrowseItem.OfPlaylist -> Cover(item.playlist.cover?.key, Icons.AutoMirrored.Rounded.PlaylistPlay, RoundedCornerShape(10.dp), ROW_COVER)
            is BrowseItem.OfGenre -> Cover(item.genre.cover?.key, Icons.Rounded.LibraryMusic, RoundedCornerShape(10.dp), ROW_COVER)
            is BrowseItem.OfAlbum -> Cover(item.album.cover?.key, Icons.Rounded.Album, RoundedCornerShape(10.dp), ROW_COVER)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1) }
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(22.dp))
    }
}

/** "Home Media isn't reachable…" over a Jellyfin page shown from the last visit; Retry asks at once. */
@Composable
internal fun OfflineBanner(serverName: String?, onRetry: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp, vertical = 8.dp)
            .clip(shape)
            .background(HiFi.Danger.copy(alpha = 0.10f))
            .border(1.dp, HiFi.Danger.copy(alpha = 0.35f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = HiFi.Danger, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.jellyfin_page_offline, serverName ?: "Jellyfin"),
            color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.state_retry), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.hifiPress(onRetry).padding(6.dp)
        )
    }
}

private val TILE = 160.dp
private val ROW_COVER = 48.dp
