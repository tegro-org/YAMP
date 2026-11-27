package org.tegro.yamp.data.network

import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import org.tegro.yamp.data.download.CardStorage
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.tegro.yamp.domain.account.AccountId
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadProgressInfo(
    val current: Int,
    val total: Int,
    val currentTrackTitle: String = "",
    val currentArtistName: String = "",
    val percent: Float = if (total > 0) current.toFloat() / total else 0f
)

@Singleton
class TrackDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    /** Null in tests that do not need the card. */
    private val card: CardStorage? = null,
    /** The per-account clients a download is fetched through (D60); null in tests: the shared client. */
    private val clients: YandexClients? = null,
    /** What the stream cache holds whole (D91): a converted track played to its end is copied, not fetched again. */
    private val cachedStreams: org.tegro.yamp.data.player.CachedStreams? = null
) {
    private val tracksDir = File(context.filesDir, "tracks").apply {
        if (!exists()) mkdirs()
    }

    /** The card's folder while a card is in; its files are encrypted ("<id>.enc"). */
    private fun cardDir(): File? = card?.cardTracksDir()

    private fun internalFile(trackId: String) = File(tracksDir, "$trackId.mp3")
    private fun cardFile(dir: File, trackId: String) = File(dir, "$trackId.${CardStorage.EXTENSION}")

    /** True when new downloads go to the card: chosen in the settings, and the card is in and usable. */
    private suspend fun downloadsToCard(): Boolean = card != null && card.wantedNow() && card.cardUsable

    private val _downloadedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedTrackIds = _downloadedTrackIds.asStateFlow()

    private val _cacheSizeBytes = MutableStateFlow<Long>(0L)
    val cacheSizeBytes = _cacheSizeBytes.asStateFlow()

    private val _downloadProgress = MutableStateFlow<DownloadProgressInfo?>(null)
    val downloadProgress = _downloadProgress.asStateFlow()

    // Before init: declared after it, this empty value would overwrite what init reads from the disk
    @Volatile
    private var savedQualities: Map<String, TrackPreloadManager.StreamQuality> = emptyMap()

    /**
     * The accounts a downloaded Jellyfin track was saved for (gap b), from "<id>.owner" beside the internal files
     * wherever the track itself is; a Yandex download has none (its owners are the accounts that like it).
     */
    @Volatile
    private var savedOwners: Map<String, Set<String>> = emptyMap()

    init {
        // Remove leftover temp files from a download the previous process did not finish.
        tracksDir.listFiles()?.filter { it.extension == "tmp" }?.forEach { it.delete() }
        updateCacheStats()
    }

    /** Re-reads both places, for instance after the card was put in or taken out. */
    fun refresh() = updateCacheStats()

    private fun updateCacheStats() {
        val internal = tracksDir.listFiles()?.toList().orEmpty()
        val onCard = cardDir()?.listFiles()?.toList().orEmpty()
        val all = internal + onCard
        val files = internal.filter { it.extension == "mp3" && it.length() > 0 } +
            onCard.filter { it.extension == CardStorage.EXTENSION && it.length() > 0 }
        savedQualities = all.filter { it.extension == "quality" }.mapNotNull { file ->
            runCatching {
                val (codec, kbps) = file.readText().split(':')
                file.nameWithoutExtension to TrackPreloadManager.StreamQuality(codec, kbps.trim().toInt())
            }.getOrNull()
        }.toMap()
        savedOwners = internal.filter { it.extension == OWNER_EXTENSION }.associate { file ->
            file.nameWithoutExtension to runCatching { file.readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
        }
        _downloadedTrackIds.value = files.map { it.nameWithoutExtension }.toSet()
        _cacheSizeBytes.value = files.sumOf { it.length() }
    }

    fun setDownloadProgress(progress: DownloadProgressInfo?) {
        _downloadProgress.value = progress
    }

    /** The internal file, or the encrypted one on the card while the card is in. */
    fun getLocalTrackFile(trackId: String): File? {
        val file = internalFile(trackId)
        if (file.exists() && file.length() > 0) return file
        val onCard = cardDir()?.let { cardFile(it, trackId) }
        if (onCard != null && onCard.exists() && onCard.length() > 0) return onCard
        // Listed as downloaded, but not there: the card went without the system saying so. The list is read
        // again, so the track counts as not downloaded and plays from the network (upstream Н2).
        if (trackId in _downloadedTrackIds.value) updateCacheStats()
        return null
    }

    fun isTrackDownloaded(trackId: String): Boolean {
        return getLocalTrackFile(trackId) != null
    }

    /** Main-thread friendly check against the in-memory index (no disk access). */
    fun isKnownDownloaded(trackId: String): Boolean = trackId in _downloadedTrackIds.value

    /** Codec and bitrate a downloaded file was saved in; null for files from before 1.6 (the best MP3 then). */
    fun downloadedQuality(trackId: String): TrackPreloadManager.StreamQuality? = savedQualities[trackId]

    /** The accounts [trackId] was downloaded for (Jellyfin); empty for a Yandex download. From memory. */
    fun ownersOf(trackId: String): Set<String> = savedOwners[trackId].orEmpty()

    /** [account] wants the already downloaded [trackId] too: it stays while either account is signed in. */
    suspend fun addOwner(trackId: String, account: AccountId) = withContext(Dispatchers.IO) {
        if (account.value in ownersOf(trackId)) return@withContext
        writeOwners(trackId, ownersOf(trackId) + account.value)
        updateCacheStats()
    }

    /**
     * Sign-out (D62, gap b): [account] leaves every Jellyfin download's owners; a track nobody else wants goes.
     * Returns the ids removed.
     */
    suspend fun releaseOwner(account: AccountId): List<String> = withContext(Dispatchers.IO) {
        val removed = mutableListOf<String>()
        for ((trackId, owners) in savedOwners) {
            if (account.value !in owners) continue
            val left = owners - account.value
            if (left.isEmpty()) {
                removeTrack(trackId)
                removed += trackId
            } else {
                writeOwners(trackId, left)
            }
        }
        updateCacheStats()
        removed
    }

    private fun writeOwners(trackId: String, owners: Set<String>) {
        File(tracksDir, "$trackId.$OWNER_EXTENSION").writeText(owners.joinToString("\n"))
    }

    /**
     * Saves the track for offline listening. With [replace] an existing copy is swapped for the new one
     * only once the new file is complete (a better quality), so the track stays playable meanwhile.
     * The file keeps the `.mp3` name whatever the codec: the player detects the format from its content.
     */
    suspend fun downloadTrack(
        trackId: String,
        url: String,
        quality: TrackPreloadManager.StreamQuality? = null,
        replace: Boolean = false,
        /** The account the link was resolved under: its client fetches the file. */
        account: AccountId? = null,
        /**
         * Headers for this URL alone (Jellyfin's auth, gap b): the file is then fetched through [http] with redirects
         * off, so they never reach another host.
         */
        headers: Map<String, String> = emptyMap(),
        http: OkHttpClient? = null,
        /** The account a Jellyfin download is saved for: it goes when the last of its owners signs out. */
        owner: AccountId? = null,
        /**
         * The stream cache key the bytes of [url] are kept under once played whole (a Jellyfin conversion, D91): when all
         * of them are on disk the file is copied from there, with no request.
         */
        cacheKey: String? = null
    ): Boolean {
        // One download per track: several jobs may want it at once (all liked, the like just given, a playlist,
        // the quality upgrade), and they would write the same temporary file together. The others wait and
        // find the finished file.
        // The locks stay in the map: dropping one while a job is about to take it would let two in again
        return trackLocks.getOrPut(trackId) { Mutex() }.withLock {
            val fetch = Fetch(url, quality, account, headers, http, cacheKey)
            downloadNow(trackId, fetch, replace).also { ok -> if (ok && owner != null) addOwner(trackId, owner) }
        }
    }

    private val trackLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private class Fetch(
        val url: String,
        val quality: TrackPreloadManager.StreamQuality?,
        val account: AccountId?,
        val headers: Map<String, String>,
        val http: OkHttpClient?,
        val cacheKey: String?
    )

    private suspend fun downloadNow(trackId: String, fetch: Fetch, replace: Boolean): Boolean = withContext(Dispatchers.IO) {
        val url = fetch.url
        var quality = fetch.quality
        if (!replace && isTrackDownloaded(trackId)) return@withContext true

        val toCard = downloadsToCard()
        val key = if (toCard) card?.key() else null
        val dir = if (toCard && key != null) cardDir() ?: tracksDir else tracksDir
        val encrypted = dir != tracksDir
        val file = if (encrypted) cardFile(dir, trackId) else internalFile(trackId)
        val tempFile = File(dir, "$trackId.tmp")

        val encrawKey = if (url.contains("#encraw_key=")) {
            url.substringAfter("#encraw_key=").takeIf { it.isNotBlank() }
        } else null
        val cleanUrl = if (encrawKey != null) url.substringBefore("#encraw_key=") else url

        try {
            // A conversion played whole is on disk already (D91): copied from there, else fetched
            val flac = copyFromCache(fetch.cacheKey, tempFile, encrypted, key)
                ?: fetchInto(cleanUrl, fetch, tempFile, encrawKey, encrypted, key)
            if (flac == null) {
                tempFile.delete()
                return@withContext false
            }
            if (quality == null && flac) quality = TrackPreloadManager.StreamQuality("flac", 0)

            // Rename after successful download
            if (file.exists()) file.delete()
            val renamed = tempFile.renameTo(file)
            if (!renamed) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            val qualityFile = File(dir, "$trackId.quality")
            val saved = quality
            if (saved != null) qualityFile.writeText("${saved.codec}:${saved.bitrateKbps}") else qualityFile.delete()
            // A replaced copy in the other place goes, so a track is never kept twice
            removeOtherCopy(trackId, keep = file)
            updateCacheStats()
            return@withContext true
        } catch (e: kotlinx.coroutines.CancellationException) {
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            android.util.Log.w("TrackDownloadManager", "Download failed", e)
            tempFile.delete()
            return@withContext false
        }
    }

    /**
     * The whole stream under [cacheKey] into [tempFile] (D91, decision 4): a converted track played to its end is on
     * disk already. Null when it is not all there, or the copy broke (then it is fetched as usual); else whether it is
     * FLAC (by its first bytes: FLAC in Ogg, or plain FLAC).
     */
    private fun copyFromCache(cacheKey: String?, tempFile: File, encrypted: Boolean, cardKey: ByteArray?): Boolean? {
        val input = cacheKey?.let { runCatching { cachedStreams?.openComplete(it) }.getOrNull() } ?: return null
        return try {
            val buffered = java.io.BufferedInputStream(input, OGG_HEAD_BYTES.toInt() * 2)
            buffered.mark(OGG_HEAD_BYTES.toInt())
            val head = ByteArray(OGG_HEAD_BYTES.toInt())
            val headLength = buffered.readNBytesCompat(head)
            buffered.reset()
            writeInto(tempFile, buffered, encrypted, cardKey)
            android.util.Log.i("TrackDownloadManager", "Copied ${tempFile.nameWithoutExtension} from the stream cache, no request")
            isFlacHead(head.copyOf(headLength))
        } catch (e: java.io.IOException) {
            android.util.Log.w("TrackDownloadManager", "Copy from the stream cache failed, fetching", e)
            tempFile.delete()
            null
        } finally {
            runCatching { input.close() }
        }
    }

    /** [cleanUrl] into [tempFile]; null when the server did not send it, else whether it is FLAC. */
    private fun fetchInto(cleanUrl: String, fetch: Fetch, tempFile: File, encrawKey: String?, encrypted: Boolean, cardKey: ByteArray?): Boolean? {
        val request = Request.Builder().url(cleanUrl).apply { fetch.headers.forEach { (name, value) -> header(name, value) } }.build()
        val http = when {
            fetch.headers.isNotEmpty() -> (fetch.http ?: okHttpClient).newBuilder().followRedirects(false).followSslRedirects(false).build()
            fetch.account != null && clients != null -> clients.http(fetch.account)
            else -> okHttpClient
        }
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body
            // A file sent as it is says what it is: a FLAC one must not play in compatible sound (silent)
            val flac = isFlac(body)
            val rawStream = body.byteStream()
            writeInto(tempFile, if (encrawKey != null) EncrawDecryptor.wrapInputStream(rawStream, encrawKey) else rawStream, encrypted, cardKey)
            flac
        }
    }

    /** All of [input] into [tempFile]; on the card written encrypted, the stream itself untouched. */
    private fun writeInto(tempFile: File, input: java.io.InputStream, encrypted: Boolean, cardKey: ByteArray?) {
        FileOutputStream(tempFile).use { fos ->
            val target = if (encrypted && cardKey != null) CardStorage.encrypting(cardKey, fos) else fos
            input.use { source -> target.use { out -> source.copyTo(out) } }
        }
    }

    suspend fun removeTrack(trackId: String) = withContext(Dispatchers.IO) {
        var removed = false
        for (dir in listOfNotNull(tracksDir, cardDir())) {
            File(dir, "$trackId.quality").delete()
            File(dir, "$trackId.$OWNER_EXTENSION").delete()
            for (file in listOf(internalFile(trackId).takeIf { dir == tracksDir }, cardFile(dir, trackId).takeIf { dir != tracksDir })) {
                if (file != null && file.exists()) removed = file.delete() || removed
            }
        }
        if (removed) updateCacheStats()
    }

    suspend fun clearMusicCache() = withContext(Dispatchers.IO) {
        tracksDir.listFiles()?.forEach { it.delete() }
        cardDir()?.listFiles()?.forEach { it.delete() }
        updateCacheStats()
    }

    private fun removeOtherCopy(trackId: String, keep: File) {
        val other = if (keep.extension == CardStorage.EXTENSION) internalFile(trackId) else cardDir()?.let { cardFile(it, trackId) }
        if (other != null && other.exists()) {
            other.delete()
            File(other.parentFile, "$trackId.quality").takeIf { it.parentFile != keep.parentFile }?.delete()
        }
    }

    /**
     * Moves every downloaded track to the card (encrypting) or back to the internal storage (decrypting),
     * one at a time: each track is complete in its new place before the old copy goes, so stopping half-way
     * loses nothing. [onProgress] gets (moved, total). Returns false when it could not finish.
     */
    suspend fun moveAll(toCard: Boolean, onProgress: (Int, Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val cardTracks = cardDir() ?: return@withContext false
        val key = card?.key() ?: return@withContext false
        val sources = if (toCard) {
            tracksDir.listFiles { f -> f.extension == "mp3" && f.length() > 0 }.orEmpty()
        } else {
            cardTracks.listFiles { f -> f.extension == CardStorage.EXTENSION && f.length() > 0 }.orEmpty()
        }
        var moved = 0
        onProgress(0, sources.size)
        for (source in sources) {
            val id = source.nameWithoutExtension
            val targetDir = if (toCard) cardTracks else tracksDir
            val target = if (toCard) cardFile(cardTracks, id) else internalFile(id)
            val temp = File(targetDir, "$id.tmp")
            try {
                if (toCard) {
                    source.inputStream().use { input -> FileOutputStream(temp).use { fos -> CardStorage.encrypting(key, fos).use { input.copyTo(it) } } }
                } else {
                    CardStorage.decryptingStream(key, source).use { input -> FileOutputStream(temp).use { input.copyTo(it) } }
                }
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                val quality = File(source.parentFile, "$id.quality")
                if (quality.exists()) quality.copyTo(File(targetDir, "$id.quality"), overwrite = true).also { quality.delete() }
                source.delete()
            } catch (e: kotlinx.coroutines.CancellationException) {
                temp.delete()
                throw e
            } catch (e: Exception) {
                android.util.Log.w("TrackDownloadManager", "Move of $id failed", e)
                temp.delete()
                updateCacheStats()
                return@withContext false
            }
            moved++
            onProgress(moved, sources.size)
            if (moved % 10 == 0) updateCacheStats()
        }
        updateCacheStats()
        true
    }

    /**
     * A file sent as FLAC: `audio/flac`, or FLAC in Ogg (`audio/ogg`, Jellyfin's lossless conversion in the original
     * mode, 0.6.1) — told from Opus or Vorbis by its first packet, peeked without taking it from the body.
     */
    private fun isFlac(body: okhttp3.ResponseBody): Boolean {
        val type = body.contentType() ?: return false
        if (type.subtype.contains("flac", ignoreCase = true)) return true
        if (!type.subtype.equals("ogg", ignoreCase = true)) return false
        val head = runCatching {
            body.source().peek().run { if (request(OGG_HEAD_BYTES)) readByteArray(OGG_HEAD_BYTES) else null }
        }.getOrNull() ?: return false
        return isOggFlac(head)
    }

    /** A file with no type to go by (a copy from the stream cache): FLAC in Ogg, or plain FLAC ("fLaC"). */
    private fun isFlacHead(head: ByteArray): Boolean =
        isOggFlac(head) || head.size >= FLAC_MARK.length && String(head, 0, FLAC_MARK.length, Charsets.ISO_8859_1) == FLAC_MARK

    private fun isOggFlac(head: ByteArray): Boolean {
        if (head.size <= OGG_SEGMENTS_AT) return false
        // An Ogg page: "OggS", its header up to the segment count (byte 26), the segment table, then the packet
        val packet = OGG_SEGMENTS_AT + 1 + (head[OGG_SEGMENTS_AT].toInt() and 0xFF)
        return String(head, 0, 4, Charsets.ISO_8859_1) == "OggS" && packet + OGG_FLAC_MARK.size <= head.size &&
            OGG_FLAC_MARK.indices.all { head[packet + it] == OGG_FLAC_MARK[it] }
    }

    /** Fills [buffer] as far as the stream goes; the bytes read. */
    private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private companion object {
        const val OWNER_EXTENSION = "owner"
        const val FLAC_MARK = "fLaC"
        const val OGG_HEAD_BYTES = 300L
        const val OGG_SEGMENTS_AT = 26
        /** The first packet of FLAC in Ogg: 0x7F, "FLAC". */
        val OGG_FLAC_MARK = byteArrayOf(0x7F, 'F'.code.toByte(), 'L'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte())
    }
}

