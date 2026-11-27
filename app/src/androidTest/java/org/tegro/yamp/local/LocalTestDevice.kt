package org.tegro.yamp.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.tegro.yamp.data.provider.local.LocalSources
import org.tegro.yamp.data.provider.local.VolumeMonitor
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

/**
 * The emulator's SD card for the local files tests (docs/phase-4d-local-files.md, steps 3–4). Needs a public removable
 * volume — on the emulator: `adb shell sm set-virtual-disk true`, then `sm partition disk:<id> public`; without one
 * the tests are skipped. The test tree is `assets/local-tree.zip` (scripts/local-test-tree/generate.sh).
 */
object LocalTestDevice {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context: Context get() = instrumentation.targetContext
    private val testAssets get() = instrumentation.context.assets

    /** The SD card's UUID (`6917-1912`), or the test is skipped. */
    fun sdVolumeId(): String {
        val volume = context.getSystemService(StorageManager::class.java).storageVolumes
            .firstOrNull { it.isRemovable && it.uuid != null && it.state == android.os.Environment.MEDIA_MOUNTED }
        assumeTrue("no SD card: adb shell sm set-virtual-disk true; sm partition <disk> public", volume != null)
        return volume!!.uuid!!
    }

    /** `sm`'s id of the volume (`public:7,305`), for `sm mount / unmount`. */
    fun smVolumeId(uuid: String): String =
        shell("sm list-volumes public").lines().map { it.trim().split(' ') }.first { it.lastOrNull() == uuid }.first()

    /** Unmounts or mounts the SD card and waits until [monitor] has seen it. */
    fun setMounted(uuid: String, mounted: Boolean, monitor: VolumeMonitor) {
        shell("sm ${if (mounted) "mount" else "unmount"} ${smVolumeId(uuid)}")
        runBlocking { withTimeout(WAIT_MS) { monitor.volumes.first { (it[uuid]?.mounted == true) == mounted } } }
    }

    /** Runs a shell command as `shell` and returns its output. */
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    /** Runs a `sh` script fed through stdin (paths with spaces and Cyrillic need no escaping beyond single quotes). */
    fun shellScript(script: String): String {
        val (out, input) = instrumentation.uiAutomation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write(script.toByteArray()) }
        return ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes().decodeToString() }
    }

    /** The test tree's files: path in the tree → content. */
    fun treeFiles(): Map<String, ByteArray> = ZipInputStream(testAssets.open("local-tree.zip")).use { zip ->
        generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.associate { it.name to zip.readBytes() }.toSortedMap()
    }

    /** Writes the test tree to `/storage/<uuid>/<relPath>` as the shell user (a public folder the SAF picker offers). */
    fun pushTree(uuid: String, relPath: String) {
        val root = "/storage/$uuid/$relPath"
        val script = StringBuilder("rm -rf '$root'\nmkdir -p '$root'\n")
        for ((path, bytes) in treeFiles()) {
            val target = "$root/$path"
            script.append("mkdir -p '").append(target.substringBeforeLast('/')).append("'\n")
            script.append("base64 -d > '").append(target).append("' <<'EOF'\n")
                .append(Base64.encodeToString(bytes, Base64.DEFAULT)).append("EOF\n")
        }
        shellScript(script.toString())
    }

    /** Copies the test tree into [dir] with the File API (the app's own folder on the SD card). */
    fun copyTree(dir: File) {
        dir.deleteRecursively()
        for ((path, bytes) in treeFiles()) File(dir, path).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
    }

    /** The app's own files folder on the SD card (`/storage/<uuid>/Android/data/<package>/files`). */
    fun appDirOn(uuid: String): File = context.getExternalFilesDirs(null).filterNotNull().first { it.path.startsWith("/storage/$uuid/") }

    /**
     * A persisted read grant for the tree `/storage/<uuid>/<relPath>`: reused when the app holds it, else taken
     * through the system picker (opened at that folder; UiAutomator presses "Use this folder" / "Select" and, on
     * Android 10+, "Allow").
     */
    fun grantTree(uuid: String, relPath: String): Uri {
        val tree = DocumentsContract.buildTreeDocumentUri(LocalSources.EXTERNAL_STORAGE_AUTHORITY, "$uuid:$relPath")
        if (context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }) return tree
        val initial = DocumentsContract.buildDocumentUri(LocalSources.EXTERNAL_STORAGE_AUTHORITY, "$uuid:$relPath")
        val picked = AtomicReference<Uri?>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activityResultRegistry.register("tree", ActivityResultContracts.OpenDocumentTree()) { uri -> picked.set(uri) }
                    .launch(initial)
            }
            val device = UiDevice.getInstance(instrumentation)
            device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder|select"))), WAIT_MS)!!.click()
            // Android 10+ confirms the grant; Android 9 returns at once
            if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.P) {
                device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), WAIT_MS)!!.click()
            }
            val until = System.currentTimeMillis() + WAIT_MS
            while (picked.get() == null && System.currentTimeMillis() < until) Thread.sleep(POLL_MS)
            val uri = checkNotNull(picked.get()) { "the picker returned nothing" }
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return uri
        }
    }

    private const val WAIT_MS = 20_000L
    private const val POLL_MS = 100L
}
