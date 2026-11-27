package org.tegro.yamp.data.provider.local.scan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The scan's progress for the Sources screen (docs/phase-4d-local-files.md §5): "Scanning USB · 1 240 / 3 100 ·
 * Fleur/2005…". [done] / [total] count the files to read of the current source ([total] 0 while listing);
 * [folder] is the folder being listed or read; [skipped] = files by unsupported format so far (`ape` → 12).
 */
data class ScanState(
    val phase: Phase = Phase.IDLE,
    val sourceId: Long? = null,
    val volumeLabel: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val folder: String? = null,
    val skipped: Map<String, Int> = emptyMap()
) {
    enum class Phase { IDLE, LISTING, READING, FINISHING }

    val running: Boolean get() = phase != Phase.IDLE
}

/**
 * What one scan of a source found, kept as JSON in `sources.lastScan`. [outcome] is [OUTCOME_DONE], or why nothing
 * was changed: [OUTCOME_NOT_CONNECTED] (volume absent, or it went away mid-scan), [OUTCOME_NO_ACCESS] (no grant / the
 * root can't be listed), [OUTCOME_CANCELLED]. [tracks] = playable tracks under the source; [skipped] = present files
 * by unsupported format (`ape`, `alac`; `unreadable` for files nothing could parse).
 */
@Serializable
data class ScanSummary(
    val outcome: String,
    val tracks: Int = 0,
    val read: Int = 0,
    val unchanged: Int = 0,
    val removed: Int = 0,
    val moved: Int = 0,
    val failed: Int = 0,
    val skipped: Map<String, Int> = emptyMap()
) {
    companion object {
        const val OUTCOME_DONE = "done"
        const val OUTCOME_NOT_CONNECTED = "not_connected"
        const val OUTCOME_NO_ACCESS = "no_access"
        const val OUTCOME_CANCELLED = "cancelled"
        const val UNREADABLE = "unreadable"
    }
}

/** The one [ScanState] the scanner writes and the screens read. */
@Singleton
class LocalScanStatus @Inject constructor() {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    internal fun set(state: ScanState) {
        _state.value = state
    }
}
