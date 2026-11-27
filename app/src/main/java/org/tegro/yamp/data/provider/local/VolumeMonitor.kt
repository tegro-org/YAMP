package org.tegro.yamp.data.provider.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tegro.yamp.provider.local.MountCandidate
import org.tegro.yamp.provider.local.MountSelection
import org.tegro.yamp.provider.local.MountSource
import org.tegro.yamp.provider.local.VolumeMounts
import java.io.File
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A storage volume as the system reports it now. [id] is the volume UUID (`1A2B-3C4D`), [PRIMARY] for the built-in
 * shared storage; [directory] is its readable mount point, used by the File back-end on API ≤ 29 — null there when
 * none of the candidates (`/storage/<uuid>`, the `/storage` directory the system maps to it, `/mnt/media_rw/<uuid>`)
 * can be listed by the file API (D93, D99).
 *
 * [removable], [fileAccess] and [mount] are for the journal: `ok`, or why the mount point can't be read by the file API
 * (`ENOENT`, `EACCES`, `unlistable`; `n/a` on API ≥ 30, where the file API is not used), and every candidate tried.
 */
data class LocalVolume(
    val id: String,
    val label: String,
    val mounted: Boolean,
    val directory: File?,
    val removable: Boolean = false,
    val fileAccess: String = FILE_ACCESS_NA,
    val mount: MountSelection? = null
) {
    companion object {
        const val PRIMARY = "primary"
        const val FILE_ACCESS_OK = "ok"
        const val FILE_ACCESS_NA = "n/a"
    }
}

/** What the scanner asks about volumes (a fake in tests). */
interface VolumeState {
    fun isMounted(volumeId: String): Boolean

    /** The system's name of the volume ("SD card", "SanDisk USB drive"); null when the system doesn't list it now. */
    fun label(volumeId: String): String?
}

/**
 * Which volumes are mounted (docs/phase-4d-local-files.md §4, D47), from `StorageManager`: re-read on every
 * `registerStorageVolumeCallback` change on API 30+, on `ACTION_MEDIA_MOUNTED / UNMOUNTED / EJECT / REMOVED /
 * BAD_REMOVAL` (scheme `file`, exempt from the implicit-broadcast ban) on API ≤ 29. A volume missing from the
 * system's list is simply not mounted: nothing is concluded from its absence beyond that.
 */
@Singleton
class VolumeMonitor @Inject constructor(@ApplicationContext private val context: Context) : VolumeState {
    private val storage = context.getSystemService(StorageManager::class.java)
    private val _volumes = MutableStateFlow(read())

    /** The volumes the system lists now, by id. */
    val volumes: StateFlow<Map<String, LocalVolume>> = _volumes.asStateFlow()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) registerCallback() else registerReceiver()
    }

    override fun isMounted(volumeId: String): Boolean = _volumes.value[volumeId]?.mounted == true

    override fun label(volumeId: String): String? = _volumes.value[volumeId]?.label

    fun volume(volumeId: String): LocalVolume? = _volumes.value[volumeId]

    /** Re-reads the system's list (also called by the triggers; cheap). */
    fun refresh() {
        _volumes.value = read()
    }

    private fun read(): Map<String, LocalVolume> =
        storage?.storageVolumes.orEmpty().mapNotNull(::toLocal).associateBy { it.id }

    private fun toLocal(volume: StorageVolume): LocalVolume? {
        val id = volume.uuid ?: if (volume.isPrimary) LocalVolume.PRIMARY else return null
        val state = volume.state
        val mounted = state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
        val label = volume.getDescription(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return LocalVolume(id, label, mounted, volume.directory, volume.isRemovable)
        // API ≤ 29 has no public getDirectory() (the hidden getPath() is not used): find the mount among the standard view,
        // the /storage directory the system maps to this volume (a unit may name it `udisk1`, not by UUID) and the
        // internal /mnt/media_rw mount, accepting what the file API can really list (D99).
        val candidates = if (id == LocalVolume.PRIMARY) {
            @Suppress("DEPRECATION")
            listOf(MountCandidate(Environment.getExternalStorageDirectory(), MountSource.OWN))
        } else {
            VolumeMounts.candidates(
                uuid = id,
                storageChildren = File(STORAGE_ROOT).list()?.toList().orEmpty(),
                belongsToVolume = { belongsTo(it, id) }
            )
        }
        // The primary volume is only access-checked: a listing would fail before the storage permission is granted, and
        // nothing re-reads the volumes when it is
        val mount = VolumeMounts.select(candidates, if (id == LocalVolume.PRIMARY) ::access else ::listability)
        return LocalVolume(id, label, mounted, mount.dir, volume.isRemovable, mount.result, mount)
    }

    /** Whether the system maps [dir] to the volume [uuid] and that volume is mounted (not by comparing names). */
    private fun belongsTo(dir: File, uuid: String): Boolean {
        val volume = runCatching { storage?.getStorageVolume(dir) }.getOrNull() ?: return false
        return volume.uuid == uuid && volume.state.let { it == Environment.MEDIA_MOUNTED || it == Environment.MEDIA_MOUNTED_READ_ONLY }
    }

    /** `ok` when the file API can list [dir]; else the errno name (`ENOENT`: not mounted for apps, `EACCES`) or `unlistable`. */
    private fun listability(dir: File): String {
        val access = access(dir)
        if (access != VolumeMounts.OK) return access
        return if (dir.canRead() && dir.list() != null) VolumeMounts.OK else UNLISTABLE
    }

    private fun access(dir: File): String = try {
        Os.access(dir.path, OsConstants.R_OK or OsConstants.X_OK)
        VolumeMounts.OK
    } catch (e: ErrnoException) {
        OsConstants.errnoName(e.errno) ?: "errno ${e.errno}"
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun registerCallback() {
        val direct = Executor { it.run() }
        storage?.registerStorageVolumeCallback(
            direct,
            object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) = refresh()
            }
        )
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            MEDIA_ACTIONS.forEach(::addAction)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = refresh()
            },
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private companion object {
        const val STORAGE_ROOT = "/storage"
        const val UNLISTABLE = "unlistable"
        val MEDIA_ACTIONS = listOf(
            Intent.ACTION_MEDIA_MOUNTED,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_BAD_REMOVAL
        )
    }
}
