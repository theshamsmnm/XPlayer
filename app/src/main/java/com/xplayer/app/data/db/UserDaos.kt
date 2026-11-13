package com.xplayer.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

private const val COLLECTION_TRACK_COLUMNS = """
    t.id AS id, t.title AS title, t.artistName AS artist, t.albumId AS albumId, a.title AS albumTitle,
    t.durationMs AS durationMs, t.uri AS uri, t.trackNumber AS trackNumber, t.discNumber AS discNumber,
    t.year AS year, t.dateAddedSec AS dateAddedSec, t.dateModifiedSec AS dateModifiedSec,
    t.lastPlayedAt AS lastPlayedAt,
    EXISTS(SELECT 1 FROM favorites f WHERE f.type = 'TRACK' AND f.targetId = t.id) AS isFavorite,
    COALESCE(
        (SELECT c.version FROM custom_artwork c WHERE c.ownerType = 'TRACK' AND c.ownerId = t.id),
        (SELECT c.version FROM custom_artwork c WHERE c.ownerType = 'ALBUM' AND c.ownerId = t.albumId),
        0
    ) AS artVersion,
    ct.position AS position, ct.addedAt AS addedAt
"""

@Dao
interface CollectionDao {
    @Insert
    suspend fun insert(collection: CollectionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrder(rows: List<CollectionOrderEntity>)

    @Query("SELECT COALESCE(MAX(position), -1) FROM collection_order")
    suspend fun maxOrderPosition(): Int

    @Query("UPDATE collections SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE collections SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getById(id: Long): CollectionEntity?

    @Query("SELECT * FROM collections")
    suspend fun all(): List<CollectionEntity>

    @Query("SELECT * FROM collection_order")
    suspend fun allOrder(): List<CollectionOrderEntity>

    @Query("SELECT * FROM collection_tracks ORDER BY collectionId, position")
    suspend fun allCrossRefs(): List<CollectionTrackCrossRef>

    @Query("DELETE FROM collections")
    suspend fun deleteAll()

    @Query(
        """
        SELECT c.id AS id, c.name AS name, c.createdAt AS createdAt,
          (SELECT COUNT(*) FROM collection_tracks ct JOIN tracks t ON t.id = ct.trackId
             WHERE ct.collectionId = c.id AND t.isAvailable = 1) AS trackCount,
          COALESCE(co.position, 2147483647) AS orderPosition,
          COALESCE((SELECT x.version FROM custom_artwork x WHERE x.ownerType = 'COLLECTION' AND x.ownerId = c.id), 0)
            AS artVersion
        FROM collections c LEFT JOIN collection_order co ON co.collectionId = c.id
        ORDER BY orderPosition, c.id
        """,
    )
    fun observeCollections(): Flow<List<CollectionRow>>

    @Query(
        """
        SELECT c.id AS id, c.name AS name, c.createdAt AS createdAt,
          (SELECT COUNT(*) FROM collection_tracks ct JOIN tracks t ON t.id = ct.trackId
             WHERE ct.collectionId = c.id AND t.isAvailable = 1) AS trackCount,
          COALESCE(co.position, 2147483647) AS orderPosition,
          COALESCE((SELECT x.version FROM custom_artwork x WHERE x.ownerType = 'COLLECTION' AND x.ownerId = c.id), 0)
            AS artVersion
        FROM collections c LEFT JOIN collection_order co ON co.collectionId = c.id
        WHERE c.id = :id
        """,
    )
    fun observeCollection(id: Long): Flow<CollectionRow?>

    @Query(
        """
        SELECT c.id AS id, c.name AS name, c.createdAt AS createdAt,
          (SELECT COUNT(*) FROM collection_tracks ct JOIN tracks t ON t.id = ct.trackId
             WHERE ct.collectionId = c.id AND t.isAvailable = 1) AS trackCount,
          COALESCE(co.position, 2147483647) AS orderPosition,
          COALESCE((SELECT x.version FROM custom_artwork x WHERE x.ownerType = 'COLLECTION' AND x.ownerId = c.id), 0)
            AS artVersion
        FROM collections c LEFT JOIN collection_order co ON co.collectionId = c.id
        WHERE LOWER(c.name) LIKE '%' || :pattern || '%' ESCAPE '\'
        ORDER BY c.name COLLATE NOCASE LIMIT :limit
        """,
    )
    suspend fun search(pattern: String, limit: Int): List<CollectionRow>

    @Query(
        """
        SELECT $COLLECTION_TRACK_COLUMNS
        FROM collection_tracks ct
        JOIN tracks t ON t.id = ct.trackId
        JOIN albums a ON a.id = t.albumId
        WHERE ct.collectionId = :collectionId AND t.isAvailable = 1
        ORDER BY ct.position
        """,
    )
    fun observeTracks(collectionId: Long): Flow<List<CollectionTrackRow>>

    @Query("SELECT trackId FROM collection_tracks WHERE collectionId = :collectionId ORDER BY position")
    suspend fun trackIds(collectionId: Long): List<Long>

    @Query("SELECT COALESCE(MAX(position), -1) FROM collection_tracks WHERE collectionId = :collectionId")
    suspend fun maxTrackPosition(collectionId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTracks(rows: List<CollectionTrackCrossRef>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(rows: List<CollectionTrackCrossRef>)

    @Query("DELETE FROM collection_tracks WHERE collectionId = :collectionId AND trackId IN (:trackIds)")
    suspend fun removeTracks(collectionId: Long, trackIds: List<Long>)

    @Query("SELECT * FROM collection_tracks WHERE collectionId = :collectionId")
    suspend fun crossRefs(collectionId: Long): List<CollectionTrackCrossRef>

    @Query("DELETE FROM collection_tracks")
    suspend fun deleteAllCrossRefs()

    @Query("DELETE FROM collection_order")
    suspend fun deleteAllOrder()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllRaw(rows: List<CollectionEntity>)
}

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(rows: List<FavoriteEntity>)

    @Query("DELETE FROM favorites WHERE type = :type AND targetId IN (:ids)")
    suspend fun remove(type: String, ids: List<Long>)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE type = :type AND targetId = :id)")
    suspend fun isFavorite(type: String, id: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE type = 'TRACK' AND targetId = :id)")
    fun observeTrackFavorite(id: Long): Flow<Boolean>

    @Query("SELECT targetId FROM favorites WHERE type = :type AND targetId IN (:ids)")
    suspend fun favoritesAmong(type: String, ids: List<Long>): List<Long>

    @Query("SELECT * FROM favorites")
    suspend fun all(): List<FavoriteEntity>

    @Query("DELETE FROM favorites")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<FavoriteEntity>)
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: PlaybackHistoryEntity): Long

    @Query("DELETE FROM playback_history")
    suspend fun clear()

    /** Keeps the table bounded: removes everything but the newest [keep] entries. */
    @Query(
        """
        DELETE FROM playback_history WHERE id NOT IN (
            SELECT id FROM playback_history ORDER BY playedAt DESC, id DESC LIMIT :keep
        )
        """,
    )
    suspend fun trim(keep: Int)

    @Query(
        """
        SELECT h.id AS historyId, h.playedAt AS playedAt, ${Sql.TRACK_COLUMNS}
        FROM playback_history h
        JOIN tracks t ON t.id = h.trackId
        JOIN albums a ON a.id = t.albumId
        ORDER BY h.playedAt DESC, h.id DESC
        LIMIT :limit
        """,
    )
    fun observeHistory(limit: Int): Flow<List<HistoryRow>>

    @Query("SELECT h.playedAt AS playedAt, t.fingerprint AS fingerprint FROM playback_history h JOIN tracks t ON t.id = h.trackId ORDER BY h.playedAt DESC LIMIT :limit")
    suspend fun exportRows(limit: Int): List<HistoryExportRow>
}

data class HistoryExportRow(val playedAt: Long, val fingerprint: String)

@Dao
interface ArtworkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CustomArtworkEntity)

    @Query("SELECT * FROM custom_artwork WHERE ownerType = :type AND ownerId = :id")
    suspend fun get(type: String, id: Long): CustomArtworkEntity?

    @Query("DELETE FROM custom_artwork WHERE ownerType = :type AND ownerId = :id")
    suspend fun delete(type: String, id: Long)

    @Query("SELECT * FROM custom_artwork")
    suspend fun all(): List<CustomArtworkEntity>

    @Query("SELECT * FROM custom_artwork")
    fun observeAll(): Flow<List<CustomArtworkEntity>>

    @Query("DELETE FROM custom_artwork")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<CustomArtworkEntity>)
}

@Dao
interface LayoutDao {
    @Query("SELECT * FROM layout_preferences WHERE scopeKey = :scope")
    suspend fun get(scope: String): LayoutPreferenceEntity?

    @Query("SELECT * FROM layout_preferences WHERE scopeKey = :scope")
    fun observe(scope: String): Flow<LayoutPreferenceEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: LayoutPreferenceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<LayoutPreferenceEntity>)

    @Query("SELECT * FROM layout_preferences")
    suspend fun all(): List<LayoutPreferenceEntity>

    @Query("DELETE FROM layout_preferences")
    suspend fun clear()

    @Query("DELETE FROM layout_preferences WHERE scopeKey = :scope")
    suspend fun delete(scope: String)
}

@Dao
interface QueueDao {
    @Query("SELECT position, trackId FROM queue_items ORDER BY position")
    suspend fun all(): List<QueueRow>

    @Query("DELETE FROM queue_items")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<QueueItemEntity>)
}
