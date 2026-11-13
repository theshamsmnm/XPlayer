package com.xplayer.app.data.repo

import com.xplayer.app.data.db.AlbumRow
import com.xplayer.app.data.db.CollectionRow
import com.xplayer.app.data.db.CollectionTrackRow
import com.xplayer.app.data.db.HistoryRow
import com.xplayer.app.data.db.LayoutPreferenceEntity
import com.xplayer.app.data.db.TrackRow
import com.xplayer.app.domain.model.AlbumItem
import com.xplayer.app.domain.model.ArtKind
import com.xplayer.app.domain.model.ArtworkRef
import com.xplayer.app.domain.model.CollectionItem
import com.xplayer.app.domain.model.CollectionTrack
import com.xplayer.app.domain.model.LayoutPrefs
import com.xplayer.app.domain.model.PlayHistoryEntry
import com.xplayer.app.domain.model.SortKey
import com.xplayer.app.domain.model.TrackItem
import com.xplayer.app.domain.model.ViewMode

fun TrackRow.toItem() = TrackItem(
    id = id, title = title, artist = artist, albumId = albumId, albumTitle = albumTitle,
    durationMs = durationMs, uri = uri, trackNumber = trackNumber, discNumber = discNumber, year = year,
    dateAddedSec = dateAddedSec, lastPlayedAt = lastPlayedAt, isFavorite = isFavorite,
    art = ArtworkRef(ArtKind.TRACK, id, artVersion * 31 + dateModifiedSec, albumTitle.ifBlank { title }),
)

fun CollectionTrackRow.toItem() = CollectionTrack(
    track = TrackItem(
        id = id, title = title, artist = artist, albumId = albumId, albumTitle = albumTitle,
        durationMs = durationMs, uri = uri, trackNumber = trackNumber, discNumber = discNumber, year = year,
        dateAddedSec = dateAddedSec, lastPlayedAt = lastPlayedAt, isFavorite = isFavorite,
        art = ArtworkRef(ArtKind.TRACK, id, artVersion * 31 + dateModifiedSec, albumTitle.ifBlank { title }),
    ),
    position = position,
    addedAt = addedAt,
)

fun HistoryRow.toItem() = PlayHistoryEntry(
    historyId = historyId,
    playedAt = playedAt,
    track = TrackItem(
        id = id, title = title, artist = artist, albumId = albumId, albumTitle = albumTitle,
        durationMs = durationMs, uri = uri, trackNumber = trackNumber, discNumber = discNumber, year = year,
        dateAddedSec = dateAddedSec, lastPlayedAt = lastPlayedAt, isFavorite = isFavorite,
        art = ArtworkRef(ArtKind.TRACK, id, artVersion * 31 + dateModifiedSec, albumTitle.ifBlank { title }),
    ),
)

fun AlbumRow.toItem() = AlbumItem(
    id = id, title = title, artist = artist, year = year, trackCount = trackCount,
    dateAddedSec = dateAddedSec, lastPlayedAt = lastPlayedAt, orderPosition = orderPosition,
    isFavorite = isFavorite,
    art = ArtworkRef(ArtKind.ALBUM, id, artVersion * 31 + sourceVersion, title),
)

fun CollectionRow.toItem() = CollectionItem(
    id = id, name = name, trackCount = trackCount, orderPosition = orderPosition, createdAt = createdAt,
    art = ArtworkRef(ArtKind.COLLECTION, id, artVersion, name),
)

fun LayoutPreferenceEntity.toModel() = LayoutPrefs(
    scopeKey = scopeKey,
    columns = columns.coerceIn(2, 4),
    artworkScale = artworkScale.coerceIn(LayoutPrefs.MIN_ARTWORK_SCALE, 1f),
    spacingDp = spacingDp.coerceIn(0, 24),
    showTitles = showTitles,
    showArtists = showArtists,
    sortKey = runCatching { SortKey.valueOf(sortKey) }.getOrDefault(SortKey.CUSTOM),
    ascending = ascending,
    locked = locked,
    viewMode = runCatching { ViewMode.valueOf(viewMode) }.getOrDefault(ViewMode.GRID),
)

fun LayoutPrefs.toEntity() = LayoutPreferenceEntity(
    scopeKey = scopeKey, columns = columns, artworkScale = artworkScale, spacingDp = spacingDp,
    showTitles = showTitles, showArtists = showArtists, sortKey = sortKey.name, ascending = ascending,
    locked = locked, viewMode = viewMode.name,
)
