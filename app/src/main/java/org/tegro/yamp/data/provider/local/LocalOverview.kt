package org.tegro.yamp.data.provider.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.tegro.yamp.data.provider.local.db.LocalDatabase
import org.tegro.yamp.data.provider.local.db.SourceEntity
import org.tegro.yamp.data.provider.local.scan.LocalScanStatus
import org.tegro.yamp.data.provider.local.scan.ScanState
import org.tegro.yamp.data.provider.local.scan.ScanSummary
import javax.inject.Inject
import javax.inject.Singleton

/** How a source is now (canvas `lo-sources`). */
enum class SourceState { AVAILABLE, SCANNING, ABSENT, NO_ACCESS, NOT_SCANNED }

/**
 * One source as the Sources screen, the account card and the Library's status line show it: its volume's name, its
 * folder, its state, its tracks (kept while the volume is absent), the scan's progress on it, its excludes.
 */
data class SourceStatus(
    val id: Long,
    val volumeId: String,
    val volumeName: String,
    /** The folder's path on the volume (`Music`); empty for the volume's root. */
    val folder: String,
    val state: SourceState,
    val tracks: Int,
    /** 0..100 while it is read; null while it is listed (indeterminate) or not scanning. */
    val percent: Int? = null,
    val done: Int = 0,
    val total: Int = 0,
    /** The folder being listed or read (volume-relative), while scanning. */
    val scanFolder: String? = null,
    val lastScanAt: Long? = null,
    /** When its volume was last seen mounted (an absent stick: "last seen 3 days ago"). */
    val lastSeenAt: Long? = null,
    val includeHidden: Boolean = false,
    val excludes: List<String> = emptyList()
)

/** A file the scanner skipped (a format Stage 1 doesn't play, or unreadable): where it is, for the list. */
data class SkippedFile(val volumeName: String, val relPath: String)

/** "This device" at a glance: its sources, its tracks, the skipped files by format (`ape`, `alac`, …, `unreadable`). */
data class LocalOverview(
    val sources: List<SourceStatus> = emptyList(),
    val tracks: Int = 0,
    val skipped: Map<String, List<SkippedFile>> = emptyMap()
) {
    val skippedCount: Int get() = skipped.values.sumOf { it.size }
    val scanning: SourceStatus? get() = sources.firstOrNull { it.state == SourceState.SCANNING }

    /** The names of the volumes that are not connected, each once. */
    val absent: List<String> get() = sources.filter { it.state == SourceState.ABSENT }.map { it.volumeName }.distinct()
}

/**
 * The live [LocalOverview] (docs/phase-4d-local-files.md §5 progress, §4 absent volumes): the sources table, the scan's
 * [ScanState], the mounted volumes and the index, put together off the main thread whenever one of them changes.
 */
@Singleton
class LocalOverviewModel @Inject constructor(
    private val sources: LocalSources,
    private val library: LocalLibrary,
    status: LocalScanStatus,
    private val volumes: VolumeMonitor,
    private val provider: LocalProvider,
    private val db: LocalDatabase
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    val overview: StateFlow<LocalOverview> =
        combine(sources.sources, status.state, volumes.volumes, library.version) { list, scan, _, _ -> list to scan }
            .mapLatest { (list, scan) -> build(list, scan) }
            .stateIn(scope, SharingStarted.Eagerly, LocalOverview())

    private suspend fun build(list: List<SourceEntity>, scan: ScanState): LocalOverview = withContext(Dispatchers.IO) {
        if (list.isEmpty()) return@withContext LocalOverview()
        val s = library.snapshot()
        val seen = db.library().volumes().associate { it.volumeId to it.lastSeenAt }
        val dao = db.sources()
        val statuses = list.map { source ->
            val mounted = volumes.isMounted(source.volumeId)
            val scanning = scan.running && scan.sourceId == source.id
            val outcome = source.lastScan?.let { runCatching { JSON.decodeFromString(ScanSummary.serializer(), it) }.getOrNull() }
            val state = when {
                !mounted -> SourceState.ABSENT
                scanning -> SourceState.SCANNING
                outcome?.outcome == ScanSummary.OUTCOME_NO_ACCESS || !hasGrant(source) -> SourceState.NO_ACCESS
                source.lastScanAt == null -> SourceState.NOT_SCANNED
                else -> SourceState.AVAILABLE
            }
            SourceStatus(
                id = source.id,
                volumeId = source.volumeId,
                volumeName = provider.volumeLabel(s, source.volumeId),
                folder = source.rootRel,
                state = state,
                tracks = s.countUnder(source.volumeId, source.rootRel),
                percent = if (scanning && scan.phase == ScanState.Phase.READING && scan.total > 0) scan.done * PERCENT / scan.total else null,
                done = if (scanning) scan.done else 0,
                total = if (scanning) scan.total else 0,
                scanFolder = scan.folder.takeIf { scanning },
                lastScanAt = source.lastScanAt,
                lastSeenAt = seen[source.volumeId],
                includeHidden = source.includeHidden,
                excludes = dao.excludes(source.id)
            )
        }
        val skipped = s.skipped
            .filter { file -> list.any { it.volumeId == file.volumeId && isUnder(file.relPath, it.rootRel) } }
            .groupBy { it.format ?: ScanSummary.UNREADABLE }
            .mapValues { (_, files) -> files.map { SkippedFile(provider.volumeLabel(s, it.volumeId), it.relPath) } }
        LocalOverview(statuses, list.sumOf { s.countUnder(it.volumeId, it.rootRel) }, skipped.toSortedMap())
    }

    private fun hasGrant(source: SourceEntity): Boolean = source.treeUri?.let(sources::hasGrant) ?: true

    private companion object {
        const val PERCENT = 100
        val JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
