package org.tegro.yamp.diag

import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagState(
    val status: String = "Not connected",
    val connected: Boolean = false,
    val busy: Boolean = false,
    val rows: List<ItemResult> = emptyList(),
    val zips: List<File> = emptyList(),
)

class DiagViewModel(app: Application) : AndroidViewModel(app) {
    private val session = AdbSession(app.filesDir)
    private val outDir = File(app.cacheDir, "diag").apply { mkdirs() }
    private val _state = MutableStateFlow(DiagState(zips = zips()))
    val state: StateFlow<DiagState> = _state

    init { connect() }

    fun connect() = launchBusy {
        _state.update { it.copy(status = "Connecting… (allow debugging on the screen if asked)") }
        val result = withContext(Dispatchers.IO) { runCatching { session.connect() } }
        _state.update {
            result.fold(
                { port -> it.copy(status = "adb: connected on 127.0.0.1:$port", connected = true) },
                { e -> it.copy(status = "adb: ${e.message}. Switch on adb over Wi-Fi (e.g. MConfig+), then Connect.", connected = false) },
            )
        }
    }

    fun collect() = launchBusy {
        _state.update { it.copy(rows = emptyList(), status = "Collecting…") }
        val now = Date()
        val zip = File(outDir, "yamp-diag-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now) + ".zip")
        val results = withContext(Dispatchers.IO) {
            if (session.connectedPort == null) runCatching { session.connect() }
            zip.outputStream().buffered().use { out ->
                val assembler = ZipAssembler(out)
                assembler.add("audio_props.txt", audioProps().toByteArray())
                val kept = mutableMapOf<String, String>()
                DiagCommands.items(now).forEach { item ->
                    runItem(assembler, item)?.let { if (item.file in SUMMARY_INPUTS) kept[item.file] = String(it) }
                }
                // Poweramp's DVC (or any effect taking over the volume) on YAMP's session: one line, read first
                val effects = EffectSummary.line(
                    kept[DiagCommands.AUDIO_FLINGER_FILE], kept[DiagCommands.PID_FILE], kept[DiagCommands.PROCESSES_FILE]
                )
                assembler.add("summary.txt", (effects + "\n").toByteArray())
                assembler.finish(header(now) + ("effects" to effects))
            }
        }
        prune()
        _state.update {
            it.copy(
                rows = results,
                zips = zips(),
                connected = session.connectedPort != null,
                status = "Saved ${zip.name}" + if (session.connectedPort == null) " (adb unavailable — local facts only)" else "",
            )
        }
    }

    fun delete(file: File) {
        file.delete()
        _state.update { it.copy(zips = zips()) }
    }

    /** Runs [item] into the zip; its output, or null when it failed. */
    private fun runItem(assembler: ZipAssembler, item: DiagItem): ByteArray? {
        if (session.connectedPort == null) {
            assembler.fail(item.file, "adb not connected")
            return null
        }
        return runCatching { session.run(item) }.fold(
            { out ->
                when {
                    out.exitCode != null && out.exitCode != 0 -> {
                        assembler.fail(item.file, "exit ${out.exitCode}: " + String(out.bytes).trim().take(REASON_CHARS))
                        null
                    }
                    else -> {
                        assembler.add(item.file, out.bytes, out.truncated)
                        out.bytes
                    }
                }
            },
            { e ->
                assembler.fail(item.file, e.message ?: e.javaClass.simpleName)
                null
            },
        )
    }

    private fun audioProps(): String {
        val am = getApplication<Application>().getSystemService(AudioManager::class.java)
        return buildString {
            append("PROPERTY_OUTPUT_SAMPLE_RATE: ").append(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)).append('\n')
            append("PROPERTY_OUTPUT_FRAMES_PER_BUFFER: ").append(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)).append('\n')
            @Suppress("DEPRECATION")
            append("AudioTrack native rate (STREAM_MUSIC): ")
                .append(AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)).append('\n')
        }
    }

    private fun header(now: Date): List<Pair<String, String>> {
        val pm = getApplication<Application>().packageManager
        val yamp = runCatching {
            val info = pm.getPackageInfo(DiagCommands.YAMP_PACKAGE, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrElse { if (it is PackageManager.NameNotFoundException) "not installed" else it.toString() }
        return listOf(
            "collected" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(now),
            "device" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "diag" to "1.0",
            "yamp" to yamp,
            "adb" to (session.connectedPort?.let { "127.0.0.1:$it" } ?: "unavailable"),
            "logcat since" to DiagCommands.logcatSince(now),
        )
    }

    private fun zips(): List<File> =
        outDir.listFiles { f -> f.name.endsWith(".zip") }.orEmpty().sortedByDescending { it.name }

    private fun prune() = zips().drop(KEEP_ZIPS).forEach { it.delete() }

    private fun launchBusy(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try { block() } finally { _state.update { it.copy(busy = false) } }
        }
    }

    override fun onCleared() = session.close()

    private companion object {
        const val KEEP_ZIPS = 5
        const val REASON_CHARS = 120
        val SUMMARY_INPUTS = setOf(DiagCommands.AUDIO_FLINGER_FILE, DiagCommands.PID_FILE, DiagCommands.PROCESSES_FILE)
    }
}
