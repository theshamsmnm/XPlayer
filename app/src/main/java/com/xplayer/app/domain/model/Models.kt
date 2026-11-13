package com.xplayer.app.domain.model

import androidx.compose.runtime.Immutable

enum class ArtKind { TRACK, ALBUM, COLLECTION }

/**
 * Identifies the artwork of an entity. [version] changes whenever custom artwork or the source file
 * changes, which invalidates the image cache. [label] is only used to draw the generated fallback.
 */
@Immutable
data class ArtworkRef(val kind: ArtKind, val id: Long, val version: Long, val label: String)

@Immutable
data class TrackItem(
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
    val lastPlayedAt: Long,
    val isFavorite: Boolean,
    val art: ArtworkRef,
)

@Immutable
data class AlbumItem(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val trackCount: Int,
    val dateAddedSec: Long,
    val lastPlayedAt: Long,
    val orderPosition: Int,
    val isFavorite: Boolean,
    val art: ArtworkRef,
)

@Immutable
data class ArtistItem(val id: Long, val name: String, val albumCount: Int, val trackCount: Int)

@Immutable
data class CollectionItem(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val orderPosition: Int,
    val createdAt: Long,
    val art: ArtworkRef,
)

/** A track inside a collection, with its position in the collection's custom order. */
@Immutable
data class CollectionTrack(val track: TrackItem, val position: Int, val addedAt: Long)

enum class SortKey {
    CUSTOM, ALPHABETICAL, TITLE, ARTIST, RECENTLY_ADDED, RECENTLY_PLAYED, YEAR, TRACK_COUNT
}

enum class ViewMode { GRID, LIST }

enum class ThemeMode { AMOLED, DARK, LIGHT }

/** Display + order + lock settings of one library/collection ("scope"). */
@Immutable
data class LayoutPrefs(
    val scopeKey: String,
    val columns: Int = DEFAULT_COLUMNS,
    val artworkScale: Float = 1f,
    val spacingDp: Int = DEFAULT_SPACING_DP,
    val showTitles: Boolean = true,
    val showArtists: Boolean = true,
    val sortKey: SortKey = SortKey.CUSTOM,
    val ascending: Boolean = true,
    val locked: Boolean = false,
    val viewMode: ViewMode = ViewMode.GRID,
) {
    /** Drag-and-drop needs: unlocked, custom order displayed, grid view. */
    val canReorder: Boolean get() = !locked && sortKey == SortKey.CUSTOM && viewMode == ViewMode.GRID

    companion object {
        const val DEFAULT_COLUMNS = 3
        const val DEFAULT_SPACING_DP = 8
        const val MIN_ARTWORK_SCALE = 0.6f
        const val SCOPE_LIBRARY = "library"
        const val SCOPE_ALBUMS = "albums"
        const val SCOPE_FAVORITES = "favorites"
        const val SCOPE_COLLECTIONS = "collections"
        fun collectionScope(collectionId: Long) = "collection:$collectionId"
    }
}

@Immutable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.AMOLED,
    val autoScan: Boolean = true,
    val resumePlayback: Boolean = true,
    val shuffle: Boolean = false,
    /** One of androidx.media3.common.Player.REPEAT_MODE_* (0 off, 1 one, 2 all). */
    val repeatMode: Int = 0,
)

/** Search results grouped by category. */
@Immutable
data class SearchResults(
    val tracks: List<TrackItem> = emptyList(),
    val albums: List<AlbumItem> = emptyList(),
    val artists: List<ArtistItem> = emptyList(),
    val collections: List<CollectionItem> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && collections.isEmpty()
}

data class PlayHistoryEntry(val historyId: Long, val playedAt: Long, val track: TrackItem)

/** Progress/outcome of a library synchronisation, observed by the UI. */
sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val processed: Int, val total: Int, val full: Boolean) : SyncState
    data class Done(val result: SyncResult) : SyncState
    data class Failed(val message: String?) : SyncState
}

data class SyncResult(
    val added: Int = 0,
    val updated: Int = 0,
    val markedMissing: Int = 0,
    val relinked: Int = 0,
    val totalAvailable: Int = 0,
)
