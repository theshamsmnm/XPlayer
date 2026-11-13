package com.xplayer.app.data.backup

import kotlinx.serialization.Serializable

/**
 * Backup file = a ZIP archive:
 *   manifest.json            (this model)
 *   artwork/<name>.jpg       (custom artwork files referenced by [BackupArtwork.file])
 *
 * Tracks are referenced by *fingerprint* (title|artist|album|seconds), never by database id, so a
 * backup restores correctly on a re-indexed library or another device. The audio files themselves are
 * never part of a backup.
 */
@Serializable
data class BackupManifest(
    val format: String = FORMAT,
    val version: Int = CURRENT_VERSION,
    val createdAt: Long,
    val appVersion: String,
    val settings: BackupSettings,
    val layouts: List<BackupLayout> = emptyList(),
    val collections: List<BackupCollection> = emptyList(),
    val favoriteTracks: List<String> = emptyList(),
    val favoriteAlbums: List<String> = emptyList(),
    val albumOrder: List<String> = emptyList(),
    val hiddenTracks: List<String> = emptyList(),
    val artwork: List<BackupArtwork> = emptyList(),
) {
    companion object {
        const val FORMAT = "xplayer-backup"
        const val CURRENT_VERSION = 1
        const val MANIFEST_ENTRY = "manifest.json"
        const val ARTWORK_DIR = "artwork/"
    }
}

@Serializable
data class BackupSettings(
    val theme: String,
    val autoScan: Boolean,
    val resumePlayback: Boolean,
    val shuffle: Boolean,
    val repeatMode: Int,
)

@Serializable
data class BackupLayout(
    val scopeKey: String,
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

@Serializable
data class BackupCollection(
    /** Id at backup time; used to restore per-collection layout scopes ("collection:<id>"). */
    val originalId: Long,
    val name: String,
    val createdAt: Long,
    val position: Int,
    val tracks: List<BackupCollectionTrack> = emptyList(),
)

@Serializable
data class BackupCollectionTrack(val fingerprint: String, val position: Int, val addedAt: Long)

/**
 * Custom artwork. [ownerType] is TRACK/ALBUM/COLLECTION; [ownerKey] is a track fingerprint, an album
 * key, or the collection's [BackupCollection.originalId] as text.
 */
@Serializable
data class BackupArtwork(val ownerType: String, val ownerKey: String, val file: String)
