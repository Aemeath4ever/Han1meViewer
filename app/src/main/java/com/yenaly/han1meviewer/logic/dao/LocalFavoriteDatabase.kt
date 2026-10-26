package com.yenaly.han1meviewer.logic.dao

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.yenaly.han1meviewer.logic.entity.LocalFavoriteEntity
import com.yenaly.yenaly_libs.utils.applicationContext

/**
 * 本地收藏獨立成庫，避免每次新增欄位都要遷移已有的歷史／關鍵幀資料庫。
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
