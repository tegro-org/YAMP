package org.tegro.yamp.provider.local

import java.io.File

/** Where a mount candidate came from; [accepted] = may be chosen (an [OTHER_VOLUME] one is only probed for the journal). */
enum class MountSource(val tag: String, val accepted: Boolean) {
    /** The volume's own directory: the primary volume's, or `StorageVolume.getDirectory()` where the API has it. */
    OWN("own", true),

    /** `/storage/<uuid>`: the standard sdcardfs view. */
    UUID("uuid", true),

    /** A `/storage` child that `StorageManager` maps to this volume: the vendor's name (`udisk1`), not the UUID. */
    CHILD("child", true),

    /** A `/storage` child that belongs to another volume (or to none the system knows). */
    OTHER_VOLUME("other", false),

    /** `/mnt/media_rw/<uuid>`: the internal mount, last resort (apps can rarely read it). */
    MEDIA_RW("media_rw", true)
}

data class MountCandidate(val dir: File, val source: MountSource)

/** What probing one candidate said: [VolumeMounts.OK] or the error (`ENOENT`, `EACCES`, `unlistable`). */
data class MountAttempt(val candidate: MountCandidate, val result: String) {
    val readable: Boolean get() = result == VolumeMounts.OK
}

/** [dir] is the readable mount chosen, null when none; [attempts] is every candidate probed, in order. */
data class MountSelection(val dir: File?, val attempts: List<MountAttempt>) {
    /** `ok`, or the most actionable error: the first one that is not `ENOENT` (not mounted for apps), else `ENOENT`. */
    val result: String
        get() = if (dir != null) VolumeMounts.OK
        else attempts.firstOrNull { it.candidate.source.accepted && it.result != VolumeMounts.ABSENT }?.result ?: VolumeMounts.ABSENT
}

/**
 * Finds where the file API can read a mounted volume on API ≤ 29 (docs/phase-4d-local-files.md, D99). The platform
 * names a removable mount however the vendor chose — the 2026-11-24 head unit mounts the stick as `/storage/udisk1`,
 * not by UUID — so the directory name is never assumed to be the UUID: a `/storage` child counts only when the system
 * maps it to this volume. Pure: the system calls come in as lambdas.
 */
object VolumeMounts {
    const val OK = "ok"
    const val ABSENT = "ENOENT"

    /** `/storage` children that are views of the primary volume, never a removable one. */
    val SKIPPED_STORAGE_CHILDREN = setOf("emulated", "self")

    /**
     * The candidates for the removable volume [uuid], in the order they are tried: its own directory, `/storage/<uuid>`,
     * the `/storage` children [belongsToVolume] maps to it, then `/mnt/media_rw/<uuid>`. The children that belong to
     * another volume follow, to be probed for the journal only. A path appears once.
     */
    fun candidates(
        uuid: String,
        own: File? = null,
        storageChildren: List<String>,
        belongsToVolume: (File) -> Boolean,
        storageRoot: File = File("/storage"),
        mediaRwRoot: File = File("/mnt/media_rw")
    ): List<MountCandidate> {
        val children = storageChildren.filter { it !in SKIPPED_STORAGE_CHILDREN && it != uuid }.sorted()
            .map { File(storageRoot, it) }
        val (mine, others) = children.partition(belongsToVolume)
        val all = listOfNotNull(own?.let { MountCandidate(it, MountSource.OWN) }) +
            MountCandidate(File(storageRoot, uuid), MountSource.UUID) +
            mine.map { MountCandidate(it, MountSource.CHILD) } +
            MountCandidate(File(mediaRwRoot, uuid), MountSource.MEDIA_RW) +
            others.map { MountCandidate(it, MountSource.OTHER_VOLUME) }
        return all.distinctBy { it.dir.path }
    }

    /** Probes every candidate once; the first accepted one that [probe] calls [OK] is the mount. */
    fun select(candidates: List<MountCandidate>, probe: (File) -> String): MountSelection {
        val attempts = ArrayList<MountAttempt>(candidates.size)
        var chosen: File? = null
        for (candidate in candidates) {
            val attempt = MountAttempt(candidate, probe(candidate.dir))
            attempts += attempt
            if (chosen == null && candidate.source.accepted && attempt.readable) chosen = candidate.dir
        }
        return MountSelection(chosen, attempts)
    }
}
