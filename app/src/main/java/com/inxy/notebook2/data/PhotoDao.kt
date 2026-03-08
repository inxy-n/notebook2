package com.inxy.notebook2.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {

    @Insert
    suspend fun insertPhoto(photo: PhotoEntity)

    @Insert
    suspend fun insertAllPhotos(photos: List<PhotoEntity>)

    @Update
    suspend fun updatePhoto(photo: PhotoEntity)

    @Query("SELECT * FROM photos ORDER BY dateAdded DESC")
    fun getAllPhotos(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE dateAdded >= :timestamp ORDER BY dateAdded DESC")
    fun getPhotosAfterDate(timestamp: Long): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE isSynced = 0")
    suspend fun getUnsyncedPhotos(): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun getPhotoCount(): Int

    @Query("DELETE FROM photos WHERE uri = :uri")
    suspend fun deletePhotoByUri(uri: String)

    @Query("SELECT EXISTS(SELECT 1 FROM photos WHERE uri = :uri)")
    suspend fun isPhotoExists(uri: String): Boolean

    @Query("DELETE FROM photos")
    suspend fun deleteAll()
}