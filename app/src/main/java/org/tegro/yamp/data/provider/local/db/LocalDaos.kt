package org.tegro.yamp.data.provider.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Sources, their excludes and the volumes (user data + the volume labels). */
@Dao
interface LocalSourceDao {
    @Query("SELECT * FROM sources ORDER BY addedAt, id")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY addedAt, id")
    fun all(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    fun byId(id: Long): SourceEntity?

    @Query("SELECT * FROM sources WHERE volumeId = :volumeId AND rootRel = :rootRel")
    fun find(volumeId: String, rootRel: String): SourceEntity?

    @Insert
    fun insert(source: SourceEntity): Long

    @Query("DELETE FROM sources WHERE id = :id")
    fun delete(id: Long)

    @Query("UPDATE sources SET includeHidden = :include WHERE id = :id")
    fun setIncludeHidden(id: Long, include: Boolean)

    @Query("UPDATE sources SET lastScanAt = :at, lastScan = :summary WHERE id = :id")
    fun setLastScan(id: Long, at: Long, summary: String)

    @Query("SELECT relPath FROM excludes WHERE sourceId = :sourceId ORDER BY relPath")
    fun excludes(sourceId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun addExclude(exclude: ExcludeEntity)

    @Query("DELETE FROM excludes WHERE sourceId = :sourceId AND relPath = :relPath")
    fun removeExclude(sourceId: Long, relPath: String)

    @Upsert
    fun upsertVolume(volume: VolumeEntity)

    @Query("SELECT * FROM volumes WHERE volumeId = :volumeId")
    fun volume(volumeId: String): VolumeEntity?

    @Query("SELECT * FROM volumes ORDER BY volumeId")
    fun observeVolumes(): Flow<List<VolumeEntity>>
}

/**
 * The scanner's access to the index. Blocking on purpose: the scan runs on its own background thread and batches
 * its writes in transactions. Id lists are passed in chunks (SQLite's 999-variable limit on old devices).
 */
@Dao
interface LocalIndexDao {
    @Query("SELECT * FROM files WHERE volumeId = :volumeId")
    fun filesOfVolume(volumeId: String): List<FileEntity>

    @Insert
    fun insertFile(file: FileEntity): Long

    @Update
    fun updateFile(file: FileEntity)

    @Query("UPDATE files SET missingSince = :at WHERE id IN (:ids)")
    fun markMissing(ids: List<Long>, at: Long)

    @Query("DELETE FROM files WHERE id IN (:ids)")
    fun deleteFiles(ids: List<Long>)

    @Query("DELETE FROM tracks WHERE fileId IN (:fileIds)")
    fun deleteTracksOfFiles(fileIds: List<Long>)

    @Query("SELECT * FROM dirs WHERE volumeId = :volumeId")
    fun dirsOfVolume(volumeId: String): List<DirEntity>

    @Insert
    fun insertDir(dir: DirEntity): Long

    @Update
    fun updateDir(dir: DirEntity)

    @Query("DELETE FROM dirs WHERE id IN (:ids)")
    fun deleteDirs(ids: List<Long>)

    /** Every dir of [volumeId]: its playable, present files. */
    @Query(
        "UPDATE dirs SET trackCount = (SELECT COUNT(*) FROM files WHERE files.dirId = dirs.id AND files.supported = 1 " +
            "AND files.missingSince IS NULL) WHERE volumeId = :volumeId"
    )
    fun recountDirs(volumeId: String)

    /**
     * A track without an embedded picture shows its folder's cover: re-derived after every scan, since a `cover.jpg`
     * can appear or go while the audio files stay unchanged (and are not re-read).
     */
    @Query(
        "UPDATE tracks SET art = CASE WHEN (SELECT d.artFile FROM files f JOIN dirs d ON d.id = f.dirId " +
            "WHERE f.id = tracks.fileId) IS NULL THEN 'none' ELSE 'file' END WHERE art != 'embedded'"
    )
    fun refreshFolderArt()

    @Upsert
    fun upsertTrack(track: TrackEntity)

    @Query("SELECT firstSeenAt FROM tracks WHERE ref = :ref")
    fun firstSeenAt(ref: String): Long?

    @Query("SELECT * FROM tracks ORDER BY ref")
    fun tracks(): List<TrackEntity>

    @Query("SELECT * FROM files ORDER BY volumeId, relPath")
    fun files(): List<FileEntity>

    @Query("SELECT * FROM dirs ORDER BY volumeId, relPath")
    fun dirs(): List<DirEntity>

    @Upsert
    fun upsertMove(move: MoveEntity)

    /** Chains collapse: whatever pointed at [oldRef] now points at [newRef]. */
    @Query("UPDATE moves SET newRef = :newRef WHERE newRef = :oldRef")
    fun retarget(oldRef: String, newRef: String)

    /** A file is back at a path an alias pointed away from: the real file wins. */
    @Query("DELETE FROM moves WHERE oldRef = :ref")
    fun deleteAliasFrom(ref: String)

    /** A chain that came back to where it started (`A → B`, then `B → A`) leaves `A → A`: gone. */
    @Query("DELETE FROM moves WHERE oldRef = newRef")
    fun deleteSelfAliases()

    @Query("SELECT * FROM moves ORDER BY oldRef")
    fun moves(): List<MoveEntity>
}

/** A present track with where its file is: what the library pages, the resolver and the covers read. */
data class LibraryTrackRow(
    val ref: String,
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
    val firstSeenAt: Long,
    val fileId: Long,
    val dirId: Long,
    val volumeId: String,
    val relPath: String,
    val mtime: Long,
    val format: String?,
    val searchText: String
)

/** The library's reads (docs/phase-4d-local-files.md §8): whole tables, the provider keeps one snapshot in memory. */
@Dao
interface LocalLibraryDao {
    @Query(
        "SELECT t.ref, t.title, t.artists, t.albumArtist, t.album, t.albumKey, t.disc, t.trackNo, t.year, t.genre, t.durationMs, " +
            "t.art, t.firstSeenAt, t.fileId, f.dirId, f.volumeId, f.relPath, f.mtime, f.format, t.searchText " +
            "FROM tracks t JOIN files f ON f.id = t.fileId WHERE f.missingSince IS NULL AND f.supported = 1"
    )
    fun tracks(): List<LibraryTrackRow>

    @Query("SELECT * FROM dirs")
    fun dirs(): List<DirEntity>

    @Query("SELECT * FROM volumes")
    fun volumes(): List<VolumeEntity>

    @Query("SELECT * FROM favourites ORDER BY addedAt DESC")
    fun favourites(): List<FavouriteEntity>

    @Query("SELECT * FROM moves")
    fun moves(): List<MoveEntity>

    @Query("SELECT * FROM files WHERE id = :id")
    fun file(id: Long): FileEntity?

    @Query("SELECT * FROM dirs WHERE id = :id")
    fun dir(id: Long): DirEntity?

    /** Present files the scanner counted as not playable (a Stage 2 format, or unreadable), by path. */
    @Query("SELECT * FROM files WHERE missingSince IS NULL AND supported = 0 ORDER BY volumeId, relPath")
    fun skippedFiles(): List<FileEntity>
}
