package org.tegro.yamp.diag

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** How one item ended. */
sealed interface ItemStatus {
    data class Ok(val bytes: Int) : ItemStatus
    data class Truncated(val bytes: Int) : ItemStatus
    data class Failed(val reason: String) : ItemStatus
}

data class ItemResult(val file: String, val status: ItemStatus)

/**
 * Writes the collection into one zip: every item that produced output, then `manifest.txt` listing every item —
 * failed ones too, so a failure never aborts the zip.
 */
class ZipAssembler(out: OutputStream) : AutoCloseable {
    private val zip = ZipOutputStream(out)
    private val results = mutableListOf<ItemResult>()

    fun add(file: String, bytes: ByteArray, truncated: Boolean = false) {
        zip.putNextEntry(ZipEntry(file))
        zip.write(bytes)
        if (truncated) zip.write("\n[truncated by YAMP Diag]\n".toByteArray())
        zip.closeEntry()
        results += ItemResult(file, if (truncated) ItemStatus.Truncated(bytes.size) else ItemStatus.Ok(bytes.size))
    }

    fun fail(file: String, reason: String) {
        results += ItemResult(file, ItemStatus.Failed(reason))
    }

    /** Adds `manifest.txt` and closes the zip. */
    fun finish(header: List<Pair<String, String>>): List<ItemResult> {
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(manifest(header, results).toByteArray())
        zip.closeEntry()
        zip.close()
        return results.toList()
    }

    override fun close() = zip.close()

    companion object {
        const val MANIFEST = "manifest.txt"

        fun manifest(header: List<Pair<String, String>>, results: List<ItemResult>): String = buildString {
            header.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
            append('\n')
            results.forEach { r ->
                append(r.file).append(": ")
                append(
                    when (val s = r.status) {
                        is ItemStatus.Ok -> "ok (${s.bytes} B)"
                        is ItemStatus.Truncated -> "truncated (${s.bytes} B)"
                        is ItemStatus.Failed -> "failed — ${s.reason}"
                    },
                )
                append('\n')
            }
        }
    }
}
