package com.yenaly.han1meviewer.logic.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yenaly.han1meviewer.logic.entity.LocalFavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalFavoriteDao {

    @Query("SELECT * FROM LocalFavoriteEntity ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<LocalFavoriteEntity>>

    @Query(
        """SELECT * FROM LocalFavoriteEntity
           WHERE site = :site AND videoCode = :videoCode LIMIT 1"""
    )
    fun observe(site: String, videoCode: String): Flow<LocalFavoriteEntity?>

    @Query(
        """SELECT * FROM LocalFavoriteEntity
           WHERE site = :site AND videoCode = :videoCode LIMIT 1"""
    )
    suspend fun find(site: String, videoCode: String): LocalFavoriteEntity?

    @Query("SELECT * FROM LocalFavoriteEntity ORDER BY updatedAt DESC")
    suspend fun getAll(): List<LocalFavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LocalFavoriteEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<LocalFavoriteEntity>)

    @Update
    suspend fun update(entity: LocalFavoriteEntity)

    @Delete
    suspend fun delete(entity: LocalFavoriteEntity)

    @Query("DELETE FROM LocalFavoriteEntity")
    suspend fun deleteAll()

    @Query(
        """UPDATE LocalFavoriteEntity
           SET rating = :rating, comment = :comment, updatedAt = :updatedAt
           WHERE id = :id"""
    )
    suspend fun updateRating(id: Long, rating: Int, comment: String, updatedAt: Long)
}
