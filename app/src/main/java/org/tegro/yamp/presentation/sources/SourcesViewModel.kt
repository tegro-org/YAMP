package org.tegro.yamp.presentation.sources

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tegro.yamp.R
import org.tegro.yamp.data.provider.local.FileSourceFs
import org.tegro.yamp.data.provider.local.LocalAccountSession
import org.tegro.yamp.data.provider.local.LocalJournal
import org.tegro.yamp.data.provider.local.LocalOverview
import org.tegro.yamp.data.provider.local.LocalOverviewModel
import org.tegro.yamp.data.provider.local.LocalSources
import org.tegro.yamp.data.provider.local.LocalVolume
import org.tegro.yamp.data.provider.local.VolumeMonitor
import org.tegro.yamp.data.provider.local.childPath
import org.tegro.yamp.data.provider.local.scan.LocalScanWorker
import org.tegro.yamp.domain.media.NaturalOrder
import org.tegro.yamp.provider.local.AudioFormats
import javax.inject.Inject

/**
 * A volume the app's own folder chooser offers (API ≤ 29): its id, name, whether it is removable, and whether the file
 * API can read it ([readable]); a USB drive can't be (Android ≤ 10 mounts it for the system only) and is picked in the
 * system folder picker instead (D93).
 */
data class ChooserVolume(val id: String, val name: String, val removable: Boolean, val readable: Boolean = true)

/** One level of the app's own folder chooser: its folders (with how many entries each holds) and its audio files. */
data class ChooserLevel(
    val volumeId: String,
    val relPath: String,
    val folders: List<Pair<String, Int>>,
    val audioFiles: Int,
    val unsupported: Int,
    val failed: Boolean = false
)

/** A folder of a source for the Exclude screen: its path, how many subfolders it has, hidden (always skipped). */
data class ExcludeRow(val relPath: String, val name: String, val subfolders: Int, val hidden: Boolean)

/**
 * "This device" sources (docs/phase-4d-local-files.md, UX; canvas `lo-*`): adding a folder (the system picker on
 * API ≥ 30, the app's own chooser on API ≤ 29), the Sources screen's actions (rescan, full rescan, exclude, show
 * hidden, remove) and what it shows ([overview]).
 */
@HiltViewModel
class SourcesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: LocalAccountSession,
    private val sources: LocalSources,
    private val volumes: VolumeMonitor,
    private val journal: LocalJournal,
    overview: LocalOverviewModel
) : ViewModel() {

    val overview: StateFlow<LocalOverview> = overview.overview

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    /** A snackbar's text: a folder added, or one that can't be used. */
    val messages: SharedFlow<String> = _messages

    /** The system folder picker on Android 11+; the storage permission and the app's own chooser before (D93). */
    val usesSystemPicker: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Whether `ACTION_OPEN_DOCUMENT_TREE` can be launched at all — false on ROMs without DocumentsUI (the head unit). */
    val canSystemPicker: Boolean
        get() = context.packageManager.resolveActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 0) != null

    /** A folder from the system picker; [onDone] gets whether it became a source. */
    fun addTree(uri: Uri, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val added = runCatching { session.addTree(uri) }.onFailure { journal.event("ADD tree failed: ${it.javaClass.simpleName}") }.getOrNull() != null
            _messages.tryEmit(context.getString(if (added) R.string.local_added else R.string.local_add_failed))
            onDone(added)
        }
    }

    /** A folder from the app's own chooser (API ≤ 29). */
    fun addPath(volumeId: String, relPath: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val added = session.addPath(volumeId, relPath) != null
            _messages.tryEmit(context.getString(if (added) R.string.local_added else R.string.local_add_failed))
            onDone(added)
        }
    }

    /** The system picker opened from the app's chooser for [volumeId], or its [outcome], in the journal. */
    fun notePicker(outcome: String, volumeId: String? = null) =
        journal.event("PICKER " + listOfNotNull(volumeId?.let(LocalJournal::vol), outcome).joinToString(" "))

    fun remove(sourceId: Long) {
        viewModelScope.launch { session.remove(sourceId) }
    }

    /** Rescan (new and changed files) of every source, or [full] (every file again) of one. */
    fun rescan(full: Boolean = false, sourceId: Long? = null) = LocalScanWorker.enqueue(context, full = full, sourceId = sourceId)

    fun setIncludeHidden(sourceId: Long, include: Boolean) {
        viewModelScope.launch {
            sources.setIncludeHidden(sourceId, include)
            rescan(sourceId = sourceId)
        }
    }

    /** The folder switch of the Exclude screen: off = excluded; the next scan follows. */
    fun setExcluded(sourceId: Long, relPath: String, excluded: Boolean) {
        viewModelScope.launch {
            if (excluded) sources.exclude(sourceId, relPath) else sources.include(sourceId, relPath)
            rescan(sourceId = sourceId)
        }
    }

    /** The subfolders of [relPath] in source [sourceId] for the Exclude screen; null when it can't be listed now. */
    suspend fun excludeRows(sourceId: Long, relPath: String): List<ExcludeRow>? = withContext(Dispatchers.IO) {
        val source = sources.all().firstOrNull { it.id == sourceId } ?: return@withContext null
        val fs = sources.fsFor(source) ?: return@withContext null
        val entries = fs.list(relPath) ?: return@withContext null
        entries.filter { it.isDirectory }.sortedWith(compareBy(NaturalOrder) { it.name }).map { dir ->
            val path = childPath(relPath, dir.name)
            ExcludeRow(path, dir.name, fs.list(path)?.count { it.isDirectory } ?: 0, hidden = dir.name.startsWith("."))
        }
    }

    /** The volumes the app's chooser offers: the mounted ones, each marked whether the file API can read it. */
    fun chooserVolumes(phone: String): List<ChooserVolume> {
        volumes.refresh()
        journal.volumes(volumes.volumes.value.values)
        return volumes.volumes.value.values.filter { it.mounted }
            .sortedBy { it.id != LocalVolume.PRIMARY }
            .map {
                val primary = it.id == LocalVolume.PRIMARY
                ChooserVolume(it.id, if (primary) phone else it.label, removable = !primary, readable = it.directory != null)
            }
    }

    /** A level of the app's chooser (API ≤ 29, the file API). */
    suspend fun chooserLevel(volumeId: String, relPath: String): ChooserLevel = withContext(Dispatchers.IO) {
        val dir = volumes.volume(volumeId)?.directory
        val fs = dir?.let(::FileSourceFs)
        val entries = fs?.list(relPath)
        if (fs == null || entries == null) {
            journal.event("CHOOSER ${LocalJournal.vol(volumeId)} ${LocalJournal.path(relPath)} unreadable: ${fs?.lastError ?: "no mount point"}")
            return@withContext ChooserLevel(volumeId, relPath, emptyList(), 0, 0, failed = true)
        }
        val visible = entries.filter { !it.name.startsWith(".") }
        ChooserLevel(
            volumeId = volumeId,
            relPath = relPath,
            folders = visible.filter { it.isDirectory }.sortedWith(compareBy(NaturalOrder) { it.name })
                .map { it.name to (fs.list(childPath(relPath, it.name))?.count { e -> !e.name.startsWith(".") } ?: 0) },
            audioFiles = visible.count { !it.isDirectory && AudioFormats.isPlayable(it.name) },
            unsupported = visible.count { !it.isDirectory && AudioFormats.isCandidate(it.name) && !AudioFormats.isPlayable(it.name) }
        )
    }
}
