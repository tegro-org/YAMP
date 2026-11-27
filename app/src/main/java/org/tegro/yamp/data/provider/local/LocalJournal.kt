package org.tegro.yamp.data.provider.local

import org.tegro.yamp.data.diagnostics.DiagnosticLog
import org.tegro.yamp.data.diagnostics.logHash
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The local provider's lines in the journal (tag `LOCAL`): the volumes as the app sees them, how a folder was added,
 * which back-end reads each source and why one can't be read — what a head unit's log must show (docs/phase-4d §10:
 * no paths; a volume id is shown as [vol], a path as [path], both hashed).
 */
@Singleton
class LocalJournal @Inject constructor(private val log: DiagnosticLog) {
    fun event(message: String) = log.event(TAG, message)

    private val lastMounts = ConcurrentHashMap<String, String>()

    /**
     * The volumes the system lists now, one line; then, for a removable volume, one `MOUNT` line with every directory
     * tried for it and what the file API said — written again only when it changes.
     */
    fun volumes(all: Collection<LocalVolume>) {
        event("VOLUMES " + all.joinToString(", ") { volume(it) }.ifEmpty { "none" })
        for (v in all) {
            val trace = mountTrace(v) ?: continue
            if (lastMounts.put(v.id, trace) != trace) event(trace)
        }
    }

    companion object {
        private const val TAG = "LOCAL"

        /** `primary` or `vol#1a2b3c4d`. */
        fun vol(volumeId: String): String = if (volumeId == LocalVolume.PRIMARY) volumeId else "vol" + logHash(volumeId)

        /** `root` or `path#1a2b3c4d`. */
        fun path(relPath: String): String = if (relPath.isEmpty()) "root" else "path" + logHash(relPath)

        private val UUID_NAME = Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}|[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}")

        /** A directory name under `/storage`: the vendor's mount name (`udisk1`) as is, a volume UUID hashed. */
        fun mountName(name: String): String = if (UUID_NAME.matches(name)) vol(name) else name

        /**
         * `MOUNT vol#… uuid:vol#…=ENOENT child:udisk1=ok media_rw:vol#…=EACCES → child:udisk1`: each candidate and its result,
         * then the one chosen (`none` if no candidate could be listed). Null for the primary volume and on API ≥ 30.
         */
        fun mountTrace(v: LocalVolume): String? {
            val mount = v.mount ?: return null
            if (v.id == LocalVolume.PRIMARY) return null
            val tried = mount.attempts.joinToString(" ") { "${it.candidate.source.tag}:${mountName(it.candidate.dir.name)}=${it.result}" }
            val chosen = mount.attempts.firstOrNull { it.readable && it.candidate.dir == mount.dir }
            return "MOUNT ${vol(v.id)} $tried → " + (chosen?.let { "${it.candidate.source.tag}:${mountName(it.candidate.dir.name)}" } ?: "none")
        }

        /** `vol#… removable mounted file=ENOENT` — what decides the back-end on API ≤ 29. */
        fun volume(v: LocalVolume): String = listOfNotNull(
            vol(v.id),
            if (v.id == LocalVolume.PRIMARY) null else if (v.removable) "removable" else "fixed",
            if (v.mounted) "mounted" else "unmounted",
            "file=${v.fileAccess}"
        ).joinToString(" ")
    }
}
