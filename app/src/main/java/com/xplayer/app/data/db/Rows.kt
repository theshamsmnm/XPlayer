package com.xplayer.app.data.db

/** Shared SQL fragments (compile-time constants so they can be used inside @Query). */
object Sql {
    const val TRACK_COLUMNS = """
        t.id AS id, t.title AS title, t.artistName AS artist, t.albumId AS albumId, a.title AS albumTitle,
        t.durationMs AS durationMs, t.uri AS uri, t.trackNumber AS trackNumber, t.discNumber AS discNumber,
        t.year AS year, t.dateAddedSec AS dateAddedSec, t.dateModifiedSec AS dateModifiedSec,
        t.lastPlayedAt AS lastPlayedAt,
        EXISTS(SELECT 1 FROM favorites f WHERE f.type = 'TRACK' AND f.targetId = t.id) AS isFavorite,
        COALESCE(
            (SELECT c.version FROM custom_artwork c WHERE c.ownerType = 'TRACK' AND c.ownerId = t.id),
            (SELECT c.version FROM custom_artwork c WHERE c.ownerType = 'ALBUM' AND c.ownerId = t.albumId),
            0
        ) AS artVersion
    """
    const val TRACK_FROM = "FROM tracks t JOIN albums a ON a.id = t.albumId"

    /** A track is visible in the library when available and not hidden. */
    const val VISIBLE =
        "t.isAvailable = 1 AND NOT EXISTS (SELECT 1 FROM hidden_tracks h WHERE h.trackId = t.id)"

    const val TRACK_SELECT = "SELECT $TRACK_COLUMNS $TRACK_FROM"

    const val ALBUM_COLUMNS = """
        al.id AS id, al.title AS title, al.artistName AS artist, al.year AS year,
        al.dateAddedSec AS dateAddedSec, al.lastPlayedAt AS lastPlayedAt,
        (SELECT COUNT(*) FROM tracks t WHERE t.albumId = al.id AND $VISIBLE) AS trackCount,
        COALESCE(lo.position, 2147483647) AS orderPosition,
        EXISTS(SELECT 1 FROM favorites f WHERE f.type = 'ALBUM' AND f.targetId = al.id) AS isFavorite,
        COALESCE((SELECT c.version FROM custom_artwork c WHERE c.ownerType = 'ALBUM' AND c.ownerId = al.id), 0)
            AS artVersion,
        COALESCE((SELECT MAX(t.dateModifiedSec) FROM tracks t WHERE t.albumId = al.id), 0) AS sourceVersion
    """
    const val ALBUM_FROM = "FROM albums al LEFT JOIN library_order lo ON lo.albumId = al.id"
    const val ALBUM_HAS_VISIBLE_TRACKS =
        "EXISTS (SELECT 1 FROM tracks t WHERE t.albumId = al.id AND $VISIBLE)"
    const val ALBUM_SELECT = "SELECT $ALBUM_COLUMNS $ALBUM_FROM"
}

data class TrackRow(
    val id: Long,
    val title: String,
    val artist: String,
    val albumId: Long,
    val albumTitle: String,
    val durationMs: Long,
    val uri: String,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val lastPlayedAt: Long,
    val isFavorite: Boolean,
    val artVersion: Long,
)

data class AlbumRow(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val dateAddedSec: Long,
    val lastPlayedAt: Long,
    val trackCount: Int,
    val orderPosition: Int,
    val isFavorite: Boolean,
    val artVersion: Long,
    val sourceVersion: Long,
)

data class ArtistRow(val id: Long, val name: String, val albumCount: Int, val trackCount: Int)

data class CollectionRow(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val trackCount: Int,
    val orderPosition: Int,
    val artVersion: Long,
)

data class CollectionTrackRow(
    val id: Long,
    val title: String,
    val artist: String,
    val albumId: Long,
    val albumTitle: String,
    val durationMs: Long,
    val uri: String,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val lastPlayedAt: Long,
    val isFavorite: Boolean,
    val artVersion: Long,
    val position: Int,
    val addedAt: Long,
)

data class HistoryRow(
    val historyId: Long,
    val playedAt: Long,
    val id: Long,
    val title: String,
    val artist: String,
    val albumId: Long,
    val albumTitle: String,
    val durationMs: Long,
    val uri: String,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val lastPlayedAt: Long,
    val isFavorite: Boolean,
    val artVersion: Long,
)

/** Lightweight projection used by the synchroniser to diff MediaStore against the database. */
data class TrackSyncRow(
    val id: Long,
    val mediaStoreId: Long?,
    val dateModifiedSec: Long,
    val sizeBytes: Long,
    val isAvailable: Boolean,
    val fingerprint: String,
)

data class QueueRow(val position: Int, val trackId: Long)
