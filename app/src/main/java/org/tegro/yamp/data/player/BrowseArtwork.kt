package org.tegro.yamp.data.player

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tegro.yamp.BuildConfig
import org.tegro.yamp.data.provider.jellyfin.JellyfinImageAuth
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Covers for Android Auto (gap (d)). The car loads artwork only from `content://` and `android.resource://` URIs and
 * can't send the Jellyfin header, so a remote cover becomes `content://<app>.artwork/<SHA-256 of its URL>`, served by
 * [ArtworkProvider]. The URI says nothing about the server or the account: the key is a hash, the URL behind it is
 * kept in memory only (and never carries a token — the header is added when fetching), and only covers the tree
 * named can be fetched (no open proxy).
 */
object BrowseArtwork {
    val AUTHORITY: String = BuildConfig.APPLICATION_ID + ".artwork"

    private const val MAX_KNOWN = 4_000
    private val known = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_KNOWN
    }

    /** A URI the car can load for [url]: bundled images as they are, a remote one through [ArtworkProvider]. */
    fun uriFor(url: String?): Uri? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("android.resource://") || url.startsWith("content://")) return url.toUri()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val key = keyOf(url)
        synchronized(known) { known[key] = url }
        return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(key).build()
    }

    /** The remote URL a key stands for, while this process knows it. */
    internal fun urlOf(key: String): String? = synchronized(known) { known[key] }

    internal fun keyOf(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    internal val KEY = Regex("^[0-9a-f]{64}$")
}

/**
 * Serves [BrowseArtwork]'s covers to Android Auto, read-only: from its own small cache directory, else fetched once
 * (a Jellyfin server's covers with that account's header, [JellyfinImageAuth]; every other host gets nothing). Local
 * files' covers ([org.tegro.yamp.data.provider.local.LocalArt]) come from the device, under signed keys — one URL form
 * for the app's images, the player and the car.
 */
class ArtworkProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun imageAuth(): JellyfinImageAuth
        fun localArt(): org.tegro.yamp.data.provider.local.LocalArt
    }

    private val client: OkHttpClient by lazy {
        val auth = EntryPointAccessors.fromApplication(requireNotNull(context).applicationContext, Deps::class.java).imageAuth()
        OkHttpClient.Builder()
            // Per hop: a redirect to another host never carries the header
            .addNetworkInterceptor { chain -> auth.intercept(chain) }
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        // A local file's cover (docs/phase-4d-local-files.md §7): `local/<signed key>/<px>`, made on the device
        val segments = uri.pathSegments
        if (segments.size == 3 && segments[0] == org.tegro.yamp.data.provider.local.LocalArt.PATH) {
            val px = segments[2].toIntOrNull() ?: throw FileNotFoundException("unknown")
            val deps = EntryPointAccessors.fromApplication(requireNotNull(context).applicationContext, Deps::class.java)
            return deps.localArt().open(segments[1], px)
        }
        val key = uri.lastPathSegment?.takeIf { BrowseArtwork.KEY.matches(it) } ?: throw FileNotFoundException("unknown")
        val dir = File(requireNotNull(context).cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, key)
        if (!file.exists()) {
            val url = BrowseArtwork.urlOf(key) ?: throw FileNotFoundException("unknown")
            if (!fetch(url, file, dir)) throw FileNotFoundException("not loaded")
            trim(dir)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Saves [url] as [file]; false on an HTTP or network error, or an image over [MAX_BYTES]. */
    private fun fetch(url: String, file: File, dir: File): Boolean {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val part = File(dir, file.name + ".part")
        val saved = try {
            client.newCall(request).execute().use { response -> save(response, part) }
        } catch (e: java.io.IOException) {
            android.util.Log.w("ArtworkProvider", "Cover not loaded: ${e.javaClass.simpleName}")
            false
        }
        val done = saved && part.renameTo(file)
        if (!done) part.delete()
        return done
    }

    private fun save(response: okhttp3.Response, part: File): Boolean {
        val body = response.body
        if (!response.isSuccessful || body.contentLength() > MAX_BYTES) return false
        return part.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } } <= MAX_BYTES
    }

    /** Keeps the newest [MAX_FILES] covers. */
    private fun trim(dir: File) {
        val files = dir.listFiles()?.takeIf { it.size > MAX_FILES } ?: return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    override fun getType(uri: Uri): String = if (uri.pathSegments.firstOrNull() == org.tegro.yamp.data.provider.local.LocalArt.PATH) "image/jpeg" else "image/*"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val DIR = "car-artwork"
        const val MAX_FILES = 500
        const val MAX_BYTES = 4L * 1024 * 1024
        const val TIMEOUT_S = 10L
        /** Some logo hosts (Wikimedia) refuse requests without a browser User-Agent, as in the app's image loader. */
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
