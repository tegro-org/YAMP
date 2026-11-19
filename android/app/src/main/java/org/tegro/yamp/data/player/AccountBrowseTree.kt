package org.tegro.yamp.data.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.tegro.yamp.data.folders.PinnedFolder
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.account.ProviderAccount
import org.tegro.yamp.domain.media.Cover
import org.tegro.yamp.domain.media.NaturalOrder
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.provider.MusicProvider
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.Page
import org.tegro.yamp.domain.provider.getOrNull
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef
import org.tegro.yamp.provider.jellyfin.JellyfinRefs

/** Where a pick in the car plays (the app's rules, D84). */
interface BrowsePlayer {
    /** [tracks] of [account] from [startId] (a track ref's id; null: the first). */
    fun play(account: AccountId, tracks: List<Track>, startId: String?)

    /** Whether [account]'s My Wave can be started from the car. */
    fun hasWave(account: AccountId): Boolean

    fun playWave(account: AccountId)
}

/** The tree's own words, from resources. */
data class BrowseLabels(
    val wave: String,
    val waveSubtitle: String,
    /** My Wave's icon (a bundled image). */
    val waveArt: Uri?,
    val liked: String,
    val favourites: String,
    val playlists: String,
    val albums: String,
    val folders: String,
    val downloaded: String,
    val playAll: String
)

/**
 * The accounts' part of the Android Auto browse tree (gap (d)): every signed-in account, Yandex and Jellyfin — a
 * folder per account, or the only account's rows right at the root. Yandex: My Wave · Liked · Playlists · Albums ·
 * Downloaded; Jellyfin: Favourites · Playlists · Albums · Folders (the Phase 4b tree, a folder playable as a whole) ·
 * Downloaded. Everything comes through the account's [MusicProvider] and plays through [player] in that account
 * (the app's rule, D84). Covers go through [artwork] (Android Auto loads `content://` art only, and never sends
 * the Jellyfin header).
 *
 * Ids: a node is `n|<kind>|<account>|<arg>` (arg: a [MediaRef] value, a search text, or empty); a track is its
 * node's id + `|` + the track's ref. Account keys, refs and ids never hold `|`; no id holds a token.
 */
class AccountBrowseTree(
    private val accounts: () -> List<ProviderAccount>,
    private val providers: (AccountId) -> MusicProvider?,
    private val pins: (AccountId) -> List<PinnedFolder>,
    private val isDownloaded: (TrackRef) -> Boolean,
    private val artwork: (String?) -> Uri?,
    private val labels: BrowseLabels,
    private val player: BrowsePlayer,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    /** Short memory of track lists, so the paging of one list and the pick that follows don't ask again. */
    private val memo = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Track>>>()

    /** The root's account part: a folder per account, or the only account's rows; nothing when nobody is signed in. */
    fun top(): List<MediaItem> {
        val all = accounts()
        return when (all.size) {
            0 -> emptyList()
            1 -> rowsOf(all.single())
            else -> all.map { account ->
                folder(node(ACCOUNT, account.id), account.displayName, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, subtitle = serviceName(account.provider))
            }
        }
    }

    /** Whether [mediaId] belongs to this part of the tree. */
    fun owns(mediaId: String): Boolean = mediaId.startsWith(NODE + SEP)

    /** Children of an account node; null for an unknown node or an account that is not signed in. */
    suspend fun children(parentId: String): List<MediaItem>? {
        val id = parse(parentId) ?: return null
        if (id.trackRef != null) return null
        val account = accounts().firstOrNull { it.id == id.account } ?: return null
        val provider = providers(account.id) ?: return null
        return when (id.kind) {
            ACCOUNT -> rowsOf(account)
            PLAYLISTS -> provider.library?.playlists()?.getOrNull()?.sortedWith(compareBy(NaturalOrder) { it.title })?.map {
                folder(node(PLAYLIST, account.id, it.ref.value), it.title, MediaMetadata.MEDIA_TYPE_PLAYLIST, cover = it.cover)
            }
            ALBUMS -> allPages { provider.browse?.albums(it) }?.sortedWith(compareBy(NaturalOrder) { it.title })?.map { album ->
                folder(
                    node(ALBUM, account.id, album.ref.value), album.title, MediaMetadata.MEDIA_TYPE_ALBUM,
                    subtitle = album.artists.joinToString(", ") { it.name }, cover = album.cover
                )
            }
            FOLDERS, FOLDER -> folderLevel(account.id, provider, id.arg.takeIf { id.kind == FOLDER }?.let(MediaRef::parse))
            else -> tracksOf(id, provider)?.map { trackItem(parentId, it) }
        }
    }

    /** A single item: a track of a known list, or a node of an account (found among its parent's children). */
    suspend fun item(mediaId: String): MediaItem? {
        val id = parse(mediaId) ?: return null
        if (id.trackRef != null) return children(mediaId.substringBeforeLast(SEP))?.firstOrNull { it.mediaId == mediaId }
        val parent = when (id.kind) {
            ACCOUNT -> return top().firstOrNull { it.mediaId == mediaId }
            PLAYLIST -> node(PLAYLISTS, id.account)
            ALBUM -> node(ALBUMS, id.account)
            // A folder's parent is not in its id: the item is rebuilt plainly
            FOLDER -> return folder(mediaId, labels.folders, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, playable = true)
            PLAY_FOLDER -> return playable(mediaId, labels.playAll, "", null, MediaMetadata.MEDIA_TYPE_PLAYLIST)
            else -> node(ACCOUNT, id.account)
        }
        return children(parent)?.firstOrNull { it.mediaId == mediaId }
    }

    /**
     * What a pick starts: a track plays its list from it, a list or a folder plays whole, My Wave starts the wave —
     * all in the item's account. Null for an unknown id, an account that is not signed in or an empty list.
     */
    suspend fun startAction(mediaId: String): (() -> Unit)? {
        val id = parse(mediaId) ?: return null
        val account = accounts().firstOrNull { it.id == id.account }?.id ?: return null
        val provider = providers(account) ?: return null
        if (id.kind == WAVE) return if (player.hasWave(account)) ({ player.playWave(account) }) else null
        val tracks = tracksOf(id, provider)?.takeIf { it.isNotEmpty() } ?: return null
        val start = id.trackRef?.let { ref -> tracks.firstOrNull { it.ref.value == ref }?.ref?.id ?: return null }
        return { player.play(account, tracks, start) }
    }

    /** Search results for the car's search: each account's tracks, then its albums, in the accounts' order. */
    suspend fun search(query: String): List<MediaItem> {
        val text = query.replace(SEP, ' ').trim()
        if (text.isEmpty()) return emptyList()
        val all = accounts()
        return all.flatMap { account ->
            val found = providers(account.id)?.search?.search(text)?.getOrNull() ?: return@flatMap emptyList()
            val searchNode = node(SEARCH, account.id, text)
            remember(searchNode, found.tracks)
            val who = account.displayName.takeIf { all.size > 1 }
            found.tracks.map { trackItem(searchNode, it, who) } +
                found.albums.map { album ->
                    folder(
                        node(ALBUM, account.id, album.ref.value), album.title, MediaMetadata.MEDIA_TYPE_ALBUM,
                        subtitle = listOfNotNull(who, album.artists.joinToString(", ") { it.name }.ifBlank { null }).joinToString(" · "),
                        cover = album.cover
                    )
                }
        }
    }

    /** A voice "play <text>": the first account whose search finds tracks plays them from the top hit. */
    suspend fun voiceSearch(query: String): (() -> Unit)? {
        val text = query.replace(SEP, ' ').trim()
        if (text.isEmpty()) return null
        for (account in accounts()) {
            val tracks = providers(account.id)?.search?.search(text)?.getOrNull()?.tracks.orEmpty()
            if (tracks.isNotEmpty()) return { player.play(account.id, tracks, tracks.first().ref.id) }
        }
        return null
    }

    private fun rowsOf(account: ProviderAccount): List<MediaItem> {
        val id = account.id
        return if (account.provider == Provider.JELLYFIN) {
            listOf(
                folder(node(LIKED, id), labels.favourites, MediaMetadata.MEDIA_TYPE_PLAYLIST),
                folder(node(PLAYLISTS, id), labels.playlists, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folder(node(ALBUMS, id), labels.albums, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                folder(node(FOLDERS, id), labels.folders, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                folder(node(DOWNLOADED, id), labels.downloaded, MediaMetadata.MEDIA_TYPE_PLAYLIST)
            )
        } else {
            listOfNotNull(
                playable(node(WAVE, id), labels.wave, labels.waveSubtitle, labels.waveArt, MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .takeIf { player.hasWave(id) },
                folder(node(LIKED, id), labels.liked, MediaMetadata.MEDIA_TYPE_PLAYLIST),
                folder(node(PLAYLISTS, id), labels.playlists, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folder(node(ALBUMS, id), labels.albums, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                folder(node(DOWNLOADED, id), labels.downloaded, MediaMetadata.MEDIA_TYPE_PLAYLIST)
            )
        }
    }

    /**
     * A folder level as the phone shows it: "Play all" first (inside a folder), pinned folders, the other folders,
     * then the loose tracks. At the root the pins come from the pin list (a pin may sit deeper in the tree).
     */
    private suspend fun folderLevel(account: AccountId, provider: MusicProvider, folderRef: MediaRef?): List<MediaItem>? {
        val folders = provider.folders ?: return null
        val level = (if (folderRef == null) folders.root() else folders.level(folderRef)).getOrNull() ?: return null
        val pinned = pins(account)
        val pinnedIds = pinned.map { it.id }.toSet()
        val top = if (folderRef == null) {
            pinned.map { pin ->
                level.folders.firstOrNull { it.ref.id == pin.id }?.let { it.ref to it.title }
                    ?: (MediaRef(JellyfinRefs.PROVIDER + MediaRef.INSTANCE_SEPARATOR + account.id.substringBefore('/'), JellyfinRefs.KIND_FOLDER, pin.id) to pin.title)
            }
        } else {
            level.folders.filter { it.ref.id in pinnedIds }.map { it.ref to it.title }
        }
        val coverOf = level.folders.associate { it.ref.id to it.cover }
        val pinCovers = pinned.associate { it.id to it.cover }
        val levelNode = if (folderRef == null) node(FOLDERS, account) else node(FOLDER, account, folderRef.value)
        remember(levelNode, level.tracks)
        return listOfNotNull(
            folderRef?.let { playable(node(PLAY_FOLDER, account, it.value), labels.playAll, "", null, MediaMetadata.MEDIA_TYPE_PLAYLIST) }
        ) +
            top.map { (ref, title) -> folderItem(account, ref, title, coverOf[ref.id]?.url(ART_PX) ?: pinCovers[ref.id]?.replace(SIZE, ART_PX.toString())) } +
            level.folders.filter { it.ref.id !in pinnedIds }.map { folderItem(account, it.ref, it.title, it.cover?.url(ART_PX)) } +
            level.tracks.map { trackItem(levelNode, it) }
    }

    /** A folder: opens (browsable) and plays whole (playable) — Android Auto shows it as a folder, a voice pick plays it. */
    private fun folderItem(account: AccountId, ref: MediaRef, title: String, coverUrl: String?): MediaItem =
        folder(node(FOLDER, account, ref.value), title, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, playable = true, coverUrl = coverUrl)

    /** The tracks a list node plays (a track id plays its node's list); null where the node has none. */
    private suspend fun tracksOf(id: NodeId, provider: MusicProvider): List<Track>? {
        val key = node(id.kind, id.account, id.arg)
        val ref = MediaRef.parse(id.arg)
        val wholeFolder = id.kind == PLAY_FOLDER || (id.kind == FOLDER && id.trackRef == null)
        val memoKey = if (wholeFolder) node(PLAY_FOLDER, id.account, id.arg) else key
        memo[memoKey]?.takeIf { nowMs() - it.first < MEMO_MS }?.let { return it.second }
        val tracks = when {
            id.kind == LIKED -> provider.library?.likedTracks()?.getOrNull()
            id.kind == DOWNLOADED -> provider.library?.likedTracks()?.getOrNull()?.filter { isDownloaded(it.ref) }
            id.kind == PLAYLIST && ref != null -> provider.library?.playlistTracks(ref)?.getOrNull()
            id.kind == ALBUM && ref != null -> provider.catalog?.album(ref)?.getOrNull()?.tracks
            wholeFolder && ref != null -> provider.folders?.tracks(ref)?.getOrNull()
            // A loose track of a level: the level's tracks
            id.kind == FOLDER && ref != null -> provider.folders?.level(ref)?.getOrNull()?.tracks
            id.kind == FOLDERS -> provider.folders?.root()?.getOrNull()?.tracks
            id.kind == SEARCH -> provider.search?.search(id.arg)?.getOrNull()?.tracks
            else -> null
        } ?: return null
        remember(memoKey, tracks)
        return tracks
    }

    private fun remember(key: String, tracks: List<Track>) {
        if (memo.size > MEMO_MAX) memo.clear()
        memo[key] = nowMs() to tracks
    }

    private fun trackItem(nodeId: String, track: Track, lead: String? = null): MediaItem = playable(
        nodeId + SEP + track.ref.value,
        track.title,
        listOfNotNull(lead, track.artists.joinToString(", ") { it.name }.ifBlank { null }).joinToString(" · "),
        artwork(track.cover?.url(ART_PX)),
        MediaMetadata.MEDIA_TYPE_MUSIC
    )

    private fun folder(
        id: String,
        title: String,
        mediaType: Int,
        subtitle: String? = null,
        cover: Cover? = null,
        playable: Boolean = false,
        coverUrl: String? = cover?.url(ART_PX)
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .setArtworkUri(artwork(coverUrl))
                .setIsBrowsable(true)
                .setIsPlayable(playable)
                .setMediaType(mediaType)
                .build()
        )
        .build()

    private fun playable(id: String, title: String, subtitle: String, art: Uri?, mediaType: Int): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .setArtworkUri(art)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(mediaType)
                .build()
        )
        .build()

    private fun serviceName(provider: Provider): String = if (provider == Provider.JELLYFIN) "Jellyfin" else "Yandex"

    /** Every page of a paged list (up to [MAX_PAGES]); null when the first fails. */
    private suspend fun <T> allPages(call: suspend (Page.Cursor?) -> Outcome<Page<T>>?): List<T>? {
        val all = mutableListOf<T>()
        var cursor: Page.Cursor? = null
        repeat(MAX_PAGES) {
            when (val page = call(cursor)) {
                null -> return null
                is Outcome.Err -> return if (all.isEmpty()) null else all
                is Outcome.Ok -> {
                    all += page.value.items
                    cursor = page.value.next ?: return all
                }
            }
        }
        return all
    }

    /** A parsed id: [trackRef] is set for a track item. */
    private data class NodeId(val kind: String, val account: AccountId, val arg: String, val trackRef: String?)

    private fun parse(mediaId: String): NodeId? {
        val parts = mediaId.split(SEP, limit = 5)
        if (parts.size < 4 || parts[0] != NODE || parts[2].isEmpty()) return null
        return NodeId(parts[1], AccountId(parts[2]), parts[3], parts.getOrNull(4)?.takeIf { TrackRef.parse(it) != null })
            .takeIf { parts.size == 4 || it.trackRef != null }
    }

    companion object {
        private const val NODE = "n"
        private const val SEP = '|'
        const val ACCOUNT = "acc"
        const val WAVE = "wave"
        const val LIKED = "liked"
        const val DOWNLOADED = "dl"
        const val PLAYLISTS = "pls"
        const val PLAYLIST = "pl"
        const val ALBUMS = "albs"
        const val ALBUM = "alb"
        const val FOLDERS = "fds"
        const val FOLDER = "fd"
        const val PLAY_FOLDER = "fdall"
        const val SEARCH = "srch"

        /** A node's id: `n|<kind>|<account>|<arg>`. */
        fun node(kind: String, account: AccountId, arg: String = ""): String = listOf(NODE, kind, account.value, arg).joinToString(SEP.toString())

        /** Covers at the car's list size (the Medium cover tier). */
        private const val ART_PX = 400
        private const val SIZE = "{px}"
        private const val MAX_PAGES = 20
        private const val MEMO_MS = 60_000L
        private const val MEMO_MAX = 64
    }
}
