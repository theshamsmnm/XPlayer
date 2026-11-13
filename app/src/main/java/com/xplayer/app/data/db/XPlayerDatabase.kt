package com.xplayer.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        TrackEntity::class,
        CollectionEntity::class,
        CollectionOrderEntity::class,
        CollectionTrackCrossRef::class,
        FavoriteEntity::class,
        PlaybackHistoryEntity::class,
        CustomArtworkEntity::class,
        LibraryOrderEntity::class,
        LayoutPreferenceEntity::class,
        HiddenTrackEntity::class,
        QueueItemEntity::class,
    ],
    version = XPlayerDatabase.VERSION,
    exportSchema = true,
)
abstract class XPlayerDatabase : RoomDatabase() {
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun trackDao(): TrackDao
    abstract fun hiddenDao(): HiddenDao
    abstract fun collectionDao(): CollectionDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun artworkDao(): ArtworkDao
    abstract fun layoutDao(): LayoutDao
    abstract fun queueDao(): QueueDao

    companion object {
        const val VERSION = 1
        const val NAME = "xplayer.db"

        /**
         * Every schema change MUST add a [Migration] here (and bump [VERSION]). The database is
         * never recreated destructively because it holds user-owned data (collections, favorites,
         * custom artwork references, ordering, locks).
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context): XPlayerDatabase =
            Room.databaseBuilder(context.applicationContext, XPlayerDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
