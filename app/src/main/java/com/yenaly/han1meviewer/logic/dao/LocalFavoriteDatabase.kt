package com.yenaly.han1meviewer.logic.dao

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.yenaly.han1meviewer.logic.entity.LocalFavoriteEntity
import com.yenaly.yenaly_libs.utils.applicationContext

/**
 * 本地收藏独立成库，避免每次新增栏位都要迁移已有的历史／关键帧资料库。
 */
@Database(
    entities = [LocalFavoriteEntity::class],
    version = 1, exportSchema = false
)
abstract class LocalFavoriteDatabase : RoomDatabase() {

    abstract val localFavoriteDao: LocalFavoriteDao

    companion object {
        val instance by lazy {
            Room.databaseBuilder(
                applicationContext,
                LocalFavoriteDatabase::class.java,
                "local_favorites.db"
            ).build()
        }
    }
}
