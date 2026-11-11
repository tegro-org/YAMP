package org.tegro.yamp.data.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.tegro.yamp.BuildConfig
import org.tegro.yamp.data.crash.CrashLog
import org.tegro.yamp.domain.repository.SettingsRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A log file on the device for what nobody can watch from a laptop in the car: playback events, recovery
 * decisions, audio focus, pauses with their reason, the service coming and going, crashes. Written through,
 * one line at a time, so a force-stop loses nothing; rotated at [MAX_BYTES] with one older file kept.
 * Shared from Settings → About. Off by default in release builds, on in debug (Settings → About switch).
 * Secrets never reach the file: every line goes through [CrashLog.redact] plus the stream-URL scrubber.
 */
@Singleton
class DiagnosticLog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository
) {
    private val dir get() = File(context.filesDir, DIR)
    private val file get() = File(dir, FILE)
    private val older get() = File(dir, OLDER)

    /** One thread writes, in order; the callers never wait for the disk. */
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "yamp-diag").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    var enabled: Boolean = BuildConfig.DEBUG
        private set

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settings.diagnosticLog.collect { on ->
                enabled = on
                if (!on) writer.execute { dir.deleteRecursively() }
            }
        }
    }

    /** Appends one line; a no-op while the log is off. */
    fun event(tag: String, message: String) {
        if (!enabled) return
        val line = line(tag, message)
        writer.execute { append(line) }
    }

    /** Like [event] but written before returning: for the crash handler, which has no "later". */
    fun eventNow(tag: String, message: String) {
        if (!enabled) return
        append(line(tag, message))
    }

    private fun line(tag: String, message: String): String =
        stamp.format(Date()) + "  " + tag.padEnd(9) + " " + scrub(message).replace('\n', ' ')

    @Synchronized
    private fun append(line: String) {
        runCatching {
            dir.mkdirs()
            if (file.length() > MAX_BYTES) {
                older.delete()
                file.renameTo(older)
            }
            file.appendText(line + "\n")
        }.onFailure { Log.w(TAG, "Diagnostic log not written", it) }
    }

    /** What makes the file self-describing when it lands in a chat. */
    fun writeHeader(extra: String = "") {
        event(
            "APP",
            "YAMP ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${if (BuildConfig.DEBUG) "debug" else "release"}) · " +
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
                (if (extra.isNotBlank()) " · $extra" else "")
        )
    }

    fun clear() {
        writer.execute {
            file.delete()
            older.delete()
        }
    }

    /** Both files, older first, for sharing. */
    fun files(): List<File> = listOf(older, file).filter { it.exists() && it.length() > 0 }

    /** Sends the log to a messenger or mail of the user's choice. */
    suspend fun share() {
        // Everything queued so far lands first
        writer.submit {}.get()
        val uris = files().map { FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, it) }
        if (uris.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("text/plain")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            .putExtra(Intent.EXTRA_SUBJECT, "YAMP diagnostic log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Log.w(TAG, "Diagnostic log not shared", it) }
    }

    /** The enabled state as persisted (the flag above may lag the DataStore by a moment). */
    suspend fun isEnabled(): Boolean = settings.diagnosticLog.first()

    companion object {
        private const val TAG = "DiagnosticLog"
        private const val DIR = "logs"
        private const val FILE = "yamp.log"
        private const val OLDER = "yamp.1.log"
        private const val AUTHORITY_SUFFIX = ".update_provider"
        const val MAX_BYTES = 1024L * 1024L

        private val signedUrlParams = Regex("""(?i)([?&](?:sign|ts|token|uid|__uid|key)=)[^&\s]+""")
        private val encrawKey = Regex("""(#encraw_key=)[0-9a-fA-F]+""")

        /** Tokens, signed stream URLs and account ids out; the rest as is. Pure, unit-tested. */
        fun scrub(text: String): String = CrashLog.redact(text)
            .replace(signedUrlParams, "$1‹…›")
            .replace(encrawKey, "$1‹…›")
    }
}
