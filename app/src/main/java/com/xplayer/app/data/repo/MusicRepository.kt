package com.xplayer.app.data.repo

import androidx.room.withTransaction
import com.xplayer.app.core.TextKeys
import com.xplayer.app.data.db.FavoriteEntity
import com.xplayer.app.data.db.HiddenTrackEntity
import com.xplayer.app.data.db.PlaybackHistoryEntity
import com.xplayer.app.data.db.XPlayerDatabase
import com.xplayer.app.domain.model.AlbumItem
import com.xplayer.app.domain.model.ArtistItem
import com.xplayer.app.domain.model.PlayHistoryEntry
import com.xplayer.app.domain.model.SearchResults
import com.xplayer.app.domain.model.TrackItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Read access to the indexed library plus favorites, history and hidden-track management. */
class MusicRepository(private val db: XPlayerDatabase) {

    private val tracks = db.trackDao()
    private val albums = db.albumDao()

    // ---- Library reads ---------------------------------------------------------------------

    fun observeAlbums(): Flow<List<AlbumItem>> = albums.observeAlbums().map { rows -> rows.map { it.toItem() } }
    fun observeAlbum(id: Long): Flow<AlbumItem?> = albums.observeAlbum(id).map { it?.toItem() }
    fun observeFavoriteAlbums(): Flow<List<AlbumItem>> =
        albums.observeFavoriteAlbums().map { rows -> rows.map { it.toItem() } }

    fun observeArtists(): Flow<List<ArtistItem>> = db.artistDao().observeArtists().map { rows ->
        rows.map { ArtistItem(it.id, it.name, it.albumCount, it.trackCount) }
    }

    fun observeArtist(id: Long): Flow<ArtistItem?> = db.artistDao().observeArtist(id).map { r ->
        r?.let { ArtistItem(it.id, it.name, it.albumCount, it.trackCount) }
    }

    fun observeAlbumsOfArtist(artistId: Long): Flow<List<AlbumItem>> =
        albums.observeAlbumsOfArtist(artistId).map { rows -> rows.map { it.toItem() } }

    fun observeAlbumTracks(albumId: Long): Flow<List<TrackItem>> =
        tracks.observeAlbumTracks(albumId).map { rows ->
            rows.map { it.toItem() }.sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.title.lowercase() }))
        }

    fun observeArtistTracks(artistId: Long): Flow<List<TrackItem>> =
        tracks.observeArtistTracks(artistId).map { rows ->
            rows.map { it.toItem() }.sortedWith(compareBy({ it.albumTitle.lowercase() }, { it.discNumber }, { it.trackNumber }))
        }

    fun observeTrack(id: Long): Flow<TrackItem?> = tracks.observeRow(id).map { it?.toItem() }
    fun observeFavoriteTracks(): Flow<List<TrackItem>> = tracks.observeFavoriteTracks().map { r -> r.map { it.toItem() } }
    fun observeRecentlyPlayed(limit: Int = 30): Flow<List<TrackItem>> =
        tracks.observeRecentlyPlayed(limit).map { r -> r.map { it.toItem() } }

    fun observeRecentlyAdded(limit: Int = 30): Flow<List<TrackItem>> =
        tracks.observeRecentlyAdded(limit).map { r -> r.map { it.toItem() } }

    fun observeHidden(): Flow<List<TrackItem>> = tracks.observeHidden().map { r -> r.map { it.toItem() } }
    fun observeMissing(): Flow<List<TrackItem>> = tracks.observeMissing().map { r -> r.map { it.toItem() } }
    fun observeDuplicates(): Flow<List<TrackItem>> = tracks.observeDuplicates().map { r -> r.map { it.toItem() } }
    fun observeTrackCount(): Flow<Int> = tracks.observeAvailableCount()

    /** Resolves ids to playable items, preserving the order of [ids]. Unavailable tracks are dropped. */
    suspend fun tracksByIds(ids: List<Long>): List<TrackItem> {
        if (ids.isEmpty()) return emptyList()
        val byId = ids.distinct().chunked(500).flatMap { tracks.rowsByIds(it) }.associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toItem() }
    }

    suspend fun trackIdsOfAlbum(albumId: Long): List<Long> =
        tracks.albumTrackIds(albumId)

    // ---- Search --------------------------------------------------------------------------------

    suspend fun search(query: String, limitPerGroup: Int = 25): SearchResults {
        val normalized = TextKeys.normalize(query)
        if (normalized.isEmpty()) return SearchResults()
        val pattern = TextKeys.escapeLike(normalized)
        val artistMatches = db.artistDao().search(pattern, limitPerGroup)
        return SearchResults(
            tracks = tracks.search(pattern, limitPerGroup).map { it.toItem() },
            albums = albums.search(pattern, limitPerGroup).map { it.toItem() },
            artists = artistMatches.map { ArtistItem(it.id, it.name, it.albumCount, it.trackCount) },
            collections = db.collectionDao().search(pattern, limitPerGroup).map { it.toItem() },
        )
    }

    // ---- Favorites -----------------------------------------------------------------------------

    suspend fun setTrackFavorite(trackId: Long, favorite: Boolean) = setTracksFavorite(listOf(trackId), favorite)

    suspend fun setTracksFavorite(trackIds: List<Long>, favorite: Boolean) = db.withTransaction {
        if (favorite) {
            val now = System.currentTimeMillis()
            db.favoriteDao().add(trackIds.map { FavoriteEntity(FavoriteEntity.TRACK, it, now) })
        } else {
            db.favoriteDao().remove(FavoriteEntity.TRACK, trackIds)
        }
    }

    suspend fun setAlbumFavorite(albumId: Long, favorite: Boolean) = db.withTransaction {
        if (favorite) {
            db.favoriteDao().add(listOf(FavoriteEntity(FavoriteEntity.ALBUM, albumId, System.currentTimeMillis())))
        } else {
            db.favoriteDao().remove(FavoriteEntity.ALBUM, listOf(albumId))
        }
    }

    suspend fun toggleTrackFavorite(trackId: Long): Boolean = db.withTransaction {
        val now = db.favoriteDao().isFavorite(FavoriteEntity.TRACK, trackId)
        setTrackFavorite(trackId, !now)
        !now
    }

    /**
     * Batch toggle: if every selected track is already a favorite they are all un-favorited, otherwise
     * all become favorites. Returns the new state.
     */
    suspend fun toggleTracksFavorite(trackIds: List<Long>): Boolean = db.withTransaction {
        val already = db.favoriteDao().favoritesAmong(FavoriteEntity.TRACK, trackIds).toSet()
        val makeFavorite = !trackIds.all { it in already }
        setTracksFavorite(trackIds, makeFavorite)
        makeFavorite
    }

    // ---- History -------------------------------------------------------------------------------

    fun observeHistory(limit: Int = 200): Flow<List<PlayHistoryEntry>> =
        db.historyDao().observeHistory(limit).map { rows -> rows.map { it.toItem() } }

    /** Records a play: history row + "last played" on the track and its album. */
    suspend fun recordPlay(trackId: Long, at: Long = System.currentTimeMillis()) = db.withTransaction {
        val track = tracks.getById(trackId) ?: return@withTransaction
        db.historyDao().insert(PlaybackHistoryEntity(trackId = trackId, playedAt = at))
        db.historyDao().trim(HISTORY_LIMIT)
        tracks.setLastPlayed(trackId, at)
        albums.setLastPlayed(track.albumId, at)
    }

    /** Clears history and "recently played" sorting data. Favorites and collections are untouched. */
    suspend fun clearHistory() = db.withTransaction {
        db.historyDao().clear()
        tracks.clearLastPlayed()
        albums.clearLastPlayed()
    }

    // ---- Hiding --------------------------------------------------------------------------------

    suspend fun hideTracks(ids: List<Long>) {
        val now = System.currentTimeMillis()
        db.hiddenDao().hide(ids.map { HiddenTrackEntity(it, now) })
    }

    suspend fun unhideTracks(ids: List<Long>) = db.hiddenDao().unhide(ids)

    suspend fun getTrackEntityUri(id: Long): String? = tracks.getById(id)?.uri

    companion object {
        const val HISTORY_LIMIT = 1000
    }
}
