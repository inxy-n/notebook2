package com.inxy.notebook2.ui.dashboard

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.inxy.notebook2.data.PhotoEntity
import com.inxy.notebook2.data.PhotoDatabase
import com.inxy.notebook2.ui.home.ProcessCourses
import com.inxy.notebook2.ui.home.Week
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val context = getApplication<Application>()
    private val photoDao = PhotoDatabase.getInstance(context).photoDao()

    private val _photos = MutableLiveData<List<PhotoEntity>>()
    val photos: LiveData<List<PhotoEntity>> = _photos

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    init {
        loadPhotosFromDatabase()
    }

    private fun loadPhotosFromDatabase() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // 使用 Flow 收集数据
                photoDao.getAllPhotos().collect { photosFromDb ->
                    _photos.value = photosFromDb
                }
                _error.value = null
            } catch (e: Exception) {
                _error.value = "加载数据库失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshPhotos() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // 扫描照片
                val scannedPhotos = scanPhotosFromStorage()

                // 获取数据库中已存在的所有URI
                val existingUris = photoDao.getAllPhotosSync().map { it.uri }.toSet()

                // 过滤出不在数据库中的新照片
                val newPhotos = scannedPhotos.filter { !existingUris.contains(it.uri) }

                if (newPhotos.isNotEmpty()) {
                    // 只插入新照片
                    photoDao.insertAllPhotos(newPhotos)
                    Log.d("DashboardViewModel", "新增 ${newPhotos.size} 张照片")
                } else {
                    Log.d("DashboardViewModel", "没有新照片")
                }

                // 重新加载数据
                loadPhotosFromDatabase()
                _error.value = null
            } catch (e: Exception) {
                _error.value = "扫描照片失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun scanPhotosFromStorage(): List<PhotoEntity> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<PhotoEntity>()
        val contentResolver = context.contentResolver

        // 设置查询条件：2026年3月1日之后的照片
        val startDate = LocalDateTime.of(2026, 3, 1, 0, 0, 0)
            .toEpochSecond(java.time.ZoneOffset.UTC)

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATA
        )

        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val selectionArgs = arrayOf(startDate.toString())

        val cursor = contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            null
        )

        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateAddedColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val pathColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val name = it.getString(nameColumn)
                val dateAdded = it.getLong(dateAddedColumn)
                val size = it.getLong(sizeColumn)
                val path = it.getString(pathColumn)

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                photos.add(
                    PhotoEntity(
                        uri = contentUri.toString(),
                        fileName = name,
                        filePath = path,
                        dateAdded = dateAdded,
                        size = size,
                        isSynced = false
                    )
                )
            }
        }

        return@withContext photos
    }

    fun deletePhoto(uriString: String) {
        viewModelScope.launch {
            try {
                photoDao.deleteByUri(uriString)
                loadPhotosFromDatabase()
            } catch (e: Exception) {
                _error.value = "删除失败: ${e.message}"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun formatDate(timestamp: Long): String {
        val date = LocalDateTime.ofEpochSecond(timestamp, 0, java.time.ZoneOffset.UTC)
        return date.format(dateFormatter)
    }
}

class UploadService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "http://gt.inxy.xyz:8080"
    private val jsonFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val processCourses = ProcessCourses()

    /**
     * 上传进度回调接口
     */
    interface UploadCallback {
        fun onProgress(current: Int, total: Int, message: String)
        fun onSuccess(response: String)
        fun onError(error: String)
    }

    /**
     * 生成并上传JSON文件
     */
    suspend fun generateAndUploadJson(
        photoEntities: List<PhotoEntity>,
        callback: UploadCallback
    ) = withContext(Dispatchers.IO) {
        try {
            callback.onProgress(0, 1, "正在生成JSON文件...")

            // 1. 处理照片数据，生成按周和课程组织的结构
            val weeks = processCourses.processPhotoData(photoEntities)

            // 2. 构建JSON对象
            val jsonObject = buildPhotosJson(weeks, photoEntities)

            // 3. 保存JSON文件到缓存目录
            val jsonFile = saveJsonToFile(jsonObject)

            // 4. 上传JSON文件
            callback.onProgress(0, 1, "正在上传JSON文件...")
            uploadJsonFile(jsonFile, callback)

        } catch (e: Exception) {
            Log.e("UploadService", "生成JSON失败", e)
            callback.onError("生成JSON失败: ${e.message}")
        }
    }

    /**
     * 构建照片JSON
     */
    private fun buildPhotosJson(
        weeks: List<Week>,
        allPhotos: List<PhotoEntity>
    ): JSONObject {
        val rootJson = JSONObject()
        val weeksArray = JSONArray()

        // 遍历每一周
        weeks.forEachIndexed { index, week ->
            val weekJson = JSONObject()
            val weekNum = index + 1
            weekJson.put("weekNum", weekNum)
            weekJson.put("weekName", "第 $weekNum 周")

            val coursesArray = JSONArray()

            // 遍历每周的课程
            week.courses.forEach { course ->
                val courseJson = JSONObject()
                courseJson.put("courseId", extractCourseId(course.name))
                courseJson.put("courseName", extractCourseName(course.name))
                courseJson.put("courseType", extractCourseType(course.name))
                courseJson.put("dayOfWeek", extractDayOfWeek(course.name))

                val photosArray = JSONArray()

                // 遍历课程的照片
                course.images.forEach { uri ->
                    val photoJson = JSONObject()
                    val fileName = getFileNameFromUri(uri)
                    photoJson.put("fileName", fileName)

                    val uploadTime = extractDateTimeFromFileName(fileName) ?: LocalDateTime.now()
                    photoJson.put("uploadTime", uploadTime.format(jsonFormatter))

                    photosArray.put(photoJson)
                }

                courseJson.put("photos", photosArray)
                coursesArray.put(courseJson)
            }

            weekJson.put("courses", coursesArray)
            weeksArray.put(weekJson)
        }

        rootJson.put("weeks", weeksArray)
        rootJson.put("lastUpdated", LocalDateTime.now().format(jsonFormatter))
        rootJson.put("totalPhotos", allPhotos.size)

        return rootJson
    }

    /**
     * 保存JSON到文件
     */
    private fun saveJsonToFile(jsonObject: JSONObject): File {
        val fileName = "allphoto_${System.currentTimeMillis()}.json"
        val file = File(context.cacheDir, fileName)
        file.writeText(jsonObject.toString(2))
        return file
    }

    /**
     * 上传JSON文件
     */
    private fun uploadJsonFile(file: File, callback: UploadCallback) {
        try {
            val requestBody = file.asRequestBody("application/json".toMediaType())
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.Companion.FORM)
                .addFormDataPart("file", file.name, requestBody)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/api/upload/json")
                .post(multipartBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: "成功"
                    callback.onSuccess("JSON上传成功: $responseBody")
                } else {
                    callback.onError("JSON上传失败: ${response.code}")
                }
            }
        } catch (e: Exception) {
            callback.onError("JSON上传异常: ${e.message}")
        } finally {
            file.delete()
        }
    }

    /**
     * 批量上传照片 - 基于JSON中列出的文件
     */
    suspend fun uploadPhotosBasedOnJson(
        photoEntities: List<PhotoEntity>,
        callback: UploadCallback
    ) = withContext(Dispatchers.IO) {
        try {
            callback.onProgress(0, 1, "正在处理照片分类...")

            // 1. 首先处理照片，生成按周和课程组织的结构
            val weeks = processCourses.processPhotoData(photoEntities)

            // 2. 从weeks中提取所有需要上传的照片URI
            val photosToUpload = extractPhotosFromWeeks(weeks, photoEntities)

            if (photosToUpload.isEmpty()) {
                callback.onError("没有找到需要上传的照片")
                return@withContext
            }

            val total = photosToUpload.size
            var successCount = 0
            var failCount = 0

            callback.onProgress(0, total, "开始上传 $total 张照片...")

            // 3. 上传筛选出的照片
            photosToUpload.forEachIndexed { index, photo ->
                try {
                    callback.onProgress(index + 1, total, "正在上传第 ${index + 1}/$total 张: ${photo.fileName}")

                    val result = uploadSinglePhoto(photo)
                    if (result) {
                        successCount++
                    } else {
                        failCount++
                    }

                } catch (e: Exception) {
                    Log.e("UploadService", "上传失败: ${photo.fileName}", e)
                    failCount++
                }
            }

            // 4. 返回结果
            val resultMessage = when {
                failCount == 0 -> "全部上传成功！共 $successCount 张"
                successCount == 0 -> "全部上传失败！共 $failCount 张"
                else -> "上传完成：成功 $successCount 张，失败 $failCount 张"
            }

            callback.onSuccess(resultMessage)

        } catch (e: Exception) {
            Log.e("UploadService", "批量上传失败", e)
            callback.onError("批量上传失败: ${e.message}")
        }
    }

    /**
     * 从Weeks结构中提取需要上传的照片
     */
    private fun extractPhotosFromWeeks(
        weeks: List<Week>,
        allPhotos: List<PhotoEntity>
    ): List<PhotoEntity> {
        // 收集所有在weeks中出现的照片URI
        val urisInWeeks = mutableSetOf<String>()

        weeks.forEach { week ->
            week.courses.forEach { course ->
                course.images.forEach { uri ->
                    urisInWeeks.add(uri.toString())
                }
            }
        }

        // 从allPhotos中筛选出对应的PhotoEntity
        return allPhotos.filter { photo ->
            urisInWeeks.contains(photo.uri)
        }
    }

    /**
     * 上传单张照片
     */
    private fun uploadSinglePhoto(photo: PhotoEntity): Boolean {
        return try {
            val uri = Uri.parse(photo.uri)
            val file = getFileFromUri(uri) ?: return false

            val requestBody = file.asRequestBody("image/jpeg".toMediaType())
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.Companion.FORM)
                .addFormDataPart("photo", file.name, requestBody)
                .addFormDataPart("fileName", photo.fileName)
                .addFormDataPart("uploadTime", LocalDateTime.now().format(jsonFormatter))
                .build()

            val request = Request.Builder()
                .url("$baseUrl/api/upload/photo")
                .post(multipartBody)
                .build()

            client.newCall(request).execute().use { response ->
                val success = response.isSuccessful
                if (success) {
                    Log.d("UploadService", "上传成功: ${photo.fileName}")
                } else {
                    Log.e("UploadService", "上传失败: ${photo.fileName}, code: ${response.code}")
                }
                success
            }
        } catch (e: Exception) {
            Log.e("UploadService", "上传单张照片异常: ${photo.fileName}", e)
            false
        } finally {
            // 清理临时文件
            getFileFromUri(Uri.parse(photo.uri))?.delete()
        }
    }

    /**
     * 从Uri获取File
     */
    private fun getFileFromUri(uri: Uri): File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val fileName = getFileNameFromUri(uri)
            val tempFile = File(context.cacheDir, "$fileName")
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            tempFile
        } catch (e: Exception) {
            Log.e("UploadService", "获取文件失败: $uri", e)
            null
        }
    }

    /**
     * 从Uri获取文件名
     */
    private fun getFileNameFromUri(uri: Uri): String {
        var fileName = "unknown.jpg"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndexOrThrow("_display_name")
                    fileName = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            fileName = uri.lastPathSegment ?: "unknown.jpg"
        }
        return fileName
    }

    /**
     * 从文件名提取日期时间
     */
    private fun extractDateTimeFromFileName(fileName: String): LocalDateTime? {
        val regex = Regex("(?:IMG_)?(\\d{8})_(\\d{6})\\.jpg$", RegexOption.IGNORE_CASE)
        val matchResult = regex.find(fileName)
        val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        return matchResult?.let {
            val datePart = it.groupValues[1]
            val timePart = it.groupValues[2]
            try {
                LocalDateTime.parse(datePart + timePart, formatter)
            } catch (e: Exception) {
                null
            }
        }
    }

    // 辅助方法保持不变
    private fun extractCourseId(courseName: String): String {
        return when {
            courseName.contains("ALGEBRA") -> "104168"
            courseName.contains("ORDINARY DIFFERENTIAL") -> "104285"
            courseName.contains("COMBINATORIAL") -> "104291"
            courseName.contains("CALCULUS") -> "104295"
            courseName.contains("COMPUTER") -> "104818"
            courseName.contains("MARXISM") -> "321004"
            courseName.contains("NATIONAL SECURITY") -> "321007"
            else -> "UNKNOWN"
        }
    }

    private fun extractCourseName(fullName: String): String {
        return fullName.replace(Regex("\\([^)]*\\)"), "").trim()
    }

    private fun extractCourseType(fullName: String): String {
        return when {
            fullName.contains("L)") || fullName.contains("L ") -> "L"
            fullName.contains("T)") || fullName.contains("T ") -> "T"
            fullName.contains("Lab)") || fullName.contains("Lab ") -> "Lab"
            else -> "Unknown"
        }
    }

    private fun extractDayOfWeek(fullName: String): Int {
        return when {
            fullName.contains("周一") -> 1
            fullName.contains("周二") -> 2
            fullName.contains("周三") -> 3
            fullName.contains("周四") -> 4
            fullName.contains("周五") -> 5
            fullName.contains("周六") -> 6
            fullName.contains("周日") -> 7
            else -> 1
        }
    }
}