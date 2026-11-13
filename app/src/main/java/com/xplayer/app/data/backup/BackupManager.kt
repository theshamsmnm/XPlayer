package com.xplayer.app.data.backup

import androidx.room.withTransaction
import com.xplayer.app.core.AppDispatchers
import com.xplayer.app.data.artwork.ArtworkStore
import com.xplayer.app.data.db.CollectionEntity
import com.xplayer.app.data.db.CollectionOrderEntity
import com.xplayer.app.data.db.CollectionTrackCrossRef
import com.xplayer.app.data.db.CustomArtworkEntity
import com.xplayer.app.data.db.FavoriteEntity
import com.xplayer.app.data.db.HiddenTrackEntity
import com.xplayer.app.data.db.LayoutPreferenceEntity
import com.xplayer.app.data.db.LibraryOrderEntity
import com.xplayer.app.data.db.XPlayerDatabase
import com.xplayer.app.data.repo.SettingsRepository
import com.xplayer.app.domain.model.AppSettings
import com.xplayer.app.domain.model.ArtKind
import com.xplayer.app.domain.model.LayoutPrefs
import com.xplayer.app.domain.model.SortKey
import com.xplayer.app.domain.model.ThemeMode
import com.xplayer.app.domain.model.ViewMode
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Outcome of a restore, shown to the user. */
data class RestoreSummary(
    val collections: Int,
    val favorites: Int,
    val skippedTracks: Int,
    val artworkRestored: Int,
)

class BackupException(val kind: Kind, message: String? = null) : Exception(message) {
    enum class Kind { NOT_A_BACKUP, CORRUPT, TOO_NEW, TOO_OLD, EMPTY }
}

/**
 * Offline backup/restore of everything the *user* organised (never the audio files).
 *
 * Restore safety: the archive is fully parsed and validated into memory first; artwork files are staged;
 * then ONE database transaction replaces the user-owned tables. Any failure rolls the database back
 * (and staged files are deleted), so a failed restore never leaves partially modified data.
 */
class BackupManager(
    private val db: XPlayerDatabase,
    private val settings: SettingsRepository,
    private val artwork: ArtworkStore,
    private val dispatchers: AppDispatchers,
    private val appVersion: String,
) {
    private val json = BackupArchive.json

    // ---- Export ------------------------------------------------------------------------------------

    suspend fun export(out: OutputStream) = withContext(dispatchers.io) {
        val fingerprints = db.trackDao().allFingerprints().associate { it.id to it.fingerprint }
        val albumKeys = db.albumDao().idsByKey().associate { it.id to it.albumKey }
        val collectionDao = db.collectionDao()

        val crossRefs = collectionDao.allCrossRefs().groupBy { it.collectionId }
        val order = collectionDao.allOrder().associate { it.collectionId to it.position }
        val collections = collectionDao.all().map { c ->
            BackupCollection(
                originalId = c.id, name = c.name, createdAt = c.createdAt, position = order[c.id] ?: Int.MAX_VALUE,
                tracks = crossRefs[c.id].orEmpty().mapNotNull { r ->
                    fingerprints[r.trackId]?.let { BackupCollectionTrack(it, r.position, r.addedAt) }
                },
            )
        }

        val artworkRows = db.artworkDao().all()
        val artworkEntries = ArrayList<BackupArtwork>()
        val files = ArrayList<Pair<String, java.io.File>>()
        for ((index, row) in artworkRows.withIndex()) {
            val key = when (row.ownerType) {
                ArtKind.TRACK.name -> fingerprints[row.ownerId]
                ArtKind.ALBUM.name -> albumKeys[row.ownerId]
                ArtKind.COLLECTION.name -> row.ownerId.toString()
                else -> null
            } ?: continue
            val file = java.io.File(row.fullPath)
            if (!file.exists()) continue
            val name = "art_$index.jpg"
            artworkEntries += BackupArtwork(row.ownerType, key, name)
            files += name to file
        }

        val s = settings.current()
        val manifest = BackupManifest(
            createdAt = System.currentTimeMillis(),
            appVersion = appVersion,
            settings = BackupSettings(s.theme.name, s.autoScan, s.resumePlayback, s.shuffle, s.repeatMode),
            layouts = db.layoutDao().all().map {
                BackupLayout(
                    it.scopeKey, it.columns, it.artworkScale, it.spacingDp, it.showTitles, it.showArtists,
                    it.sortKey, it.ascending, it.locked, it.viewMode,
                )
            },
            collections = collections,
            favoriteTracks = db.favoriteDao().all().filter { it.type == FavoriteEntity.TRACK }
                .sortedBy { it.addedAt }.mapNotNull { fingerprints[it.targetId] },
            favoriteAlbums = db.favoriteDao().all().filter { it.type == FavoriteEntity.ALBUM }
                .sortedBy { it.addedAt }.mapNotNull { albumKeys[it.targetId] },
            albumOrder = db.albumDao().allOrder().sortedBy { it.position }.mapNotNull { albumKeys[it.albumId] },
            hiddenTracks = db.hiddenDao().all().mapNotNull { fingerprints[it.trackId] },
            artwork = artworkEntries,
        )

        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupManifest.MANIFEST_ENTRY))
            zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((name, file) in files) {
                zip.putNextEntry(ZipEntry(BackupManifest.ARTWORK_DIR + name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    // ---- Validation ----------------------------------------------------------------------------------

    /** Reads and validates an archive without touching any app data. Throws [BackupException]. */
    suspend fun parse(input: InputStream): ParsedBackup = withContext(dispatchers.io) { BackupArchive.parse(input) }

    // ---- Restore -----------------------------------------------------------------------------------

    /** Replaces all user organisation data with the backup. Atomic for the database. */
    suspend fun restore(parsed: ParsedBackup): RestoreSummary = withContext(dispatchers.io) {
        val m = parsed.manifest
        val trackIdByFingerprint = HashMap<String, Long>().also { map ->
            db.trackDao().allFingerprints().forEach { map.putIfAbsent(it.fingerprint, it.id) }
        }
        val albumIdByKey = db.albumDao().idsByKey().associate { it.albumKey to it.id }

        // Stage artwork files first (outside the transaction). Rows are inserted inside it.
        data class StagedArt(val type: ArtKind, val ownerKey: String)
        val staged = ArrayList<Pair<StagedArt, ByteArray>>()
        m.artwork.forEach { a ->
            val kind = runCatching { ArtKind.valueOf(a.ownerType) }.getOrNull() ?: return@forEach
            staged += StagedArt(kind, a.ownerKey) to parsed.artwork.getValue(a.file)
        }

        val createdArtwork = ArrayList<CustomArtworkEntity>()
        var replacedArtwork: List<CustomArtworkEntity> = emptyList()
        var skipped = 0
        var restoredArt = 0
        var favoriteCount = 0
        try {
            db.withTransaction {
                val collectionDao = db.collectionDao()

                // 1. wipe user-owned data (scanner-owned tables are untouched)
                val oldArtwork = db.artworkDao().all()
                collectionDao.deleteAll() // cascades to order + crossrefs
                db.favoriteDao().clear()
                db.hiddenDao().clear()
                db.albumDao().clearOrder()
                db.layoutDao().clear()
                db.artworkDao().clear()

                // 2. collections (new ids; remember the mapping for scopes and artwork)
                val newCollectionIds = HashMap<Long, Long>()
                for (c in m.collections.sortedBy { it.position }) {
                    val newId = collectionDao.insert(CollectionEntity(name = c.name, createdAt = c.createdAt, updatedAt = c.createdAt))
                    newCollectionIds[c.originalId] = newId
                }
                collectionDao.upsertOrder(
                    m.collections.sortedBy { it.position }.mapIndexed { i, c ->
                        CollectionOrderEntity(newCollectionIds.getValue(c.originalId), i)
                    },
                )
                for (c in m.collections) {
                    val newId = newCollectionIds.getValue(c.originalId)
                    val refs = c.tracks.sortedBy { it.position }.mapNotNull { t ->
                        val trackId = trackIdByFingerprint[t.fingerprint]
                        if (trackId == null) { skipped++; null } else Triple(trackId, t.addedAt, t)
                    }.distinctBy { it.first }
                    collectionDao.upsertTracks(
                        refs.mapIndexed { i, (trackId, addedAt, _) -> CollectionTrackCrossRef(newId, trackId, i, addedAt) },
                    )
                }

                // 3. favorites
                val now = System.currentTimeMillis()
                val trackFavs = m.favoriteTracks.mapIndexedNotNull { i, fp ->
                    trackIdByFingerprint[fp]?.let { FavoriteEntity(FavoriteEntity.TRACK, it, now + i) }
                }
                val albumFavs = m.favoriteAlbums.mapIndexedNotNull { i, key ->
                    albumIdByKey[key]?.let { FavoriteEntity(FavoriteEntity.ALBUM, it, now + i) }
                }
                skipped += (m.favoriteTracks.size - trackFavs.size)
                db.favoriteDao().upsertAll(trackFavs + albumFavs)
                favoriteCount = trackFavs.size + albumFavs.size

                // 4. album order, hidden
                db.albumDao().upsertOrder(
                    m.albumOrder.mapNotNull { albumIdByKey[it] }.distinct().mapIndexed { i, id -> LibraryOrderEntity(id, i) },
                )
                db.hiddenDao().hide(m.hiddenTracks.mapNotNull { trackIdByFingerprint[it] }.distinct().map { HiddenTrackEntity(it, now) })

                // 5. layouts (collection scopes are remapped to the new collection ids)
                db.layoutDao().upsertAll(
                    m.layouts.mapNotNull { l ->
                        val scope = if (l.scopeKey.startsWith("collection:")) {
                            val orig = l.scopeKey.removePrefix("collection:").toLongOrNull()
                            val mapped = orig?.let { newCollectionIds[it] } ?: return@mapNotNull null
                            LayoutPrefs.collectionScope(mapped)
                        } else l.scopeKey
                        LayoutPreferenceEntity(
                            scope, l.columns.coerceIn(2, 4), l.artworkScale.coerceIn(LayoutPrefs.MIN_ARTWORK_SCALE, 1f),
                            l.spacingDp.coerceIn(0, 24), l.showTitles, l.showArtists,
                            runCatching { SortKey.valueOf(l.sortKey) }.getOrDefault(SortKey.CUSTOM).name,
                            l.ascending, l.locked,
                            runCatching { ViewMode.valueOf(l.viewMode) }.getOrDefault(ViewMode.GRID).name,
                        )
                    },
                )

                // 6. artwork
                for ((ref, bytes) in staged) {
                    val ownerId: Long? = when (ref.type) {
                        ArtKind.TRACK -> trackIdByFingerprint[ref.ownerKey]
                        ArtKind.ALBUM -> albumIdByKey[ref.ownerKey]
                        ArtKind.COLLECTION -> ref.ownerKey.toLongOrNull()?.let { newCollectionIds[it] }
                    }
                    if (ownerId == null) continue
                    val row = artwork.prepareFromBytes(ref.type, ownerId, bytes) ?: continue
                    createdArtwork += row
                    db.artworkDao().upsert(row)
                    restoredArt++
                }
                // Old image files are deleted only after the commit succeeded (see below).
                replacedArtwork = oldArtwork
            }
        } catch (e: Throwable) {
            artwork.deleteFiles(createdArtwork) // roll back staged files; the database already rolled back
            throw e
        }
        artwork.deleteFiles(replacedArtwork)

        settings.replaceFrom(
            AppSettings(
                theme = ThemeMode.valueOf(m.settings.theme),
                autoScan = m.settings.autoScan,
                resumePlayback = m.settings.resumePlayback,
                shuffle = m.settings.shuffle,
                repeatMode = m.settings.repeatMode,
            ),
        )
        RestoreSummary(m.collections.size, favoriteCount, skipped, restoredArt)
    }
}
