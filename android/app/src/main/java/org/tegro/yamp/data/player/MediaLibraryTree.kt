package org.tegro.yamp.data.player

import org.tegro.yamp.utils.CoverSize
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.tegro.yamp.BuildConfig
import org.tegro.yamp.R
import org.tegro.yamp.data.account.AccountContext
import org.tegro.yamp.data.folders.FolderPins
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.data.provider.ProviderRegistry
import org.tegro.yamp.data.provider.radio.FmRadio
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.account.accountId
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.utils.toCoverUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

/**
 * Browse tree for Android Auto, Android Automotive and other media browsers: the signed-in accounts
 * ([AccountBrowseTree]: a folder per account, or the only account's rows at the root), Radio and the queues.
 * Playback requests go to [PlaybackController], so the car gets the same queue, crossfade and offline handling as
 * the app, and a pick plays in its own account (D84).
 */
@Singleton
class MediaLibraryTree @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val fmRadio: FmRadio,
    private val downloadManager: TrackDownloadManager,
    private val playbackController: PlaybackController,
    private val settingsRepository: org.tegro.yamp.domain.repository.SettingsRepository,
    private val signedInAccounts: org.tegro.yamp.data.account.SignedInAccounts,
    private val accountContext: AccountContext,
    private val registry: ProviderRegistry,
    private val folderPins: FolderPins
) {
    private val accountTree = AccountBrowseTree(
        accounts = { signedInAccounts.current() },
        providers = registry::provider,
        pins = folderPins::of,
        isDownloaded = { downloadManager.isKnownDownloaded(it.id) },
        artwork = BrowseArtwork::uriFor,
        labels = BrowseLabels(
            wave = context.getString(R.string.auto_my_wave),
            waveSubtitle = context.getString(R.string.auto_my_wave_subtitle),
            waveArt = resourceUri(R.drawable.ic_notification),
            liked = context.getString(R.string.auto_liked),
            favourites = context.getString(R.string.library_row_favourites),
            playlists = context.getString(R.string.auto_playlists),
            albums = context.getString(R.string.auto_albums),
            folders = context.getString(R.string.library_row_folders),
            downloaded = context.getString(R.string.auto_downloaded),
            playAll = context.getString(R.string.auto_play_all)
        ),
        player = object : BrowsePlayer {
            override fun play(account: AccountId, tracks: List<Track>, startId: String?) {
                // The Yandex account in context plays as its Library does; any other account in its own queue
                if (account.provider != Provider.JELLYFIN && account == accountContext.current()) {
                    playbackController.playTrack(startId ?: tracks.first().ref.id, tracks, PlaylistSource.LIBRARY)
                } else {
                    playbackController.playInAccount(account, tracks, startId)
                }
            }

            override fun hasWave(account: AccountId): Boolean = account.provider == Provider.YANDEX &&
                (account == contextYandex() || waveQueueOf(account) != null)

            override fun playWave(account: AccountId) {
                if (account == contextYandex()) playbackController.playMyVibe()
                else waveQueueOf(account)?.let { playbackController.switchQueue(it, play = true) }
            }
        }
    )

    private fun contextYandex(): AccountId? = accountContext.currentUser()?.accountId()

    private fun waveQueueOf(account: AccountId): Long? =
        playbackController.queues.value.firstOrNull { it.queue.kind == QueueKind.WAVE && it.queue.account == account.value }?.queue?.id

    fun root(): MediaItem = folder(ROOT_ID, context.getString(R.string.app_name), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        .withExtras(Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
        })

    /** Children of [parentId], or null for an unknown node. */
    suspend fun children(parentId: String): List<MediaItem>? {
        if (accountTree.owns(parentId)) return accountTree.children(parentId)
        if (parentId.startsWith(QUEUES_ACCOUNT_PREFIX)) {
            val account = parentId.removePrefix(QUEUES_ACCOUNT_PREFIX)
            return queueSummaries().filter { it.queue.account == account }
                .sortedBy { if (it.queue.kind == QueueKind.WAVE) 0 else 1 }
                .map { queueItem(it, null) }
        }
        return when (parentId) {
            ROOT_ID -> accountTree.top() + listOf(
                folder(RADIO_ID, context.getString(R.string.auto_radio), MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                    .withExtras(Bundle().apply { putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_GRID) }),
                folder(QUEUES_ID, context.getString(R.string.queue_switcher_title), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
            )
            // One playable item per queue (docs/phase-1-queues.md §5); the car's own switcher holds no more either.
            // Several accounts (D64): a sub-folder per account when grouped, else the account's name in the subtitle
            QUEUES_ID -> {
                val names = accountNames()
                val queues = queueSummaries()
                if (names.size > 1 && settingsRepository.groupQueuesByAccount.first()) {
                    queues.filter { it.queue.account == null }.map { queueItem(it, null) } +
                        names.filter { (key, _) -> queues.any { it.queue.account == key } }.map { (key, name) ->
                            folder(QUEUES_ACCOUNT_PREFIX + key, name, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                        }
                } else {
                    queues.map { queueItem(it, names[it.queue.account].takeIf { names.size > 1 }) }
                }
            }
            RADIO_ID -> fmRadio.stations().map { station ->
                playable(
                    RADIO_PREFIX + station.id, station.name, station.frequency,
                    artwork = station.iconRes?.let { resourceUri(it) } ?: BrowseArtwork.uriFor(station.logoUrl),
                    mediaType = MediaMetadata.MEDIA_TYPE_RADIO_STATION
                )
            }
            else -> null
        }
    }

    suspend fun item(mediaId: String): MediaItem? {
        if (mediaId == ROOT_ID) return root()
        if (accountTree.owns(mediaId)) return accountTree.item(mediaId)
        val parent = when {
            mediaId.startsWith(RADIO_PREFIX) -> RADIO_ID
            mediaId.startsWith(QUEUES_ACCOUNT_PREFIX) -> QUEUES_ID
            mediaId.startsWith(QUEUE_PREFIX) -> queueParent(mediaId.removePrefix(QUEUE_PREFIX).toLongOrNull())
            else -> ROOT_ID
        }
        return children(parent)?.firstOrNull { it.mediaId == mediaId }
    }

    /** The car's search (`onSearch`): every account's tracks and albums. */
    suspend fun search(query: String): List<MediaItem> = accountTree.search(query)

    /**
     * Resolves a browse item, or a voice [searchQuery] when [mediaId] is empty, into the action that
     * starts it. The caller runs the action on the following play command, after media3 has moved the
     * service to the foreground (Android 15 denies audio focus otherwise).
     * @return null for an id this tree does not know.
     */
    suspend fun startAction(mediaId: String, searchQuery: String? = null): (() -> Unit)? = when {
        mediaId.isEmpty() && !searchQuery.isNullOrBlank() -> searchAction(searchQuery.trim())
        mediaId.isEmpty() -> contextYandex()?.let { { playbackController.playMyVibe() } }
        accountTree.owns(mediaId) -> accountTree.startAction(mediaId)
        mediaId.startsWith(RADIO_PREFIX) -> fmRadio.stations()
            .firstOrNull { it.id == mediaId.removePrefix(RADIO_PREFIX) }
            ?.let { station -> { playbackController.playRadioStation(station) } }
        mediaId.startsWith(QUEUE_PREFIX) -> mediaId.removePrefix(QUEUE_PREFIX).toLongOrNull()
            ?.takeIf { id -> queueSummaries().any { it.queue.id == id } }
            ?.let { id ->
                { playbackController.switchQueue(id, play = true) }
            }
        else -> null
    }

    /** "Play <station>" picks the station, anything else the first account's top search hits; My Wave when nothing matches. */
    private suspend fun searchAction(query: String): (() -> Unit)? {
        fmRadio.stations().firstOrNull { it.name.contains(query, ignoreCase = true) }?.let { station ->
            return { playbackController.playRadioStation(station) }
        }
        val found = try {
            accountTree.voiceSearch(query)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("MediaLibraryTree", "Voice search failed", e)
            null
        }
        return found ?: contextYandex()?.let { { playbackController.playMyVibe() } }
    }

    /** The signed-in accounts' names by key (`AccountId.value`), in the accounts' order. */
    private fun accountNames(): Map<String, String> = signedInAccounts.current().associate { it.id.value to it.displayName }

    /** The queues the car may show: a removed account's queues never (D62). */
    private fun queueSummaries(): List<org.tegro.yamp.domain.queue.QueueSummary> =
        carQueues(playbackController.queues.value, accountNames().keys).take(MAX_QUEUE_ITEMS)

    private suspend fun queueParent(id: Long?): String {
        val account = queueSummaries().firstOrNull { it.queue.id == id }?.queue?.account
        val grouped = accountNames().size > 1 && settingsRepository.groupQueuesByAccount.first()
        return if (grouped && account != null) QUEUES_ACCOUNT_PREFIX + account else QUEUES_ID
    }

    /** A queue as a playable item; [accountName] leads the subtitle in a flat list of several accounts. */
    private fun queueItem(summary: org.tegro.yamp.domain.queue.QueueSummary, accountName: String?): MediaItem {
        val track = summary.current?.let { listOf(it.title, it.artist).filter(String::isNotBlank).joinToString(" · ") }.orEmpty()
        return playable(
            QUEUE_PREFIX + summary.queue.id, summary.queue.name,
            listOfNotNull(accountName, track.ifBlank { null }).joinToString(" · "),
            artwork = BrowseArtwork.uriFor(summary.current?.coverUrl.toCoverUrl(CoverSize.Medium)),
            mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST
        )
    }

    private fun folder(id: String, title: String, mediaType: Int): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .build()
        )
        .build()

    private fun playable(id: String, title: String, subtitle: String, artwork: Uri?, mediaType: Int): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setArtworkUri(artwork)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(mediaType)
                    .build()
            )
            .build()

    private fun MediaItem.withExtras(extras: Bundle): MediaItem =
        buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()

    private fun resourceUri(resId: Int): Uri = "android.resource://${BuildConfig.APPLICATION_ID}/$resId".toUri()

    companion object {
        const val ROOT_ID = "root"
        const val RADIO_ID = "radio"
        const val QUEUES_ID = "queues"
        private const val QUEUE_PREFIX = "queue/"
        /** A sub-folder of "Queues" per account when grouped (D64). */
        private const val QUEUES_ACCOUNT_PREFIX = "queues/"
        /** Android Auto lists are paged at a few hundred items; the user's queues never come near. */
        private const val MAX_QUEUE_ITEMS = 100
        private const val RADIO_PREFIX = "radio/"

        // Content style keys understood by Android Auto and Automotive OS
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_LIST = 1
        private const val CONTENT_STYLE_GRID = 2
    }
}

/**
 * The queues Android Auto lists and lets the car play: those of no account and of a signed-in one — a removed
 * account's queues are never shown nor played there (D62); they wait in the phone's switcher.
 */
internal fun carQueues(queues: List<org.tegro.yamp.domain.queue.QueueSummary>, signedIn: Set<String>) =
    queues.filter { it.queue.account == null || it.queue.account in signedIn }
