package com.xplayer.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Database ownership rules
 * ------------------------
 *  - Scanner-owned tables:  artists, albums, tracks   (the synchroniser may rewrite these)
 *  - User-owned tables:     everything else           (the synchroniser NEVER writes to them)
 *
 * Track/album rows are never deleted by a rescan. A track whose file disappears is flagged
 * `isAvailable = 0`, so favorites, collections, custom artwork and ordering survive moves,
 * temporary unmounting of storage, and re-indexing.
 */

@Entity(
    tableName = "artists",
    indices = [Index(value = ["normalizedName"], unique = true)],
)
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Display name; empty string means "unknown artist" (localised in the UI). */
    val name: String,
    val normalizedName: String,
)

@Entity(
    tableName = "albums",
    indices = [Index(value = ["albumKey"], unique = true), Index("artistId")],
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable identity: normalised title + album artist (see TextKeys.albumKey). */
    val albumKey: String,
    val title: String,
    val artistId: Long?,
    val artistName: String,
    val year: Int = 0,
    val dateAddedSec: Long = 0,
    /** Epoch millis of the last playback of any track of this album (0 = never). */
    val lastPlayedAt: Long = 0,
)

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["mediaStoreId"], unique = true),
        Index("fingerprint"),
        Index("albumId"),
        Index("artistId"),
        Index("isAvailable"),
        Index("dateAddedSec"),
        Index("lastPlayedAt"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** MediaStore `_ID`, or null for a track that was manually relinked to a document URI. */
    val mediaStoreId: Long?,
    /** content:// URI used for playback. */
    val uri: String,
    val title: String,
    val artistId: Long?,
    val artistName: String,
    val albumId: Long,
    val trackNumber: Int = 0,
    val discNumber: Int = 1,
    val durationMs: Long = 0,
    val year: Int = 0,
    val dateAddedSec: Long = 0,
    val dateModifiedSec: Long = 0,
    val sizeBytes: Long = 0,
    val mimeType: String? = null,
    val displayName: String? = null,
    val relativePath: String? = null,
    /** Location-independent identity used for relinking and backup resolution. */
    val fingerprint: String,
    /** Lower-cased "title artist album" used for fast LIKE searches. */
    val searchKey: String,
    val isAvailable: Boolean = true,
    val lastPlayedAt: Long = 0,
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Position of a collection in the user's list of collections. */
@Entity(
    tableName = "collection_order",
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CollectionOrderEntity(
    @PrimaryKey val collectionId: Long,
    val position: Int,
)

@Entity(
    tableName = "collection_tracks",
    primaryKeys = ["collectionId", "trackId"],
    indices = [Index("trackId"), Index(value = ["collectionId", "position"])],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CollectionTrackCrossRef(
    val collectionId: Long,
    val trackId: Long,
    val position: Int,
    val addedAt: Long,
)

/** Favorite flag for a track (`TRACK`) or an album (`ALBUM`). */
@Entity(tableName = "favorites", primaryKeys = ["type", "targetId"], indices = [Index("addedAt")])
data class FavoriteEntity(
    val type: String,
    val targetId: Long,
    val addedAt: Long,
) {
    companion object {
        const val TRACK = "TRACK"
        const val ALBUM = "ALBUM"
    }
}

@Entity(
    tableName = "playback_history",
    indices = [Index("trackId"), Index("playedAt")],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val playedAt: Long,
)

/**
 * Custom artwork for a track, album or collection. Only *file paths* are stored here (never bitmaps):
 * `fullPath` is a <=1024px JPEG, `thumbPath` a 256px JPEG, both inside the app's private storage.
 * [version] is bumped on every change and participates in the image cache key.
 */
@Entity(tableName = "custom_artwork", primaryKeys = ["ownerType", "ownerId"])
data class CustomArtworkEntity(
    val ownerType: String,
    val ownerId: Long,
    val fullPath: String,
    val thumbPath: String,
    val version: Long,
    val updatedAt: Long,
)

/** Custom position of an album in the main library grid. */
@Entity(
    tableName = "library_order",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LibraryOrderEntity(
    @PrimaryKey val albumId: Long,
    val position: Int,
)

/** Display, sorting and lock settings of one scope ("library", "albums", "collection:<id>" ...). */
@Entity(tableName = "layout_preferences")
data class LayoutPreferenceEntity(
    @PrimaryKey val scopeKey: String,
    val columns: Int,
    val artworkScale: Float,
    val spacingDp: Int,
    val showTitles: Boolean,
    val showArtists: Boolean,
    val sortKey: String,
    val ascending: Boolean,
    val locked: Boolean,
    val viewMode: String,
)

@Entity(
    tableName = "hidden_tracks",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HiddenTrackEntity(
    @PrimaryKey val trackId: Long,
    val hiddenAt: Long,
)

/** Persisted playback queue (restored on the next start). Order = [position]. */
@Entity(tableName = "queue_items")
data class QueueItemEntity(
    @PrimaryKey val position: Int,
    val trackId: Long,
)
