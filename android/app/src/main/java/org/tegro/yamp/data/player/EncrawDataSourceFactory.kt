package org.tegro.yamp.data.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.AesCipherDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import org.tegro.yamp.data.network.EncrawDecryptor

/**
 * A Media3 DataSource.Factory that intercepts streams with `#encraw_key=<hexKey>` in their URI
 * and transparently decrypts the AES-CTR encrypted stream (used by Yandex Music FLAC encraw).
 * For all other streams (regular MP3/AAC, radio, local files), it acts as a normal DefaultDataSource.
 */
@UnstableApi
class EncrawDataSourceFactory(
    context: Context,
    /** The on-disk stream cache (D86), below the decryption: the cache keeps what the network sends. */
    streamCache: StreamCache? = null
) : DataSource.Factory {

    /**
     * Streams over HTTP with shorter timeouts than the 8 s defaults: a dead connection to the stream host is noticed
     * in 4 s (connect) / 6 s (read) rather than 8 + 8. On a weak 4G link a live server still answers well within
     * that; a track that already has bytes buffered rides out the retries anyway (see [PlaybackBuffering]).
     */
    private val defaultFactory: DataSource.Factory = DefaultDataSource.Factory(context, httpFactory()).let { plain ->
        streamCache?.wrap(plain) ?: plain
    }

    companion object {
        /**
         * The stream host's HTTP source, also used to write the next track to disk ([NextTrackCacher]). A stream
         * resolved with headers (a Jellyfin token, step 23) gets them on requests to its own URL ([StreamHeaders]).
         */
        fun httpFactory(): DataSource.Factory = StreamHeaders.wrap(
            androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(PlaybackBuffering.CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(PlaybackBuffering.READ_TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(true)
        )

        /** [uri] without the decryption key it may carry in its fragment: what the network (and the cache) sees. */
        fun networkUri(uri: Uri): Uri = if (uri.fragment?.startsWith("encraw_key=") == true) uri.buildUpon().fragment(null).build() else uri
    }

    override fun createDataSource(): DataSource {
        return EncrawDataSource(defaultFactory.createDataSource())
    }

    internal class EncrawDataSource(
        private val upstream: DataSource
    ) : DataSource {

        private var activeDataSource: DataSource = upstream

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val uri = dataSpec.uri
            val fragment = uri.fragment
            if (fragment != null && fragment.startsWith("encraw_key=")) {
                val hexKey = fragment.substringAfter("encraw_key=")
                val keyBytes = EncrawDecryptor.hexToBytes(hexKey)
                val cleanUri = uri.buildUpon().fragment(null).build()
                val cleanDataSpec = dataSpec.buildUpon().setUri(cleanUri).build()
                val cipherSource = AesCipherDataSource(keyBytes, upstream)
                activeDataSource = cipherSource
                return cipherSource.open(cleanDataSpec)
            }
            activeDataSource = upstream
            return upstream.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return activeDataSource.read(buffer, offset, length)
        }

        override fun getUri(): Uri? = activeDataSource.uri

        override fun getResponseHeaders(): Map<String, List<String>> = activeDataSource.responseHeaders

        override fun close() {
            try {
                activeDataSource.close()
            } finally {
                activeDataSource = upstream
            }
        }
    }
}
