package org.tegro.yamp.data.provider.local.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A volume seen by the app (index): its id (`1A2B-3C4D`, `primary`), the system's label and when it was last mounted. */
@Entity(tableName = "volumes")
data class VolumeEntity(
    @PrimaryKey val volumeId: String,
    val label: String,
    val lastSeenAt: Long
)

/**
 * A folder the user chose (user data). [rootRel] is volume-relative (`Music`, `` for a volume root); [treeUri] is the
 * SAF grant (API 30+ reads through it; API ≤ 29 maps it to a path and keeps it for later upgrades). [lastScan] is the
 * last scan's [org.tegro.yamp.data.provider.local.scan.ScanSummary] as JSON.
 */
@Entity(tableName = "sources", indices = [Index(value = ["volumeId", "rootRel"], unique = true)])
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val volumeId: String,
    val rootRel: String,
    val treeUri: String?,
    val includeHidden: Boolean = false,
    val addedAt: Long,
    val lastScanAt: Long? = null,
    val lastScan: String? = null
)

/** A folder the user excluded from a source (user data); [relPath] is volume-relative. */
@Entity(
    tableName = "excludes",
    primaryKeys = ["sourceId", "relPath"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)]
)
data class ExcludeEntity(val sourceId: Long, val relPath: String)

/** A listed directory (index). [artFile] = the folder cover's file name (FolderArt); [trackCount] = playable files in it. */
@Entity(tableName = "dirs", indices = [Index(value = ["volumeId", "relPath"], unique = true), Index("parentId")])
data class DirEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val volumeId: String,
    val relPath: String,
    val parentId: Long?,
    val name: String,
    val artFile: String?,
    val trackCount: Int = 0
)

/**
 * A candidate file (index). [supported] false = counted, not playable: a Stage 2 format by extension or codec
 * ([format] = `ape`, `alac`, …) or a file nothing could parse ([format] null). [quickHash] = the first 64 KB, for
 * move detection; [missingSince] set = a tombstone (gone from a successful listing, kept 30 days).
 */
@Entity(tableName = "files", indices = [Index(value = ["volumeId", "relPath"], unique = true), Index("dirId")])
data class FileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val volumeId: String,
    val relPath: String,
    val dirId: Long,
    val size: Long,
    val mtime: Long,
    val ext: String,
    val supported: Boolean,
    val format: String?,
    val quickHash: String?,
    val missingSince: Long? = null
)

/**
 * A playable track (index), keyed by its ref (`local:track:<volumeId>/<relPath>`). [artists] is the artist tag as
 * read (`A; B`); [art] is [ART_EMBEDDED], [ART_FILE] (the folder's [DirEntity.artFile]) or [ART_NONE]. [cueIndex],
 * [startMs], [endMs] are for Stage 2 cue tracks. [searchText] is the normalised text search matches.
 */
@Entity(tableName = "tracks", indices = [Index("fileId"), Index("albumKey")])
data class TrackEntity(
    @PrimaryKey val ref: String,
    val fileId: Long,
    val cueIndex: Int? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val title: String,
    val artists: String?,
    val albumArtist: String?,
    val album: String?,
    val albumKey: String,
    val disc: Int?,
    val trackNo: Int?,
    val year: Int?,
    val genre: String?,
    val durationMs: Long,
    val art: String,
    val searchText: String,
    val firstSeenAt: Long
) {
    companion object {
        const val ART_EMBEDDED = "embedded"
        const val ART_FILE = "file"
        const val ART_NONE = "none"
    }
}

/** A favourite track (user data; the toggle needs the Phase 5 Favorites capability). */
@Entity(tableName = "favourites")
data class FavouriteEntity(@PrimaryKey val ref: String, val addedAt: Long)

/** A same-volume rename / move (user data): the resolver follows [oldRef] → [newRef]; chains are collapsed. */
@Entity(tableName = "moves", indices = [Index("newRef")])
data class MoveEntity(@PrimaryKey val oldRef: String, val newRef: String, val at: Long)
