package com.inxy.notebook2.data
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoRepository(
    private val context: Context,
    private val photoDao: PhotoDao
) {

    /**
     * 获取2026年3月1日至今的所有照片
     */
    suspend fun loadPhotosFrom2026March1(): List<Photo> = withContext(Dispatchers.IO) {
        val timestamp2026March1 = getTimestampFor2026March1()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE
        )

        // 查询条件：日期 >= 2026年3月1日
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val selectionArgs = arrayOf(timestamp2026March1.toString())

        val photos = mutableListOf<Photo>()

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val fileName = cursor.getString(nameColumn)
                val filePath = cursor.getString(dataColumn)
                val dateAdded = cursor.getLong(dateAddedColumn)
                val dateModified = cursor.getLong(dateModifiedColumn)
                val size = cursor.getLong(sizeColumn)
                val mimeType = cursor.getString(mimeTypeColumn)

                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                val photo = Photo(
                    uri = uri,
                    fileName = fileName,
                    filePath = filePath,
                    dateAdded = dateAdded,
                    dateModified = dateModified,
                    size = size,
                    mimeType = mimeType
                )
                photos.add(photo)
            }
        }

        return@withContext photos
    }

    /**
     * 将照片保存到数据库
     */
    suspend fun savePhotosToDatabase(photos: List<Photo>) {
        val photoEntities = photos.map { photo ->
            PhotoEntity(
                uri = photo.uri.toString(),
                fileName = photo.fileName,
                filePath = photo.filePath,
                dateAdded = photo.dateAdded,
                dateModified = photo.dateModified,
                size = photo.size,
                mimeType = photo.mimeType,
                isSynced = false
            )
        }
        photoDao.insertAllPhotos(photoEntities)
    }

    /**
     * 刷新照片数据（从系统读取并更新数据库）
     */
    suspend fun refreshPhotos() {
        val photos = loadPhotosFrom2026March1()
        photoDao.deleteAll()
        savePhotosToDatabase(photos)
    }

    /**
     * 获取所有已保存的照片（从数据库）
     */
    fun getAllPhotos(): Flow<List<PhotoEntity>> {
        return photoDao.getAllPhotos()
    }

    /**
     * 获取2026年3月1日之后的照片
     */
    fun getPhotosAfter2026March1(): Flow<List<PhotoEntity>> {
        val timestamp = getTimestampFor2026March1()
        return photoDao.getPhotosAfterDate(timestamp)
    }

    /**
     * 检查照片是否已存在于数据库
     */
    suspend fun isPhotoExists(uri: String): Boolean {
        return photoDao.isPhotoExists(uri)
    }

    /**
     * 删除照片记录
     */
    suspend fun deletePhoto(uri: String) {
        photoDao.deletePhotoByUri(uri)
    }

    /**
     * 获取2026年3月1日的Unix时间戳（秒）
     */
    private fun getTimestampFor2026March1(): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.YEAR, 2026)
            set(java.util.Calendar.MONTH, java.util.Calendar.MARCH)
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis / 1000 // 转换为秒
    }

    /**
     * 格式化日期（用于显示）
     */
    fun formatDate(timestamp: Long): String {
        val date = Date(timestamp * 1000) // 转换为毫秒
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return format.format(date)
    }
}