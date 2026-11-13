package com.xplayer.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {
    @Query("SELECT id, normalizedName FROM artists")
    suspend fun idsByName(): List<ArtistKeyRow>

    @Insert
    suspend fun insert(artist: ArtistEntity): Long

    @Query(
        """
        SELECT ar.id AS id, ar.name AS name,
          (SELECT COUNT(DISTINCT t.albumId) FROM tracks t WHERE t.artistId = ar.id AND ${Sql.VISIBLE}) AS albumCount,
          (SELECT COUNT(*) FROM tracks t WHERE t.artistId = ar.id AND ${Sql.VISIBLE}) AS trackCount
        FROM artists ar
        WHERE EXISTS (SELECT 1 FROM tracks t WHERE t.artistId = ar.id AND ${Sql.VISIBLE})
        """,
    )
    fun observeArtists(): Flow<List<ArtistRow>>

    @Query(
        """
        SELECT ar.id AS id, ar.name AS name,
          (SELECT COUNT(DISTINCT t.albumId) FROM tracks t WHERE t.artistId = ar.id AND ${Sql.VISIBLE}) AS albumCount,
          (SELECT COUNT(*) FROM tracks t WHERE t.artistId = ar.id AND ${Sql.VISIBLE}) AS trackCount
        FROM artists ar WHERE ar.id = :id
        """,
    )
    fun observeArtist(id: Long): Flow<ArtistRow?>

    @Query(
        """
        SELECT ar.id AS id, ar.name AS name,
          (SELECT COUNT(DISTINCT t.albumId) FROM tracks t WHERE t.artistId = ar.id AND ${Sql.VISIBLE}) AS albumCount,
          (SELECT COUNT(*) FROM tracks t WHERE t.artistId = ar.id AND ${Sql.VISIBLE}) AS trackCount
        FROM artists ar
        WHERE ar.normalizedName LIKE '%' || :pattern || '%' ESCAPE '\'
          AND EXISTS (SELECT 1 FROM tracks t WHERE t.artistId = ar.id AND ${Sql.VISIBLE})
        ORDER BY ar.name COLLATE NOCASE LIMIT :limit
        """,
    )
    suspend fun search(pattern: String, limit: Int): List<ArtistRow>
}

data class ArtistKeyRow(val id: Long, val normalizedName: String)
data class AlbumKeyRow(val id: Long, val albumKey: String)
data class IdFingerprint(val id: Long, val fingerprint: String)

@Dao
interface AlbumDao {
    @Query("SELECT id, albumKey FROM albums")
    suspend fun idsByKey(): List<AlbumKeyRow>

    @Insert
    suspend fun insert(album: AlbumEntity): Long

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getById(id: Long): AlbumEntity?

    @Query("SELECT * FROM albums WHERE albumKey = :key")
    suspend fun getByKey(key: String): AlbumEntity?

    /** Recomputes year and date-added from the member tracks. Never touches user-owned tables. */
    @Query(
        """
        UPDATE albums SET
          year = COALESCE((SELECT MAX(t.year) FROM tracks t WHERE t.albumId = albums.id), year),
          dateAddedSec = COALESCE((SELECT MIN(t.dateAddedSec) FROM tracks t WHERE t.albumId = albums.id), dateAddedSec)
        """,
    )
    suspend fun refreshAggregates()

    @Query("UPDATE albums SET lastPlayedAt = :at WHERE id = :albumId")
    suspend fun setLastPlayed(albumId: Long, at: Long)

    @Query("UPDATE albums SET lastPlayedAt = 0")
    suspend fun clearLastPlayed()

    @Query("${Sql.ALBUM_SELECT} WHERE ${Sql.ALBUM_HAS_VISIBLE_TRACKS}")
    fun observeAlbums(): Flow<List<AlbumRow>>

    @Query("${Sql.ALBUM_SELECT} WHERE al.id = :id")
    fun observeAlbum(id: Long): Flow<AlbumRow?>

    @Query(
        """
        ${Sql.ALBUM_SELECT}
        WHERE al.id IN (SELECT t.albumId FROM tracks t WHERE t.artistId = :artistId AND ${Sql.VISIBLE})
        """,
    )
    fun observeAlbumsOfArtist(artistId: Long): Flow<List<AlbumRow>>

    @Query(
        """
        ${Sql.ALBUM_SELECT}
        WHERE ${Sql.ALBUM_HAS_VISIBLE_TRACKS}
          AND EXISTS (SELECT 1 FROM favorites f WHERE f.type = 'ALBUM' AND f.targetId = al.id)
        ORDER BY (SELECT f.addedAt FROM favorites f WHERE f.type = 'ALBUM' AND f.targetId = al.id) DESC
        """,
    )
    fun observeFavoriteAlbums(): Flow<List<AlbumRow>>

    @Query(
        """
        ${Sql.ALBUM_SELECT}
        WHERE ${Sql.ALBUM_HAS_VISIBLE_TRACKS} AND (al.albumKey LIKE '%' || :pattern || '%' ESCAPE '\')
        ORDER BY al.title COLLATE NOCASE LIMIT :limit
        """,
    )
    suspend fun search(pattern: String, limit: Int): List<AlbumRow>

    @Query("SELECT albumId FROM library_order ORDER BY position")
    suspend fun orderedAlbumIds(): List<Long>

    @Query("SELECT position FROM library_order WHERE albumId = :albumId")
    suspend fun positionOf(albumId: Long): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrder(rows: List<LibraryOrderEntity>)

    @Query("DELETE FROM library_order")
    suspend fun clearOrder()

    @Query("SELECT albumId, position FROM library_order")
    suspend fun allOrder(): List<LibraryOrderEntity>
}

@Dao
interface TrackDao {
    @Query("SELECT id, mediaStoreId, dateModifiedSec, sizeBytes, isAvailable, fingerprint FROM tracks")
    suspend fun syncSnapshot(): List<TrackSyncRow>

    @Insert
    suspend fun insert(track: TrackEntity): Long

    @Update
    suspend fun update(track: TrackEntity)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: Long): TrackEntity?

    @Query("UPDATE tracks SET isAvailable = 0 WHERE id IN (:ids)")
    suspend fun markUnavailable(ids: List<Long>)

    @Query("SELECT * FROM tracks WHERE fingerprint = :fingerprint ORDER BY id")
    suspend fun findByFingerprint(fingerprint: String): List<TrackEntity>

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE tracks SET lastPlayedAt = :at WHERE id = :trackId")
    suspend fun setLastPlayed(trackId: Long, at: Long)

    @Query("UPDATE tracks SET lastPlayedAt = 0")
    suspend fun clearLastPlayed()

    @Query("SELECT uri FROM tracks WHERE albumId = :albumId AND isAvailable = 1 ORDER BY discNumber, trackNumber LIMIT 1")
    suspend fun firstUriOfAlbum(albumId: Long): String?

    @Query("SELECT COUNT(*) FROM tracks WHERE isAvailable = 1")
    fun observeAvailableCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks WHERE isAvailable = 1")
    suspend fun availableCount(): Int

    @Query("${Sql.TRACK_SELECT} WHERE t.id IN (:ids) AND t.isAvailable = 1")
    suspend fun rowsByIds(ids: List<Long>): List<TrackRow>

    @Query("SELECT id, fingerprint FROM tracks ORDER BY isAvailable DESC, id")
    suspend fun allFingerprints(): List<IdFingerprint>

    @Query("SELECT t.id FROM tracks t WHERE t.albumId = :albumId AND ${Sql.VISIBLE} ORDER BY t.discNumber, t.trackNumber, t.title COLLATE NOCASE")
    suspend fun albumTrackIds(albumId: Long): List<Long>

    @Query("${Sql.TRACK_SELECT} WHERE t.id = :id")
    fun observeRow(id: Long): Flow<TrackRow?>

    @Query("${Sql.TRACK_SELECT} WHERE t.albumId = :albumId AND ${Sql.VISIBLE}")
    fun observeAlbumTracks(albumId: Long): Flow<List<TrackRow>>

    @Query("${Sql.TRACK_SELECT} WHERE t.artistId = :artistId AND ${Sql.VISIBLE}")
    fun observeArtistTracks(artistId: Long): Flow<List<TrackRow>>

    @Query(
        """
        ${Sql.TRACK_SELECT}
        WHERE ${Sql.VISIBLE}
          AND EXISTS (SELECT 1 FROM favorites f WHERE f.type = 'TRACK' AND f.targetId = t.id)
        ORDER BY (SELECT f.addedAt FROM favorites f WHERE f.type = 'TRACK' AND f.targetId = t.id) DESC
        """,
    )
    fun observeFavoriteTracks(): Flow<List<TrackRow>>

    @Query("${Sql.TRACK_SELECT} WHERE ${Sql.VISIBLE} AND t.lastPlayedAt > 0 ORDER BY t.lastPlayedAt DESC LIMIT :limit")
    fun observeRecentlyPlayed(limit: Int): Flow<List<TrackRow>>

    @Query("${Sql.TRACK_SELECT} WHERE ${Sql.VISIBLE} ORDER BY t.dateAddedSec DESC, t.id DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<TrackRow>>

    @Query(
        """
        ${Sql.TRACK_SELECT}
        WHERE EXISTS (SELECT 1 FROM hidden_tracks h WHERE h.trackId = t.id) AND t.isAvailable = 1
        ORDER BY t.title COLLATE NOCASE
        """,
    )
    fun observeHidden(): Flow<List<TrackRow>>

    @Query("${Sql.TRACK_SELECT} WHERE t.isAvailable = 0 ORDER BY t.title COLLATE NOCASE")
    fun observeMissing(): Flow<List<TrackRow>>

    @Query(
        """
        ${Sql.TRACK_SELECT}
        WHERE t.isAvailable = 1 AND t.fingerprint IN (
            SELECT fingerprint FROM tracks WHERE isAvailable = 1 GROUP BY fingerprint HAVING COUNT(*) > 1
        )
        ORDER BY t.fingerprint, t.id
        """,
    )
    fun observeDuplicates(): Flow<List<TrackRow>>

    @Query(
        """
        ${Sql.TRACK_SELECT}
        WHERE ${Sql.VISIBLE} AND t.searchKey LIKE '%' || :pattern || '%' ESCAPE '\'
        ORDER BY t.title COLLATE NOCASE LIMIT :limit
        """,
    )
    suspend fun search(pattern: String, limit: Int): List<TrackRow>
}

@Dao
interface HiddenDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun hide(rows: List<HiddenTrackEntity>)

    @Query("DELETE FROM hidden_tracks WHERE trackId IN (:ids)")
    suspend fun unhide(ids: List<Long>)

    @Query("SELECT trackId, hiddenAt FROM hidden_tracks")
    suspend fun all(): List<HiddenTrackEntity>

    @Query("DELETE FROM hidden_tracks")
    suspend fun clear()

    @Delete
    suspend fun delete(row: HiddenTrackEntity)
}
