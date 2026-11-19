package org.tegro.yamp.data.provider.jellyfin

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tegro.yamp.data.network.NetworkMonitor
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.data.player.SoundMode
import org.tegro.yamp.data.player.StreamCache
import org.tegro.yamp.domain.account.AccountId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What of a Jellyfin account's tracks is on the device (gap b), for its stream resolver: a downloaded copy saved for
 * an account of the same server, and streams whose bytes are all in the disk cache.
 */
@Singleton
class JellyfinOnDevice @Inject constructor(
    private val downloads: TrackDownloadManager,
    private val streamCache: StreamCache,
    private val network: NetworkMonitor,
    private val soundMode: SoundMode
) {
    /** The copy of [itemId] [account] may play, as a file URI; a FLAC copy is not one in compatible sound (silent). */
    suspend fun copyFor(account: AccountId, itemId: String): String? {
        if (!sameServer(downloads.ownersOf(itemId), account)) return null
        if (downloads.downloadedQuality(itemId)?.isLossless == true && soundMode.isCompatible(account)) return null
        val file = withContext(Dispatchers.IO) { downloads.getLocalTrackFile(itemId) } ?: return null
        return Uri.fromFile(file).toString()
    }

    /** Every byte under [cacheKey] is on disk, and the cache is on (else the engine would not read it). */
    fun isComplete(cacheKey: String): Boolean = streamCache.enabled && streamCache.isComplete(cacheKey)

    fun online(): Boolean = network.isCurrentlyConnected()

    companion object {
        /**
         * An item id names the same file for every user of one server (`jellyfin@<server>/<user>`); another server's
         * item with the same id is another file.
         */
        fun sameServer(owners: Set<String>, account: AccountId): Boolean {
            val server = account.value.substringBeforeLast('/')
            return owners.any { it.substringBeforeLast('/') == server }
        }
    }
}
