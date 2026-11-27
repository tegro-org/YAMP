package org.tegro.yamp.data.provider.local.scan

import android.content.Context
import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.inspector.MetadataRetriever
import org.tegro.yamp.data.provider.local.SourceFs
import org.tegro.yamp.provider.local.AudioFormats
import org.tegro.yamp.provider.local.Id3v1
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * [TagReader] over Media3's `MetadataRetriever` (docs/phase-4d-local-files.md §6, D97): the same extractors as
 * playback, so "readable ⇔ playable"; the audio track's codec must have a decoder on this device ([decodable]), else
 * the file is [TagRead.Unsupported] (ALAC). ID3v1 is read for an MP3 without an ID3v2 title. WAV `LIST/INFO` is not
 * read: Media3 ignores it and the platform retriever returned no title for it either (Android 14 emulator), so a WAV
 * shows its file name.
 */
@OptIn(UnstableApi::class)
class Media3TagReader(
    private val context: Context,
    private val decodable: (String) -> Boolean = PlatformDecoders::has
) : TagReader {

    override fun read(fs: SourceFs, relPath: String): TagRead {
        val ext = AudioFormats.extension(relPath)
        val (groups, durationUs) = try {
            MetadataRetriever.Builder(context, MediaItem.fromUri(fs.uri(relPath))).build().use { retriever ->
                retriever.retrieveTrackGroups().get(TIMEOUT_S, TimeUnit.SECONDS) to
                    retriever.retrieveDurationUs().get(TIMEOUT_S, TimeUnit.SECONDS)
            }
        } catch (e: ExecutionException) {
            return if (e.cause is ParserException || e.cause !is IOException) TagRead.Unreadable else TagRead.IoFailed
        } catch (_: TimeoutException) {
            return TagRead.Unreadable
        }
        val formats = formatsOf(groups)
        val audio = formats.firstOrNull { MimeTypes.isAudio(it.sampleMimeType) } ?: return TagRead.Unreadable
        val mime = audio.sampleMimeType ?: return TagRead.Unreadable
        val format = formatName(mime)
        if (!decodable(mime)) return TagRead.Unsupported(format)

        val entries = formats.flatMap { f -> f.metadata?.let(::entriesOf).orEmpty() }
        var tags = Media3Tags.map(entries, id3 = ext in ID3_CONTAINERS)
        if (tags.title == null && ext == "mp3") tags = Media3Tags.withId3v1(tags, runCatching { Id3v1.parse(tail(fs, relPath)) }.getOrNull())
        val durationMs = if (durationUs == C.TIME_UNSET) 0L else durationUs / US_PER_MS
        return TagRead.Ok(tags, durationMs, Media3Tags.hasPicture(entries), format)
    }

    private fun formatsOf(groups: TrackGroupArray) =
        (0 until groups.length).flatMap { g -> groups[g].let { group -> (0 until group.length).map(group::getFormat) } }

    private fun entriesOf(metadata: Metadata): List<Metadata.Entry> = (0 until metadata.length()).map(metadata::get)

    /** The last [Id3v1.SIZE] bytes of the file. */
    private fun tail(fs: SourceFs, relPath: String): ByteArray = fs.open(relPath).use { pfd ->
        FileInputStream(pfd.fileDescriptor).channel.use { channel ->
            val size = channel.size()
            if (size < Id3v1.SIZE) return ByteArray(0)
            val buffer = java.nio.ByteBuffer.allocate(Id3v1.SIZE)
            channel.position(size - Id3v1.SIZE)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
            buffer.array()
        }
    }

    companion object {
        private const val TIMEOUT_S = 15L
        private const val US_PER_MS = 1000L
        private val ID3_CONTAINERS = setOf("mp3", "wav")

        /** `audio/mpeg` → `mp3`, `audio/mp4a-latm` → `aac`, `audio/alac` → `alac`, else the subtype. */
        fun formatName(mime: String): String = when (mime) {
            MimeTypes.AUDIO_MPEG -> "mp3"
            MimeTypes.AUDIO_AAC -> "aac"
            MimeTypes.AUDIO_RAW -> "pcm"
            else -> mime.substringAfter('/').removePrefix("x-")
        }
    }
}

/** Whether this device has a decoder for an audio MIME type (raw PCM needs none). Cached per type. */
object PlatformDecoders {
    private val known = ConcurrentHashMap<String, Boolean>()
    private val decoders by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }.flatMap { it.supportedTypes.toList() }
            .map { it.lowercase() }.toSet()
    }

    fun has(mime: String): Boolean = known.getOrPut(mime) { mime == MimeTypes.AUDIO_RAW || mime.lowercase() in decoders }
}
