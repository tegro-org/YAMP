package org.tegro.yamp.data.player.fakes

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A server that sends a conversion as the test feeds it (D91): no length, no ranges. Each request takes the next
 * [Feed] the test [serve]d; the test then pushes its bytes, its end or the error that breaks it, at its own pace.
 */
@UnstableApi
class FeedServer : DataSource.Factory {
    /** What one request gets: [openError] at once, or the [bytes] / [end] / [fail] pushed. */
    class Feed(val openError: IOException? = null) {
        internal val items = LinkedBlockingQueue<Any>()
        fun bytes(data: ByteArray) = apply { items.add(data) }
        fun end() = apply { items.add(END) }
        fun fail(error: IOException) = apply { items.add(error) }
    }

    private val feeds = LinkedBlockingQueue<Feed>()

    /** Every request, in order. */
    val requests = CopyOnWriteArrayList<DataSpec>()

    fun serve(feed: Feed = Feed()): Feed = feed.also { feeds.add(it) }

    override fun createDataSource(): DataSource = object : DataSource {
        private var feed: Feed? = null
        private var left: ByteArray? = null
        private var at = 0

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            requests += dataSpec
            val next = feeds.poll(WAIT_S, TimeUnit.SECONDS) ?: throw IOException("nothing served")
            next.openError?.let { throw it }
            feed = next
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val chunk = left
            if (chunk != null && at < chunk.size) {
                val n = minOf(length, chunk.size - at)
                System.arraycopy(chunk, at, buffer, offset, n)
                at += n
                return n
            }
            return when (val item = feed?.items?.poll(WAIT_S, TimeUnit.SECONDS) ?: throw IOException("feed timed out")) {
                is ByteArray -> {
                    left = item
                    at = 0
                    read(buffer, offset, length)
                }
                is IOException -> throw item
                else -> C.RESULT_END_OF_INPUT
            }
        }

        override fun getUri(): Uri? = null
        override fun close() = Unit
    }

    companion object {
        private val END = Any()
        private const val WAIT_S = 10L

        fun refused(code: Int, uri: String = "https://jf.example/x"): IOException =
            HttpDataSource.InvalidResponseCodeException(code, "refused", null, emptyMap(), DataSpec(Uri.parse(uri)), ByteArray(0))

        fun timeout(): IOException = HttpDataSource.HttpDataSourceException(
            java.net.SocketTimeoutException("read timed out"), DataSpec(Uri.parse("https://jf.example/x")),
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, HttpDataSource.HttpDataSourceException.TYPE_READ
        )
    }
}
