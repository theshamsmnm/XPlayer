package com.xplayer.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.xplayer.app.ui.art.ArtworkFetcher
import com.xplayer.app.ui.art.ArtworkKeyer

class XPlayerApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /** Bounded in-memory cache (15% of the heap); artwork is always decoded at thumbnail size. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(ArtworkKeyer())
            add(ArtworkFetcher.Factory(container.artwork))
        }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .crossfade(true)
        .build()
}

val Context.appContainer: AppContainer get() = (applicationContext as XPlayerApp).container
