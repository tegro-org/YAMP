package org.tegro.yamp.data.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs are shared as **files** (F7): a FileProvider content URI in `EXTRA_STREAM` and in `ClipData`, with the read
 * grant. Apps that only take files (LocalSend, file managers) get them; the text, when given, stays in `EXTRA_TEXT`
 * for messengers. A text-only share (the old way) gave file-only apps nothing to send.
 */
object FileShare {

    private const val TAG = "FileShare"
    private const val AUTHORITY_SUFFIX = ".update_provider"
    private const val SHARE_DIR = "shared"
    private const val MIME = "text/plain"

    /** Above this the text goes only as the file: a large `EXTRA_TEXT` can overflow the binder transaction. */
    private const val MAX_EXTRA_TEXT_CHARS = 100_000

    /** Writes [text] to a cache file named `<prefix>-<time>.txt`, shareable through the app's FileProvider. */
    fun writeTextFile(context: Context, prefix: String, text: String, now: Date = Date()): File {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        // Earlier shares are left for the receiving app to read; keep only the newest few
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_FILES)?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        return File(dir, "$prefix-$stamp.txt").apply { writeText(text) }
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)

    /**
     * The share intent for [uris] (one → `ACTION_SEND`, several → `ACTION_SEND_MULTIPLE`): stream, clip data and the
     * read grant. [text] goes into `EXTRA_TEXT` only while it is small.
     */
    fun intent(uris: List<Uri>, subject: String?, text: String? = null): Intent {
        require(uris.isNotEmpty()) { "nothing to share" }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        val clip = ClipData.newRawUri(subject ?: "", uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.type = MIME
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        subject?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        if (text != null && text.length <= MAX_EXTRA_TEXT_CHARS) intent.putExtra(Intent.EXTRA_TEXT, text)
        return intent
    }

    /** Opens the system share sheet for [uris]; the chooser carries the grant to whichever app is picked. */
    fun share(context: Context, uris: List<Uri>, subject: String?, text: String? = null, title: String? = null) {
        if (uris.isEmpty()) return
        val chooser = Intent.createChooser(intent(uris, subject, text), title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(chooser) }.onFailure { Log.w(TAG, "Share failed", it) }
    }

    private const val KEEP_FILES = 5
}
