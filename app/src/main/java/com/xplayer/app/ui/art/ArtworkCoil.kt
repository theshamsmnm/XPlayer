package com.xplayer.app.ui.art

import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import com.xplayer.app.data.artwork.ArtworkStore
import com.xplayer.app.domain.model.ArtworkRef

/**
 * Cache key = kind + id + version (+ requested size by Coil). Changing custom artwork or the source
 * file changes `version`, so stale bitmaps are never shown (cache invalidation).
 */
class ArtworkKeyer : Keyer<ArtworkRef> {
    override fun key(data: ArtworkRef, options: Options): String =
        "art:${data.kind}:${data.id}:${data.version}"
}

/** Loads artwork for an [ArtworkRef] at the size Coil asks for; null result => composable draws the fallback. */
class ArtworkFetcher(
    private val store: ArtworkStore,
    private val ref: ArtworkRef,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val px = (options.size.width.pxOrElse { DEFAULT_PX }).coerceIn(64, 1024)
        val bitmap = store.load(ref, px) ?: return null
        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val store: ArtworkStore) : Fetcher.Factory<ArtworkRef> {
        override fun create(data: ArtworkRef, options: Options, imageLoader: ImageLoader): Fetcher =
            ArtworkFetcher(store, data, options)
    }

    private companion object {
        const val DEFAULT_PX = 512
    }
}

private inline fun coil.size.Dimension.pxOrElse(default: () -> Int): Int =
    (this as? coil.size.Dimension.Pixels)?.px ?: default()
