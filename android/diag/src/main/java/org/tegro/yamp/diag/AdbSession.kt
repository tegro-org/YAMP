package org.tegro.yamp.diag

import dadb.AdbKeyPair
import dadb.Dadb
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Output of one command: the bytes (capped), whether the cap cut it, and the exit code if the marker came through. */
class CommandOutput(val bytes: ByteArray, val truncated: Boolean, val exitCode: Int?)

/**
 * A connection to the device's own adbd over TCP, with Diag's own key (kept in private storage, never exported).
 *
 * Port rule: adb over Wi-Fi listens on 5555 or 7777 depending on how it was switched on, so both are probed in that
 * order with a short TCP connect; the first that accepts is remembered and probed again only after
 * [REPROBE_AFTER_FAILURES] failed connections in a row. Diag never switches adb over TCP on itself — the user does
 * (e.g. in MConfig+). The first connection with a new key shows the system's "Allow debugging?" prompt.
 */
class AdbSession(private val filesDir: File) {
    private var port: Int? = null
    private var failures = 0
    private var dadb: Dadb? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor()

    val connectedPort: Int? get() = if (dadb != null) port else null

    /** Blocking. Throws with a readable message when no daemon answers or the key is not authorised. */
    fun connect(): Int {
        dadb?.let { return port!! }
        if (port == null || failures >= REPROBE_AFTER_FAILURES) {
            port = PORTS.firstOrNull { probe(it) }
                ?: throw IllegalStateException("adb over Wi-Fi is off (nothing on ports ${PORTS.joinToString()})")
            failures = 0
        }
        return try {
            val connection = Dadb.create(HOST, port!!, keyPair(), CONNECT_TIMEOUT_MS, AUTH_TIMEOUT_MS)
            // The first stream opens the connection; with a new key adbd waits here for the user's "Allow".
            connection.open("shell:echo ok").use { it.source.readUtf8() }
            dadb = connection
            failures = 0
            port!!
        } catch (e: Exception) {
            failures++
            throw IllegalStateException("not authorised or refused on port $port — allow debugging on the screen", e)
        }
    }

    /** Runs one fixed command through the `shell:` service, capped and bounded in time. */
    fun run(item: DiagItem): CommandOutput {
        val connection = dadb ?: error("not connected")
        val stream = connection.open("shell:${item.command}; echo $EXIT_MARKER\$?")
        val timer = watchdog.schedule({ runCatching { stream.close() } }, item.timeoutMs, TimeUnit.MILLISECONDS)
        if (item.keepNewest) return runTail(item, stream, timer)
        val out = ByteArrayOutputStream()
        var truncated = false
        try {
            val buffer = ByteArray(BUFFER)
            val input = stream.source.inputStream()
            while (true) {
                val n = runCatching { input.read(buffer) }.getOrDefault(-1)
                if (n < 0) break
                val room = item.capBytes + MARKER_SLACK - out.size()
                if (room <= 0) { truncated = true; break }
                out.write(buffer, 0, minOf(n, room))
            }
        } finally {
            timer.cancel(false)
            runCatching { stream.close() }
        }
        return parse(out.toByteArray(), truncated, item.capBytes)
    }

    /** Reads the whole output line by line and keeps the newest lines within the cap. */
    private fun runTail(item: DiagItem, stream: dadb.AdbStream, timer: java.util.concurrent.ScheduledFuture<*>): CommandOutput {
        val tail = LogTail(item.capBytes - TAIL_HEADROOM, item.dropContaining)
        try {
            stream.source.inputStream().bufferedReader(Charsets.UTF_8).useLines { lines ->
                runCatching { lines.forEach(tail::accept) }
            }
        } finally {
            timer.cancel(false)
            runCatching { stream.close() }
        }
        return parse(tail.result().toByteArray(Charsets.UTF_8), cutByStream = false, cap = item.capBytes)
    }

    fun close() {
        runCatching { dadb?.close() }
        dadb = null
    }

    private fun keyPair(): AdbKeyPair {
        val private = File(filesDir, "adb_key")
        val public = File(filesDir, "adb_key.pub")
        if (!private.exists() || !public.exists()) AdbKeyPair.generate(private, public)
        return AdbKeyPair.read(private, public)
    }

    private fun probe(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(HOST, port), PROBE_TIMEOUT_MS) }
    }.isSuccess

    companion object {
        private const val HOST = "127.0.0.1"
        val PORTS = listOf(5555, 7777)
        const val REPROBE_AFTER_FAILURES = 3
        private const val PROBE_TIMEOUT_MS = 300
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val AUTH_TIMEOUT_MS = 60_000
        private const val BUFFER = 64 * 1024
        private const val MARKER_SLACK = 64
        private const val TAIL_HEADROOM = 4096
        const val EXIT_MARKER = "___DIAG_EXIT___:"

        /** Splits the trailing exit-code marker off the output and applies the cap. */
        fun parse(raw: ByteArray, cutByStream: Boolean, cap: Int): CommandOutput {
            val at = lastIndexOf(raw, EXIT_MARKER.toByteArray(Charsets.US_ASCII))
            val code = if (at < 0) null else
                String(raw, at + EXIT_MARKER.length, raw.size - at - EXIT_MARKER.length, Charsets.US_ASCII).trim().toIntOrNull()
            var body = if (at < 0) raw else raw.copyOf(at)
            var truncated = cutByStream
            if (body.size > cap) { body = body.copyOf(cap); truncated = true }
            return CommandOutput(body, truncated, code)
        }

        private fun lastIndexOf(haystack: ByteArray, needle: ByteArray): Int {
            for (i in haystack.size - needle.size downTo 0) {
                if (needle.indices.all { haystack[i + it] == needle[it] }) return i
            }
            return -1
        }
    }
}
