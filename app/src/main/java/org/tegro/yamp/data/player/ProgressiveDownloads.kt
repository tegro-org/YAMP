package org.tegro.yamp.data.player

import androidx.core.net.toUri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import org.tegro.yamp.domain.provider.ResolvedStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/** What the engine asks of the downloads of converted streams (D91). */
interface ProgressiveStreams {
    /** [stream] is a conversion sent with no length ([ResolvedStream.progressive]): its key is read from a growing file. */
    fun register(stream: ResolvedStream) = Unit

    fun isProgressive(key: String): Boolean = false

    /** The key the engine plays now (null: nothing): a download of a track neither playing nor next is cancelled. */
    fun playing(key: String?) = Unit

    /** Every byte of [key]'s stream is written (its part, or the cache it was imported into). */
    fun isComplete(key: String): Boolean = false

    /**
     * Bytes written for [key] over all its generations, and the downloads a RETRY started anew after a failure: it only
     * grows — the D67 rule's data while a reopen waits.
     */
    fun writtenTotal(key: String): Long = 0L

    /** A player has read all of a generation that broke and waits at its end: the engine reopens it from the next. */
    fun parkedAtBrokenGeneration(key: String): Boolean = false

    object None : ProgressiveStreams
}

/**
 * A download of a converted stream failed for good (D91): refused by the server, or broken more often than it restarts.
 * [reason] is the cause's Media3 error code. A slowness code (timeout, connection failed) stays only when the download
 * brought bytes: the controller then pauses at once (D67 — the restarts were the retries); a start that never brought
 * a byte is a dead start ([PlaybackException.ERROR_CODE_IO_UNSPECIFIED]) and goes RETRY → skip.
 * Never retried by the player ([PlaybackBuffering.PatientLoadErrors]).
 */
@UnstableApi
class ProgressiveDownloadException(message: String, cause: Throwable?, reason: Int) : DataSourceException(message, cause, reason) {
    companion object {
        private val SLOWNESS = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT
        )

        /** [broughtBytes]: any generation of the download wrote something. */
        fun of(cause: IOException?, broughtBytes: Boolean): ProgressiveDownloadException {
            val code = generateSequence<Throwable>(cause) { it.cause }.filterIsInstance<DataSourceException>().firstOrNull()?.reason
            val reason = if (code == null || (code in SLOWNESS && !broughtBytes)) PlaybackException.ERROR_CODE_IO_UNSPECIFIED else code
            return ProgressiveDownloadException("download failed: ${cause?.message}", cause, reason)
        }
    }
}

/**
 * The downloads of converted streams (D91), one per cache key, one registry per process. A stream sent with no length
 * (a server transcode) is fetched once, at full speed, into a growing part file under [partsDir]; players read it
 * through [GrowingFileSource], which waits at the written edge. Complete, the part is imported into the stream cache
 * ([Importer]) and deleted once no player reads it. A broken fetch restarts from byte 0 as a new generation (a new
 * transcode is not byte-identical, so generations are never joined); the old one stays readable for the player that
 * plays it. Only the playing track and the next one have downloads; on a metered network the next one starts after
 * the playing one is complete.
 */
@UnstableApi
class ProgressiveDownloads(
    private val partsDir: File,
    private val upstream: DataSource.Factory,
    private val importer: Importer,
    /** Pause before a restart, so a lost network shows as "no link" first. */
    private val restartDelayMs: Long = RESTART_DELAY_MS
) : ProgressiveStreams {

    /** Writes a complete part into the stream cache; true when all of it is there. [onCancel] gets the import's cancel. */
    fun interface Importer {
        fun import(stream: ResolvedStream, part: File, length: Long, onCancel: (() -> Unit) -> Unit): Boolean
    }

    enum class State { QUEUED, RUNNING, RESTARTING, COMPLETE, FAILED, CANCELLED }

    /** The network in use is metered: the next track's download waits for the playing one's. */
    @Volatile
    var metered: () -> Boolean = { false }

    /** Journal lines (`CACHE progressive …`), by key. */
    @Volatile
    var journal: (key: String, detail: String) -> Unit = { _, _ -> }

    /** Told each download's end (complete, failed, cancelled). */
    @Volatile
    var onFinished: (key: String, state: State) -> Unit = { _, _ -> }

    @Volatile
    private var linkUp = true
    private val linkSignal = Object()

    /** The controller's link: a restart is held (and not counted) while there is none. */
    fun linkChanged(up: Boolean) {
        linkUp = up
        synchronized(linkSignal) { linkSignal.notifyAll() }
    }

    private val registered = ConcurrentHashMap<String, ResolvedStream>()

    /** Guarded by `this`. */
    private val entries = HashMap<String, Download>()
    @Volatile
    private var playingKey: String? = null
    @Volatile
    private var nextKey: String? = null

    /** The key the engine plays now ([playing]). */
    val playingNow: String? get() = playingKey

    init {
        // Nothing from a previous process is of use: a converted stream can't resume
        partsDir.deleteRecursively()
        partsDir.mkdirs()
    }

    /** One generation's bytes. Guarded by its download's lock, except [written] (read without it). */
    internal class Part(val generation: Int, val file: File) {
        @Volatile
        var written = 0L
        var length = C.LENGTH_UNSET.toLong()
        var broken = false
        var readers = 0
        var parked = 0
        var obsolete = false
    }

    inner class Download internal constructor(val key: String, @Volatile var stream: ResolvedStream) {
        internal val lock = ReentrantLock()
        internal val changed = lock.newCondition()
        @Volatile
        var state = State.QUEUED
            internal set
        internal var failure: IOException? = null
        internal var restarts = 0
        internal val parts = mutableListOf(Part(1, partFile(key, 1)))
        internal val latest: Part get() = parts.last()
        @Volatile
        internal var total = 0L
        /** Bytes of the failed or cancelled downloads of the key this one replaced: [writtenTotal] never drops. */
        internal var carried = 0L
        @Volatile
        internal var cancelled = false
        internal var imported = false
        internal var importDone = false
        internal var dropped = false
        internal val importLock = ReentrantLock()
        @Volatile
        internal var cancelImport: (() -> Unit)? = null
        internal var startedAt = 0L
        private val listeners = mutableListOf<(State) -> Unit>()

        val written: Long get() = latest.written

        /** [listener] hears the end once: at once when it is over already. */
        fun whenDone(listener: (State) -> Unit) {
            val now = lock.withLock {
                if (state.isOver()) state else {
                    listeners += listener
                    null
                }
            }
            now?.let(listener)
        }

        /** What a reader at the written edge gets from a download that is over without its bytes: none while it goes on. */
        internal fun endOfReading(): IOException? = when (state) {
            State.FAILED -> ProgressiveDownloadException.of(failure, broughtBytes = total > 0L)
            State.CANCELLED -> ProgressiveDownloadException("download cancelled", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            else -> null
        }

        internal fun finished() {
            val heard = lock.withLock { listeners.toList().also { listeners.clear() } }
            heard.forEach { it(state) }
            onFinished(key, state)
        }
    }

    /** A player's hold on one generation's part ([GrowingFileSource]). */
    inner class Reader internal constructor(private val download: Download, private val part: Part) {
        @Volatile
        private var closed = false

        val file: File get() = part.file

        /** The whole stream's length when this generation is complete, else [C.LENGTH_UNSET]. */
        fun length(): Long = download.lock.withLock { part.length }

        /**
         * Bytes readable at [position] now, waiting for them at the written edge: [C.RESULT_END_OF_INPUT] at the end of
         * a complete generation. A broken generation waits for the engine's reopen; a failed download throws.
         * Interruptible (Media3's Loader interrupts on cancel), and wakes every [WAKE_MS] to see [close].
         */
        fun available(position: Long): Long {
            download.lock.lock()
            try {
                while (true) {
                    checkOpen()
                    val written = part.written
                    if (position < written) return written - position
                    if (part.length != C.LENGTH_UNSET.toLong() && position >= part.length) return C.RESULT_END_OF_INPUT.toLong()
                    download.endOfReading()?.let { throw it }
                    awaitChange()
                }
            } finally {
                download.lock.unlock()
            }
        }

        private fun checkOpen() {
            if (closed || Thread.currentThread().isInterrupted) throw InterruptedIOException(if (closed) "closed" else "interrupted")
        }

        /** Under the download's lock: parked at the edge until a chunk, a state change or the [WAKE_MS] wake. */
        private fun awaitChange() {
            part.parked++
            try {
                download.changed.await(WAKE_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("interrupted").apply { initCause(e) }
            } finally {
                part.parked--
            }
        }

        fun close() {
            if (closed) return
            closed = true
            download.lock.withLock {
                part.readers--
                download.changed.signalAll()
                deleteIfUnused(part)
            }
            readerLeft(download)
        }
    }

    override fun register(stream: ResolvedStream) {
        val key = stream.cacheKey ?: return
        if (!stream.progressive || stream.localFile) return
        registered[key] = stream
        // A re-resolved stream (a new link, new headers) replaces the one a download restarts from
        synchronized(this) { entries[key]?.stream = stream }
    }

    override fun isProgressive(key: String): Boolean = registered.containsKey(key)

    override fun playing(key: String?) {
        val start = synchronized(this) {
            playingKey = key
            retain()
            entries[key]?.takeIf { it.state == State.QUEUED }
        }
        start?.let(::start)
        startQueuedIfFree()
    }

    /** The next track's key (null: none): its download is kept; another one not playing goes. */
    fun next(key: String?) {
        synchronized(this) {
            nextKey = key
            retain()
        }
    }

    /** Starts (or joins) [stream]'s download as the next track's; null when it has no key. */
    fun prefetch(stream: ResolvedStream): Download? {
        val key = stream.cacheKey ?: return null
        register(stream.copy(progressive = true))
        next(key)
        return synchronized(this) { obtain(key, stream.uri) }
    }

    override fun isComplete(key: String): Boolean = synchronized(this) { entries[key] }?.state == State.COMPLETE

    override fun writtenTotal(key: String): Long = synchronized(this) { entries[key] }?.let { it.carried + it.total } ?: 0L

    /** Bytes of [key]'s current generation written so far. */
    fun written(key: String): Long = synchronized(this) { entries[key] }?.written ?: 0L

    override fun parkedAtBrokenGeneration(key: String): Boolean {
        val d = synchronized(this) { entries[key] } ?: return false
        return d.lock.withLock { d.parts.any { it.broken && it.parked > 0 } }
    }

    /** What is written of the downloads under [keyPrefix]: all of one ([State.COMPLETE]), else the most written. */
    fun progressOf(keyPrefix: String): Pair<Boolean, Long> {
        val found = synchronized(this) { entries.values.filter { it.key.startsWith(keyPrefix) } }
        return found.any { it.state == State.COMPLETE } to (found.maxOfOrNull { it.written } ?: 0L)
    }

    /** Bytes the parts hold on disk now (Settings → Storage). */
    val partsBytes: Long get() = partsDir.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * A player opens [key] ([GrowingFileSource]): its hold on the current generation, starting the download if none
     * runs. Null when the stream was imported into the cache meanwhile: it is read from there.
     */
    fun attach(key: String, uri: String): Reader? {
        val d = synchronized(this) { obtain(key, uri) }
        if (d.state == State.QUEUED && key == playingKey) start(d)
        return d.lock.withLock {
            if (d.imported) return null
            val part = d.latest
            part.readers++
            Reader(d, part)
        }
    }

    /**
     * The whole of [key]'s complete download from its part, held open (not deleted) until the stream is closed; null
     * when it is not complete, or its part went into the cache already.
     */
    fun openComplete(key: String): InputStream? {
        val d = synchronized(this) { entries[key] }?.takeIf { it.state == State.COMPLETE } ?: return null
        val reader = d.lock.withLock {
            val part = d.latest
            if (d.imported || part.obsolete || part.length == C.LENGTH_UNSET.toLong()) return null
            part.readers++
            Reader(d, part)
        }
        return try {
            object : FilterInputStream(FileInputStream(reader.file)) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        reader.close()
                    }
                }
            }
        } catch (e: IOException) {
            reader.close()
            throw e
        }
    }

    /** Every download under [keyPrefix] stops and its parts go, a running import too (sign-out); null: all of them. */
    fun cancelAll(keyPrefix: String? = null) {
        val gone = synchronized(this) {
            val matching = entries.values.filter { keyPrefix == null || it.key.startsWith(keyPrefix) }
            matching.forEach { entries.remove(it.key) }
            registered.keys.removeIf { keyPrefix == null || it.startsWith(keyPrefix) }
            matching
        }
        gone.forEach { cancel(it, hard = true) }
    }

    /** Guarded by `this`. A running or complete download, or a new one (started, or queued behind the playing one). */
    private fun obtain(key: String, uri: String): Download {
        val previous = entries[key]
        previous?.takeIf { it.state != State.FAILED && it.state != State.CANCELLED }?.let { return it }
        val stream = registered[key] ?: ResolvedStream(uri, cacheKey = key, progressive = true).also { registered[key] = it }
        // A wait for the whole file (RETRY after a failure) goes on across the new download: its bytes count on top
        val d = Download(key, stream).apply { carried = previous?.let { it.carried + it.total } ?: 0L }
        entries[key] = d
        if (!mustWait(key)) start(d)
        return d
    }

    /** Guarded by `this`. On a metered network the next track's download waits while the playing one runs. */
    private fun mustWait(key: String): Boolean {
        if (key == playingKey || !metered()) return false
        val playing = entries[playingKey ?: return false] ?: return false
        return playing.state == State.QUEUED || playing.state == State.RUNNING || playing.state == State.RESTARTING
    }

    private fun startQueuedIfFree() {
        val queued = synchronized(this) { entries.values.filter { it.state == State.QUEUED && !mustWait(it.key) } }
        queued.forEach(::start)
    }

    /** Guarded by `this`. Downloads of tracks neither playing nor next, with nobody reading them, go. */
    private fun retain() {
        val keep = setOfNotNull(playingKey, nextKey)
        entries.values.filter { it.key !in keep && readers(it) == 0 }.forEach { drop(it) }
    }

    private fun readers(d: Download): Int = d.lock.withLock { d.parts.sumOf { it.readers } }

    private fun readerLeft(d: Download) {
        synchronized(this) {
            val inRole = d.key == playingKey || d.key == nextKey
            if (entries[d.key] === d && !inRole && readers(d) == 0) drop(d)
        }
    }

    /** Guarded by `this`. Leaves the registry: running → cancelled; complete → its import (if any) still lands. */
    private fun drop(d: Download) {
        entries.remove(d.key)
        if (d.state == State.COMPLETE) {
            d.lock.withLock {
                d.dropped = true
                if (d.importDone) d.parts.forEach { it.obsolete = true; deleteIfUnused(it) }
            }
        } else {
            cancel(d, hard = false)
        }
    }

    private fun cancel(d: Download, hard: Boolean) {
        val wasRunning = d.lock.withLock {
            val running = !d.state.isOver()
            if (hard || running) d.cancelled = true
            if (running) d.state = State.CANCELLED
            d.parts.forEach { it.obsolete = true; deleteIfUnused(it) }
            d.changed.signalAll()
            running
        }
        synchronized(linkSignal) { linkSignal.notifyAll() }
        if (hard) {
            d.cancelImport?.invoke()
            // A running import ends before the caller removes the keys: nothing lands after a sign-out
            d.importLock.withLock { }
        }
        if (wasRunning) {
            journal(d.key, "cancelled at ${mb(d.written)}")
            d.finished()
        }
    }

    /** Guarded by the part's download lock. */
    private fun deleteIfUnused(part: Part) {
        if (part.obsolete && part.readers == 0) part.file.delete()
    }

    private fun start(d: Download) {
        d.lock.withLock {
            if (d.state != State.QUEUED) return
            d.state = State.RUNNING
        }
        d.startedAt = SystemClock.elapsedRealtime()
        journal(d.key, "downloading (gen 1)")
        thread(name = "progressive-download", isDaemon = true) { run(d) }
    }

    private fun run(d: Download) {
        while (true) {
            val part = d.lock.withLock { d.latest }
            val error = fetch(d, part)
            if (d.cancelled) return
            if (error == null) {
                completed(d, part)
                return
            }
            if (!restartAfter(d, part, error)) return
        }
    }

    /** One generation from byte 0 to the end; the error that broke it, or null. */
    private fun fetch(d: Download, part: Part): IOException? {
        val source = upstream.createDataSource()
        return try {
            FileOutputStream(part.file, true).use { out ->
                source.open(DataSpec.Builder().setUri(EncrawDataSourceFactory.networkUri(d.stream.uri.toUri())).build())
                copyAll(source, out, d, part)
            }
            if (part.written == 0L && !d.cancelled) IOException("the server sent nothing") else null
        } catch (e: IOException) {
            e
        } finally {
            runCatching { source.close() }
        }
    }

    private fun copyAll(source: DataSource, out: FileOutputStream, d: Download, part: Part) {
        val buffer = ByteArray(CHUNK_BYTES)
        while (!d.cancelled) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) return
            if (n <= 0) continue
            out.write(buffer, 0, n)
            d.lock.withLock {
                part.written += n
                d.total += n
                d.changed.signalAll()
            }
        }
    }

    private fun completed(d: Download, part: Part) {
        d.lock.withLock {
            part.length = part.written
            d.state = State.COMPLETE
            d.changed.signalAll()
        }
        journal(d.key, "complete ${mb(part.length)} in ${(SystemClock.elapsedRealtime() - d.startedAt) / 1000} s")
        d.finished()
        startQueuedIfFree()
        val imported = d.importLock.withLock {
            !d.cancelled && runCatching { importer.import(d.stream, part.file, part.length) { d.cancelImport = it } }.getOrDefault(false)
        }
        d.cancelImport = null
        d.lock.withLock {
            d.imported = imported
            d.importDone = true
            if (imported || d.dropped || d.cancelled) d.parts.forEach { it.obsolete = true; deleteIfUnused(it) }
        }
    }

    /** True when a new generation starts; false when the download failed for good (or was cancelled meanwhile). */
    private fun restartAfter(d: Download, part: Part, error: IOException): Boolean {
        val refused = PlaybackBuffering.refusedByServer(error)
        d.lock.withLock {
            part.broken = true
            if (!refused) d.state = State.RESTARTING
            d.changed.signalAll()
        }
        if (!refused) {
            pause(restartDelayMs, d)
            // Held, and not counted, while there is no link: no network is not the track's fault
            while (!linkUp && !d.cancelled) pause(WAKE_MS, d)
            if (d.cancelled) return false
        }
        // A start that brought nothing gets one more try; a break mid-way up to MAX_RESTARTS
        val restartsLeft = if (part.written == 0L) d.restarts < 1 else d.restarts < MAX_RESTARTS
        if (refused || !restartsLeft) {
            d.lock.withLock {
                d.state = State.FAILED
                d.failure = error
                d.parts.forEach { it.obsolete = true; deleteIfUnused(it) }
                d.changed.signalAll()
            }
            journal(d.key, "failed: ${describe(error)}")
            d.finished()
            startQueuedIfFree()
            return false
        }
        d.lock.withLock {
            d.restarts++
            d.parts += Part(part.generation + 1, partFile(d.key, part.generation + 1))
            part.obsolete = true
            deleteIfUnused(part)
            d.state = State.RUNNING
            d.changed.signalAll()
        }
        journal(d.key, "restart ${d.restarts}/$MAX_RESTARTS after ${describe(error)} at ${mb(part.written)} (gen ${part.generation + 1})")
        return true
    }

    private fun pause(ms: Long, d: Download) {
        if (ms <= 0L || d.cancelled) return
        synchronized(linkSignal) {
            try {
                linkSignal.wait(ms)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun partFile(key: String, generation: Int): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(partsDir, "$digest-$generation").also { runCatching { it.delete(); it.createNewFile() } }
    }

    private fun describe(e: IOException): String =
        (generateSequence<Throwable>(e) { it.cause }.firstOrNull { it.message != null }?.message ?: e.javaClass.simpleName).take(80)

    companion object {
        /** Restarts per track play after a broken download (a new transcode from byte 0 each). */
        const val MAX_RESTARTS = 2
        /** A waiting read or a held restart looks at its flags this often. */
        const val WAKE_MS = 500L
        const val RESTART_DELAY_MS = 1_500L
        private const val CHUNK_BYTES = 64 * 1024

        fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)

        private fun State.isOver() = this == State.COMPLETE || this == State.FAILED || this == State.CANCELLED
    }
}
