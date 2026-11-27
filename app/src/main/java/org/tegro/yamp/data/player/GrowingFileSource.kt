package org.tegro.yamp.data.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.RandomAccessFile

/**
 * Reads a converted stream's growing part file (D91) through [reader]: what is written is read at disk speed; at the
 * written edge the read waits for more bytes, the end of the download, or its failure (see
 * [ProgressiveDownloads.Reader.available]). Opened on a complete generation it reports the stream's length, so Media3
 * can seek it; on a running one the length is unknown, as from the server. One open per instance.
 */
@UnstableApi
internal class GrowingFileSource(
    private val reader: ProgressiveDownloads.Reader,
    private val uri: Uri
) : DataSource {
    private var file: RandomAccessFile? = null
    private var position = 0L
    private var end = C.LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        position = dataSpec.position
        file = RandomAccessFile(reader.file, "r").also { it.seek(position) }
        val length = reader.length()
        if (length == C.LENGTH_UNSET.toLong()) {
            end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) position + dataSpec.length else C.LENGTH_UNSET.toLong()
            return dataSpec.length
        }
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(length, position + dataSpec.length) else length
        return end - position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (end != C.LENGTH_UNSET.toLong() && position >= end) return C.RESULT_END_OF_INPUT
        val available = reader.available(position)
        if (available == C.RESULT_END_OF_INPUT.toLong()) return C.RESULT_END_OF_INPUT
        val wanted = minOf(length.toLong(), available, if (end != C.LENGTH_UNSET.toLong()) end - position else Long.MAX_VALUE).toInt()
        val n = file?.read(buffer, offset, wanted) ?: return C.RESULT_END_OF_INPUT
        if (n > 0) position += n
        return if (n < 0) C.RESULT_END_OF_INPUT else n
    }

    override fun getUri(): Uri = uri

    override fun close() {
        try {
            file?.close()
        } finally {
            file = null
            reader.close()
        }
    }
}
