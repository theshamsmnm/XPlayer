package com.xplayer.app.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.xplayer.app.data.artwork.ArtworkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future

/**
 * Supplies notification / lock-screen / system-metadata artwork: custom artwork first, then embedded
 * artwork, then a generated fallback. A small LRU keeps only a few bitmaps (bounded memory).
 */
class ArtworkBitmapLoader(
    private val artwork: ArtworkStore,
    private val scope: CoroutineScope,
    private val sizePx: Int = 512,
) : BitmapLoader {

    private val cache = object : LinkedHashMap<Uri, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, Bitmap>?) = size > CACHE_SIZE
    }

    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = scope.future {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("Undecodable artwork")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future {
        synchronized(cache) { cache[uri] }?.let { return@future it }
        val ref = ArtworkUri.parse(uri) ?: error("Unsupported artwork uri: $uri")
        val bitmap = artwork.loadOrFallback(ref, sizePx)
        synchronized(cache) { cache[uri] = bitmap }
        bitmap
    }

    private companion object {
        const val CACHE_SIZE = 6
    }
}
