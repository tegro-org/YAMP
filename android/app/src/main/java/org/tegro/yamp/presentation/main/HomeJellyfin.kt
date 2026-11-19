package org.tegro.yamp.presentation.main

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tegro.yamp.R
import org.tegro.yamp.data.folders.FolderPins
import org.tegro.yamp.data.folders.PinnedFolder
import org.tegro.yamp.data.player.PlayRequests
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.data.provider.ProviderRegistry
import org.tegro.yamp.data.provider.jellyfin.JellyfinAccountStore
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.provider.getOrNull
import org.tegro.yamp.presentation.account.AccountMark
import org.tegro.yamp.presentation.account.AccountTag
import org.tegro.yamp.presentation.catalog.BrowseKind
import org.tegro.yamp.presentation.catalog.CatalogPage
import org.tegro.yamp.presentation.catalog.FolderPage
import org.tegro.yamp.presentation.common.ProviderBadge
import org.tegro.yamp.presentation.folders.FolderArt
import org.tegro.yamp.presentation.hifi.GlassCircleButton
import org.tegro.yamp.presentation.hifi.GlassPillButton
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiSectionHeader
import org.tegro.yamp.presentation.hifi.HiFiTextAction
import org.tegro.yamp.presentation.hifi.HiFiType
import org.tegro.yamp.presentation.hifi.MetalPillButton
import org.tegro.yamp.presentation.hifi.hifiPress
import org.tegro.yamp.presentation.main.components.TrackItem
import org.tegro.yamp.presentation.media.LocalMediaMenu
import org.tegro.yamp.presentation.media.MediaTarget
import javax.inject.Inject

/**
 * One Jellyfin account's group on Home (gap (e), canvas `hj-home-c-scroll`): its pinned folders, the albums added
 * last and its favourites ([favouriteCount] of them; the first few shown).
 */
data class HomeJellyfinGroup(
    val account: AccountId,
    val name: String,
    val pins: List<PinnedFolder> = emptyList(),
    val recent: List<Album> = emptyList(),
    val favourites: List<Track> = emptyList(),
    val favouriteCount: Int = 0
) {
    val isEmpty: Boolean get() = pins.isEmpty() && recent.isEmpty() && favourites.isEmpty()
}

/** Home's Jellyfin groups, one per signed-in Jellyfin account in sign-in order (Settings → Accounts). */
@HiltViewModel
class HomeJellyfinViewModel @Inject constructor(
    accounts: JellyfinAccountStore,
    pins: FolderPins,
    private val registry: ProviderRegistry,
    private val catalog: PlayRequests,
    private val playbackController: PlaybackController
) : ViewModel() {

    private val loaded = MutableStateFlow<Map<String, HomeJellyfinGroup>>(emptyMap())
    private val _groups = MutableStateFlow<List<HomeJellyfinGroup>>(emptyList())
    val groups: StateFlow<List<HomeJellyfinGroup>> = _groups.asStateFlow()

    init {
        viewModelScope.launch {
            combine(accounts.accounts, pins.pins, loaded) { list, pinned, data ->
                list.map { account ->
                    (data[account.id.value] ?: HomeJellyfinGroup(account.id, account.userName))
                        .copy(name = account.label.ifBlank { account.userName }, pins = pinned[account.id.value].orEmpty())
                }
            }.collect { _groups.value = it }
        }
        viewModelScope.launch {
            accounts.accounts.collect { list -> list.forEach { launch { load(it.id) } } }
        }
    }

    /** Home came into view again: the newest albums and favourites once more. */
    fun refresh() {
        _groups.value.forEach { group -> viewModelScope.launch { load(group.account) } }
    }

    private suspend fun load(account: AccountId) {
        val provider = registry.provider(account) ?: return
        val recent = provider.browse?.recentAlbums(RECENT)?.getOrNull()
        val liked = provider.library?.likedTracks()?.getOrNull()
        loaded.update { map ->
            val now = map[account.value] ?: HomeJellyfinGroup(account, "")
            map + (account.value to now.copy(
                recent = recent ?: now.recent,
                favourites = liked?.take(FAVOURITES) ?: now.favourites,
                favouriteCount = liked?.size ?: now.favouriteCount
            ))
        }
    }

    /** The favourites card's Play / Shuffle: all of the account's favourites, in its own queue. */
    fun playFavourites(account: AccountId, shuffle: Boolean, from: Track? = null) {
        viewModelScope.launch {
            val liked = registry.provider(account)?.library?.likedTracks()?.getOrNull().orEmpty()
            if (liked.isEmpty()) return@launch
            catalog.userStarted()
            playbackController.playInAccount(account, if (shuffle) liked.shuffled() else liked, from?.ref?.id)
        }
    }

    private companion object {
        const val RECENT = 8
        const val FAVOURITES = 3
    }
}

/** What Home's groups do; MainScreen wires them to the catalog stack and the player. */
class HomeJellyfinActions(
    val onOpen: (CatalogPage) -> Unit,
    val onPlayFolder: (AccountId, String, Boolean) -> Unit,
    val onPlayFavourites: (AccountId, Boolean, Track?) -> Unit
)

/** How much of the first Jellyfin group shows above the tab bar on the first screen (canvas `hj-home-c`). */
internal val HomeGroupPeek = 56.dp

/**
 * Home with Jellyfin groups (gap (e)): the hero — the first tab as it is — fills the first screen but for a strip
 * where the first group starts, then the groups follow and Home scrolls. Once the hero is mostly scrolled off, the
 * title collapses into one row with the queue dropdown ([collapsedTitle]) and [onHeroHidden] says so (the mini player
 * comes back).
 */
@Composable
internal fun HomeWithGroups(
    groups: List<HomeJellyfinGroup>,
    tags: List<AccountTag>,
    headers: Boolean,
    contentBottom: Dp,
    /** The hero's own clearance above the tab bar (and the mini player): the first group starts above it. */
    heroBottom: Dp,
    collapsedTitle: String,
    queueName: String,
    controlsOnLeft: Boolean,
    actions: HomeJellyfinActions,
    onHeroHidden: (Boolean) -> Unit,
    scroll: ScrollState = rememberScrollState(),
    hero: @Composable (bottom: Dp) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        val density = LocalDensity.current
        val heroHeight = (viewport - heroBottom - HomeGroupPeek).coerceAtLeast(viewport / 2)
        val hidden by remember(heroHeight) { derivedStateOf { scroll.value > with(density) { (heroHeight * 0.6f).toPx() } } }
        LaunchedEffect(hidden) { onHeroHidden(hidden) }
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth().height(heroHeight)) { hero(0.dp) }
            groups.forEach { group ->
                HomeJellyfinGroupView(group, tags.firstOrNull { it.key == group.account.value }, headers, actions)
                Spacer(Modifier.height(18.dp))
            }
            Spacer(Modifier.height(contentBottom))
        }
        if (hidden) {
            // The collapsed title row (canvas `hj-home-c-scroll`): the title and the queue dropdown stay reachable
            Box(Modifier.fillMaxWidth().background(HiFi.Ground.copy(alpha = 0.72f)).statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (controlsOnLeft) {
                        HomeQueueDropdown(queueName, onLeft = true)
                        Spacer(Modifier.weight(1f))
                        Text(collapsedTitle, color = Color.White, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    } else {
                        Text(
                            collapsedTitle, color = Color.White, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        HomeQueueDropdown(queueName, onLeft = false)
                    }
                }
            }
        }
    }
}

/** One account's group: its header (marker, name, J) when [header], Pinned folders · Recently added · Favourites. */
@Composable
internal fun HomeJellyfinGroupView(group: HomeJellyfinGroup, tag: AccountTag?, header: Boolean, actions: HomeJellyfinActions) {
    val foldersTitle = stringResource(R.string.folders_title)
    val albumsTitle = stringResource(R.string.jellyfin_lib_albums)
    val favouritesTitle = stringResource(R.string.home_favourites)
    Column(Modifier.fillMaxWidth()) {
        if (header) {
            Row(Modifier.padding(horizontal = 20.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                tag?.let { AccountMark(it, 24.dp) }
                Spacer(Modifier.width(10.dp))
                Text(group.name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(10.dp))
                ProviderBadge(Provider.JELLYFIN, size = 16.dp)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f).height(1.dp).background(HiFi.Hairline))
            }
        }
        if (group.pins.isNotEmpty()) {
            HiFiSectionHeader(stringResource(R.string.home_pinned_folders), Modifier.padding(start = 20.dp, end = 12.dp)) {
                HiFiTextAction(stringResource(R.string.home_all_folders), { actions.onOpen(FolderPage(null, foldersTitle, group.account)) })
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(group.pins, key = { "pin" + it.id }) { pin ->
                    Column(Modifier.width(Tile).hifiPress({
                        actions.onOpen(FolderPage(pin.id, pin.title, group.account, listOf(FolderPage.Crumb(null, foldersTitle)), pin.cover))
                    })) {
                        Box {
                            FolderArt(pin.title, pin.cover, Tile, radius = 18.dp)
                            GlassCircleButton(
                                onClick = { actions.onPlayFolder(group.account, pin.id, false) },
                                contentDescription = stringResource(R.string.folders_play_cd),
                                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                            ) { Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                        }
                        Text(pin.title, style = HiFiType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        if (group.recent.isNotEmpty()) {
            HiFiSectionHeader(stringResource(R.string.home_recently_added), Modifier.padding(start = 20.dp, end = 12.dp, top = 10.dp)) {
                HiFiTextAction(stringResource(R.string.home_all), { actions.onOpen(CatalogPage.Browse(BrowseKind.ALBUMS, albumsTitle, group.account)) })
            }
            val menu = LocalMediaMenu.current
            val albumWord = stringResource(R.string.media_album_word)
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(group.recent, key = { it.ref.value }) { album ->
                    Column(
                        Modifier.width(Tile).hifiPress(
                            { actions.onOpen(CatalogPage.Album(album.ref.id, album.title, group.account)) },
                            onLongClick = menu?.let { m -> { m.open(MediaTarget.of(album, albumWord)) } }
                        )
                    ) {
                        org.tegro.yamp.presentation.catalog.Cover(album.cover?.key, Icons.Rounded.Album, RoundedCornerShape(18.dp), Tile)
                        Text(album.title, style = HiFiType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        Text(album.artists.joinToString { it.name }, style = HiFiType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (group.favourites.isNotEmpty()) {
            HiFiSectionHeader(stringResource(R.string.home_favourites), Modifier.padding(start = 20.dp, end = 12.dp, top = 10.dp)) {
                HiFiTextAction(
                    stringResource(R.string.home_all_n, group.favouriteCount),
                    { actions.onOpen(CatalogPage.Browse(BrowseKind.FAVOURITES, favouritesTitle, group.account)) }
                )
            }
            val shape = RoundedCornerShape(22.dp)
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                    .background(HiFi.GlassFill, shape).border(1.dp, HiFi.GlassBorder, shape)
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)
            ) {
                group.favourites.forEach { track ->
                    TrackItem(track = track, isPlaying = false, onClick = { actions.onPlayFavourites(group.account, false, track) }, showLike = false)
                }
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetalPillButton(stringResource(R.string.cd_slushat), { actions.onPlayFavourites(group.account, false, null) }, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow, height = 44.dp)
                    GlassPillButton(stringResource(R.string.media_shuffle), { actions.onPlayFavourites(group.account, true, null) }, Modifier.weight(1f), icon = Icons.Rounded.Shuffle, height = 44.dp)
                }
            }
        }
    }
}

private val Tile = 112.dp
