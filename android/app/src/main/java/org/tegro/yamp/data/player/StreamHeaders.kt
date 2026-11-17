package org.tegro.yamp.data.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * The request headers a resolved stream must carry ([org.tegro.yamp.domain.provider.ResolvedStream.headers] —
 * a Jellyfin token, D79), by the stream's exact URL (docs/phase-3-providers.md step 23). A media item has no place
 * for headers, so the engine registers them here when it is handed a stream and the HTTP source adds them when it
 * opens that very URL — never for another URL, another host or a redirect target the URL does not name.
 * Yandex streams carry none: their requests are unchanged.
 */
object StreamHeaders {
    private const val MAX_ENTRIES = 64

    private val byUrl = object : LinkedHashMap<String, Map<String, String>>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>?) = size > MAX_ENTRIES
    }

    /** Remembers [headers] for requests to [uri]; nothing to remember without headers. */
    @Synchronized
    fun register(uri: String, headers: Map<String, String>) {
        if (headers.isEmpty() || uri.isBlank()) return
        byUrl[key(uri)] = headers
    }

    /** The headers registered for exactly [uri] (its fragment ignored), or none. */
    @Synchronized
    fun forUri(uri: String): Map<String, String> = byUrl[key(uri)].orEmpty()

    @Synchronized
    internal fun clear() = byUrl.clear()

    private fun key(uri: String): String = uri.substringBefore('#')

    /** Wraps an HTTP source factory: each request gets the headers registered for its URL. */
    @UnstableApi
    fun wrap(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory { WithHeaders(upstream.createDataSource()) }

    @UnstableApi
    private class WithHeaders(private val upstream: DataSource) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            val headers = forUri(dataSpec.uri.toString())
            return upstream.open(if (headers.isEmpty()) dataSpec else dataSpec.withAdditionalHeaders(headers))
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)
        override fun getUri(): Uri? = upstream.uri
        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
        override fun close() = upstream.close()
    }
}
