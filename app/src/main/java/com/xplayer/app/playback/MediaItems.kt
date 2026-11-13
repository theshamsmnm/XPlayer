package com.xplayer.app.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.xplayer.app.domain.model.ArtKind
import com.xplayer.app.domain.model.ArtworkRef
import com.xplayer.app.domain.model.TrackItem

/**
 * Artwork travels through the media session as a *URI* (never as bitmaps in the queue):
 * `xplayer-art://<kind>/<id>?v=<version>`. [ArtworkBitmapLoader] resolves it to a bitmap for the
 * notification / lock screen / system media metadata, and the Compose UI converts it back into an
 * [ArtworkRef]. Because the version is part of the URI, replacing artwork changes the URI and so
 * invalidates every consumer's cache.
 */
object ArtworkUri {
    const val SCHEME = "xplayer-art"

    fun build(ref: ArtworkRef): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(ref.kind.name.lowercase())
        .appendPath(ref.id.toString())
        .appendQueryParameter("v", ref.version.toString())
        .appendQueryParameter("l", ref.label)
        .build()

    fun parse(uri: Uri?): ArtworkRef? {
        if (uri == null || uri.scheme != SCHEME) return null
        val kind = runCatching { ArtKind.valueOf(uri.authority.orEmpty().uppercase()) }.getOrNull() ?: return null
        val id = uri.pathSegments.firstOrNull()?.toLongOrNull() ?: return null
        return ArtworkRef(kind, id, uri.getQueryParameter("v")?.toLongOrNull() ?: 0L, uri.getQueryParameter("l").orEmpty())
    }
}

object MediaItems {
    const val EXTRA_DURATION = "duration_ms"

    fun from(track: TrackItem): MediaItem = MediaItem.Builder()
        .setMediaId(track.id.toString())
        .setUri(track.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist.ifBlank { null })
                .setAlbumTitle(track.albumTitle.ifBlank { null })
                .setArtworkUri(ArtworkUri.build(track.art))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(Bundle().apply { putLong(EXTRA_DURATION, track.durationMs) })
                .build(),
        )
        .build()

    fun trackId(item: MediaItem?): Long? = item?.mediaId?.toLongOrNull()
}
