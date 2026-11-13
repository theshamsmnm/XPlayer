package com.xplayer.app.data.repo

import androidx.room.withTransaction
import com.xplayer.app.data.artwork.ArtworkStore
import com.xplayer.app.data.db.CollectionEntity
import com.xplayer.app.data.db.CollectionOrderEntity
import com.xplayer.app.data.db.CollectionTrackCrossRef
import com.xplayer.app.data.db.XPlayerDatabase
import com.xplayer.app.domain.model.ArtKind
import com.xplayer.app.domain.model.CollectionItem
import com.xplayer.app.domain.model.CollectionTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Collections (playlists). Deleting a collection or removing tracks from it only touches
 * `collections` / `collection_tracks`: tracks, albums and the audio files are never affected.
 */
class CollectionRepository(
    private val db: XPlayerDatabase,
    private val artwork: ArtworkStore,
) {
    private val dao = db.collectionDao()
    private val layoutDao = db.layoutDao()

    fun observeCollections(): Flow<List<CollectionItem>> = dao.observeCollections().map { r -> r.map { it.toItem() } }
    fun observeCollection(id: Long): Flow<CollectionItem?> = dao.observeCollection(id).map { it?.toItem() }
    fun observeTracks(id: Long): Flow<List<CollectionTrack>> = dao.observeTracks(id).map { r -> r.map { it.toItem() } }

    suspend fun create(name: String, initialTrackIds: List<Long> = emptyList()): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val id = dao.insert(CollectionEntity(name = name.trim().ifBlank { DEFAULT_NAME }, createdAt = now, updatedAt = now))
        dao.upsertOrder(listOf(CollectionOrderEntity(id, dao.maxOrderPosition() + 1)))
        if (initialTrackIds.isNotEmpty()) addTracksInternal(id, initialTrackIds)
        id
    }

    suspend fun rename(id: Long, name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) dao.rename(id, clean, System.currentTimeMillis())
    }

    /** Removes the collection, its order/layout rows and its custom artwork. Audio files stay untouched. */
    suspend fun delete(id: Long) {
        db.withTransaction {
            dao.delete(id) // cascades to collection_order and collection_tracks
            layoutDao.delete(com.xplayer.app.domain.model.LayoutPrefs.collectionScope(id))
        }
        artwork.restoreOriginal(ArtKind.COLLECTION, id)
    }

    suspend fun addTracks(collectionId: Long, trackIds: List<Long>): Int = db.withTransaction {
        addTracksInternal(collectionId, trackIds)
    }

    /** Adds missing tracks at the end; tracks already in the collection keep their position. */
    private suspend fun addTracksInternal(collectionId: Long, trackIds: List<Long>): Int {
        val existing = dao.trackIds(collectionId).toSet()
        val fresh = trackIds.distinct().filter { it !in existing }
        if (fresh.isEmpty()) return 0
        val start = dao.maxTrackPosition(collectionId) + 1
        val now = System.currentTimeMillis()
        dao.addTracks(fresh.mapIndexed { i, id -> CollectionTrackCrossRef(collectionId, id, start + i, now + i) })
        dao.touch(collectionId, now)
        return fresh.size
    }

    suspend fun removeTracks(collectionId: Long, trackIds: List<Long>) = db.withTransaction {
        dao.removeTracks(collectionId, trackIds)
        // Close the gaps so positions stay dense.
        val remaining = dao.crossRefs(collectionId).sortedBy { it.position }
        dao.upsertTracks(remaining.mapIndexed { i, r -> r.copy(position = i) })
        dao.touch(collectionId, System.currentTimeMillis())
    }

    /** Batch removal of the same tracks from several collections is just a loop in one transaction. */
    suspend fun removeTracksFromAll(trackIds: List<Long>, collectionIds: List<Long>) = db.withTransaction {
        collectionIds.forEach { removeTracks(it, trackIds) }
    }
}

private const val DEFAULT_NAME = "New collection"
