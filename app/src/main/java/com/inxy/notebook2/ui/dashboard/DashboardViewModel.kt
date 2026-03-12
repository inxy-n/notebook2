package com.inxy.notebook2.ui.dashboard

import android.app.Application
import android.content.ContentUris
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
import com.inxy.notebook2.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val context = getApplication<Application>()
    private val photoDao = PhotoDatabase.getInstance(context).photoDao()
    private val localJsonManager = LocalJsonManager(context)
    private val syncManager = SyncManager(context)

    private val _photos = MutableLiveData<List<PhotoEntity>>()
    val photos: LiveData<List<PhotoEntity>> = _photos

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _syncProgress = MutableLiveData<SyncProgress>()
    val syncProgress: LiveData<SyncProgress> = _syncProgress

    private val _dataUpdated = MutableLiveData<Boolean>()
    val dataUpdated: LiveData<Boolean> = _dataUpdated
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val processCourses = ProcessCourses()

    init {
        loadPhotosFromDatabase()

        // 添加调试代码
        viewModelScope.launch {
            val jsonExists = withContext(Dispatchers.IO) {
                localJsonManager.checkJsonFile()
            }
            Log.d("DashboardViewModel", jsonExists)
        }
    }

    fun loadPhotosFromDatabase() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // 使用 first() 只取第一个值，然后停止收集
                val photosFromDb = photoDao.getAllPhotos().first()
                _photos.value = photosFromDb
                _error.value = null
            } catch (e: Exception) {
                _error.value = "加载数据库失败: ${e.message}"
            } finally {
                _isLoading.value = false  // 现在 finally 块会被执行
            }
        }
    }

    /**
     * 使用UploadService的算法生成完整的JSON
     */
    private suspend fun generateFullJson(allPhotos: List<PhotoEntity>): PhotosJson? = withContext(Dispatchers.IO) {
        try {
            // ⚠️ 添加：按时间正序排序
            val sortedPhotos = allPhotos.sortedBy { it.dateAdded }

            // 创建一个映射：URI -> PhotoEntity
            val photoMap = sortedPhotos.associateBy { it.uri }

            // 1. 处理照片数据，生成按周和课程组织的结构
            val weeks = processCourses.processPhotoData(sortedPhotos)

            // 2. 计算总照片数
            var actualTotalPhotos = 0
            val weeksInJson = weeks.mapIndexed { index, week ->
                val weekNum = index + 1
                val weekPhotosCount = week.courses.sumOf { it.images.size }
                actualTotalPhotos += weekPhotosCount

                val coursesInJson = week.courses.map { course ->
                    CourseInJson(
                        courseId = extractCourseId(course.name),
                        courseName = extractCourseName(course.name),
                        courseType = course.courseType,
                        dayOfWeek = extractDayOfWeek(course.name),
                        photos = course.images.map { uri ->
                            val uriString = uri.toString()
                            // 从映射中获取PhotoEntity，然后取fileName
                            val fileName = photoMap[uriString]?.fileName
                                ?: getFileNameFromUri(uri)  // 降级方案

                            PhotoInJson(
                                fileName = fileName,
                                uri = uriString,
                                uploadTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            )
                        }
                    )
                }

                WeekInJson(
                    weekNum = weekNum,
                    weekName = "第 $weekNum 周",
                    courses = coursesInJson
                )
            }

            return@withContext PhotosJson(
                weeks = weeksInJson,
                lastUpdated = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                totalPhotos = actualTotalPhotos
            )

        } catch (e: Exception) {
            Log.e("DashboardViewModel", "生成JSON失败", e)
            null
        }
    }
    /**
     * 刷新照片（扫描相册 + 下载目录）- 增量更新，保留服务器下载的照片
     */
    fun refreshPhotos() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // 1. 扫描系统相册
                val systemPhotos = scanSystemPhotos()
                Log.d("DashboardViewModel", "扫描到系统照片: ${systemPhotos.size}张")

                // 2. 扫描下载目录
                val downloadedPhotos = scanDownloadedPhotos()
                Log.d("DashboardViewModel", "扫描到下载照片: ${downloadedPhotos.size}张")

                // 3. 合并照片
                val allPhotos = (systemPhotos + downloadedPhotos).distinctBy { it.uri }

                // 4. 获取数据库中已存在的所有照片（包括服务器下载的）
                val existingPhotos = photoDao.getAllPhotosSync()
                val existingUris = existingPhotos.map { it.uri }.toSet()

                // 5. 过滤出新照片（只添加不在数据库中的）
                val newPhotos = allPhotos.filter { !existingUris.contains(it.uri) }

                if (newPhotos.isNotEmpty()) {
                    Log.d("DashboardViewModel", "发现新照片: ${newPhotos.size}张")

                    // ⚠️ 关键修改：将新照片按时间正序排序（旧的在前）
                    val sortedNewPhotos = newPhotos.sortedBy { it.dateAdded }
                    Log.d("DashboardViewModel", "新照片按时间正序排序完成")

                    // 6. 插入新照片（保持正序插入）
                    photoDao.insertAllPhotos(sortedNewPhotos)

                    // 7. 获取所有照片（包括旧的 + 新插入的）
                    val allDbPhotos = photoDao.getAllPhotosSync()
                    Log.d("DashboardViewModel", "数据库最终有 ${allDbPhotos.size} 张照片")

                    // 8. 获取现有的JSON
                    val existingJson = localJsonManager.loadJson()

                    if (existingJson != null) {
                        // 9. 只处理新照片，生成增量JSON
                        val newPhotosList = allDbPhotos.filter { photo ->
                            sortedNewPhotos.any { it.uri == photo.uri }
                        }

                        // ⚠️ 再次确保处理时也是正序
                        val sortedNewPhotosList = newPhotosList.sortedBy { it.dateAdded }

                        if (sortedNewPhotosList.isNotEmpty()) {
                            val newWeeks = processCourses.processPhotoData(sortedNewPhotosList)

                            // 10. 将新照片转换为JSON格式
                            val newWeeksInJson = newWeeks.map { week ->
                                WeekInJson(
                                    weekNum = extractWeekNum(week.name),
                                    weekName = week.name,
                                    courses = week.courses.map { course ->
                                        CourseInJson(
                                            courseId = extractCourseId(course.name),
                                            courseName = extractCourseName(course.name),
                                            courseType = course.courseType,
                                            dayOfWeek = extractDayOfWeek(course.name),
                                            photos = course.images.map { uri ->
                                                val uriString = uri.toString()
                                                val fileName = getFileNameFromUri(uri)
                                                PhotoInJson(
                                                    fileName = fileName,
                                                    uri = uriString,
                                                    uploadTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                                )
                                            }
                                        )
                                    }
                                )
                            }

                            // 11. 合并到现有JSON
                            localJsonManager.updateJson(newWeeksInJson)
                            Log.d("DashboardViewModel", "JSON增量更新完成，新增 ${sortedNewPhotosList.size} 张照片")
                        }
                    } else {
                        // 12. 如果没有现有JSON，生成完整的（也需要正序）
                        val allDbPhotos = photoDao.getAllPhotosSync()
                        val sortedAllPhotos = allDbPhotos.sortedBy { it.dateAdded }
                        val fullJson = generateFullJson(sortedAllPhotos)
                        if (fullJson != null) {
                            localJsonManager.replaceJson(fullJson.weeks, fullJson.totalPhotos)
                            Log.d("DashboardViewModel", "完整JSON已生成，共 ${fullJson.totalPhotos} 张照片")
                        }
                    }
                } else {
                    Log.d("DashboardViewModel", "没有新照片，JSON保持不变")
                }

                // 13. 重新加载数据
                loadPhotosFromDatabase()

                _error.value = null
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "刷新失败", e)
                _error.value = "刷新失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
        _dataUpdated.value = true
    }

    /**
     * 扫描系统相册
     */
    private suspend fun scanSystemPhotos(): List<PhotoEntity> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<PhotoEntity>()
        val contentResolver = context.contentResolver

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
                        source = "system",
                        isSynced = false
                    )
                )
            }
        }

        return@withContext photos
    }

    /**
     * 扫描下载目录
     */
    private suspend fun scanDownloadedPhotos(): List<PhotoEntity> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<PhotoEntity>()
        val downloadDir = File(context.getExternalFilesDir(null), "downloaded_photos")

        if (!downloadDir.exists()) {
            return@withContext photos
        }

        downloadDir.listFiles { file ->
            file.isFile && file.extension.lowercase() in setOf("jpg", "jpeg", "png")
        }?.forEach { file ->
            val fileName = file.name
            val dateAdded = file.lastModified() / 1000

            photos.add(
                PhotoEntity(
                    uri = Uri.fromFile(file).toString(),
                    fileName = fileName,
                    filePath = file.absolutePath,
                    dateAdded = dateAdded,
                    size = file.length(),
                    source = "downloaded",
                    isSynced = true
                )
            )
        }

        return@withContext photos
    }

    /**
     * 同步服务器数据（处理顺序变化）
     */
    fun syncWithServer() {
        viewModelScope.launch {
            _isLoading.value = true
            _syncProgress.value = SyncProgress(0, 0, "开始同步...")

            try {
                // 1. 从服务器下载JSON文件
                _syncProgress.value = SyncProgress(0, 1, "正在下载服务器JSON...")
                val serverJson = syncManager.downloadServerJson()

                if (serverJson == null) {
                    _error.value = "下载服务器JSON失败"
                    return@launch
                }

                // 打印服务器JSON信息
                Log.d("DashboardViewModel", "========== 服务器JSON信息 ==========")
                Log.d("DashboardViewModel", "服务器JSON - 总照片数: ${serverJson.totalPhotos}")
                Log.d("DashboardViewModel", "服务器JSON - 周数: ${serverJson.weeks.size}")

                // 2. 获取服务器JSON中的所有文件名
                val serverFileNames = syncManager.extractFileNamesFromJson(serverJson)
                Log.d("DashboardViewModel", "服务器JSON - 提取到 ${serverFileNames.size} 个文件名")
                Log.d("DashboardViewModel", "服务器JSON - 前10个文件名: ${serverFileNames.take(10)}")

                // 3. 获取本地JSON
                val localJson = localJsonManager.loadJson()

                Log.d("DashboardViewModel", "========== 本地JSON信息 ==========")
                if (localJson == null) {
                    Log.d("DashboardViewModel", "本地JSON为空！")
                } else {
                    Log.d("DashboardViewModel", "本地JSON - 总照片数: ${localJson.totalPhotos}")
                    Log.d("DashboardViewModel", "本地JSON - 周数: ${localJson.weeks.size}")
                }

                // 4. 获取本地所有照片
                val allDbPhotos = photoDao.getAllPhotosSync()
                val localFileMap = allDbPhotos.associateBy { it.fileName }
                val downloadedDir = File(context.getExternalFilesDir(null), "downloaded_photos")

                // 5. 找出需要下载的照片（服务器有，本地没有且未下载）
                val photosToDownload = serverFileNames.filter { fileName ->
                    !localFileMap.containsKey(fileName) &&
                            !File(downloadedDir, fileName).exists()
                }

                Log.d("DashboardViewModel", "========== 比对结果 ==========")
                Log.d("DashboardViewModel", "服务器文件数: ${serverFileNames.size}")
                Log.d("DashboardViewModel", "本地文件数: ${localFileMap.size}")
                Log.d("DashboardViewModel", "需要下载: ${photosToDownload.size} 张新照片")

                // 6. 按服务器顺序下载新照片
                if (photosToDownload.isNotEmpty()) {
                    Log.d("DashboardViewModel", "需要下载的前10个文件: ${photosToDownload.take(10)}")

                    val downloadedPhotos = mutableListOf<PhotoEntity>()

                    photosToDownload.forEachIndexed { index, fileName ->
                        _syncProgress.postValue(
                            SyncProgress(index + 1, photosToDownload.size, "正在下载 ${index + 1}/${photosToDownload.size}: $fileName")
                        )

                        Log.d("DashboardViewModel", "开始下载: $fileName")

                        val photo = syncManager.downloadPhoto(fileName)
                        if (photo != null) {
                            downloadedPhotos.add(photo)
                            Log.d("DashboardViewModel", "下载成功: $fileName")
                        } else {
                            Log.e("DashboardViewModel", "下载失败: $fileName")
                        }
                    }

                    // 7. 将新照片添加到数据库
                    if (downloadedPhotos.isNotEmpty()) {
                        photoDao.insertAllPhotos(downloadedPhotos)
                        Log.d("DashboardViewModel", "已插入 ${downloadedPhotos.size} 张新照片到数据库")
                    }
                }

                // 8. 重新获取所有照片
                val updatedDbPhotos = photoDao.getAllPhotosSync()

                // 9. 合并JSON（保持服务器顺序）
                mergeJsonWithServer(serverJson, updatedDbPhotos)

                // 10. 重新加载数据
                loadPhotosFromDatabase()

                _syncProgress.value = SyncProgress(
                    serverFileNames.size,
                    serverFileNames.size,
                    "同步完成，新增 ${photosToDownload.size} 张"
                )

            } catch (e: Exception) {
                Log.e("DashboardViewModel", "同步过程中发生异常", e)
                _error.value = "同步失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 合并服务器JSON和本地数据（保持服务器顺序）
     */
    private suspend fun mergeJsonWithServer(serverJson: PhotosJson, allDbPhotos: List<PhotoEntity>) {
        withContext(Dispatchers.IO) {
            try {
                // 获取本地JSON
                val localJson = localJsonManager.loadJson()

                if (localJson == null) {
                    Log.d("DashboardViewModel", "本地JSON为空，直接使用服务器JSON")
                    localJsonManager.saveJson(serverJson)
                    return@withContext
                }

                Log.d("DashboardViewModel", "开始合并JSON - 本地周数: ${localJson.weeks.size}, 服务器周数: ${serverJson.weeks.size}")

                // 按照服务器的顺序合并周
                val mergedWeeks = mergeWeeksWithOrder(localJson.weeks, serverJson.weeks, allDbPhotos)

                val mergedJson = PhotosJson(
                    weeks = mergedWeeks,
                    lastUpdated = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    totalPhotos = mergedWeeks.sumOf { week ->
                        week.courses.sumOf { course -> course.photos.size }
                    }
                )

                Log.d("DashboardViewModel", "合并后JSON - 周数: ${mergedJson.weeks.size}, 总照片数: ${mergedJson.totalPhotos}")

                localJsonManager.saveJson(mergedJson)
                Log.d("DashboardViewModel", "合并后的JSON已保存")

            } catch (e: Exception) {
                Log.e("DashboardViewModel", "合并JSON失败", e)
            }
        }
    }

    /**
     * 合并周数据（保持服务器顺序）
     */
    private fun mergeWeeksWithOrder(
        localWeeks: List<WeekInJson>,
        serverWeeks: List<WeekInJson>,
        allDbPhotos: List<PhotoEntity>
    ): List<WeekInJson> {
        // 创建本地周的映射
        val localWeekMap = localWeeks.associateBy { it.weekNum }

        // 按照服务器的顺序处理
        return serverWeeks.map { serverWeek ->
            val localWeek = localWeekMap[serverWeek.weekNum]
            if (localWeek != null) {
                // 合并课程，保持服务器顺序
                val mergedCourses = mergeCoursesWithOrder(localWeek.courses, serverWeek.courses, allDbPhotos)
                serverWeek.copy(courses = mergedCourses)
            } else {
                // 新增周
                serverWeek
            }
        }
    }

    /**
     * 合并课程数据（保持服务器顺序）
     */
    private fun mergeCoursesWithOrder(
        localCourses: List<CourseInJson>,
        serverCourses: List<CourseInJson>,
        allDbPhotos: List<PhotoEntity>
    ): List<CourseInJson> {
        // 创建本地课程的映射
        val localCourseMap = localCourses.associateBy {
            "${it.courseId}_${it.dayOfWeek}"
        }

        // 创建照片映射
        val photoMap = allDbPhotos.associateBy { it.fileName }

        // 按照服务器的顺序处理
        return serverCourses.map { serverCourse ->
            val key = "${serverCourse.courseId}_${serverCourse.dayOfWeek}"
            val localCourse = localCourseMap[key]

            if (localCourse != null) {
                // 合并照片，保持服务器顺序
                val mergedPhotos = mergePhotosWithOrder(localCourse.photos, serverCourse.photos, photoMap)
                serverCourse.copy(photos = mergedPhotos)
            } else {
                // 新增课程
                serverCourse
            }
        }
    }

    /**
     * 合并照片数据（保持服务器顺序，同时保留本地新增的）
     */
    private fun mergePhotosWithOrder(
        localPhotos: List<PhotoInJson>,
        serverPhotos: List<PhotoInJson>,
        photoMap: Map<String, PhotoEntity>
    ): List<PhotoInJson> {
        // 创建本地照片的映射
        val localPhotoMap = localPhotos.associateBy { it.fileName }

        // 找出服务器上没有但本地有的照片（本地新增的）
        val localOnlyPhotos = localPhotos.filter { localPhoto ->
            serverPhotos.none { it.fileName == localPhoto.fileName }
        }

        // 按照服务器顺序处理，并插入本地新增的照片
        val result = mutableListOf<PhotoInJson>()

        // 先添加服务器顺序的照片
        serverPhotos.forEach { serverPhoto ->
            val localPhoto = localPhotoMap[serverPhoto.fileName]
            if (localPhoto != null) {
                // 照片存在，使用服务器的时间戳（或本地时间戳）
                result.add(serverPhoto.copy(uploadTime = localPhoto.uploadTime))
            } else {
                // 服务器有但本地没有的照片（应该已经下载了）
                // 检查是否已下载
                val downloadedFile = File(
                    context.getExternalFilesDir(null),
                    "downloaded_photos/${serverPhoto.fileName}"
                )
                if (downloadedFile.exists()) {
                    // 已下载，添加到结果
                    result.add(serverPhoto)
                } else {
                    // 未下载，暂时跳过（应该在下载循环中处理）
                    Log.d("DashboardViewModel", "照片未下载: ${serverPhoto.fileName}")
                }
            }
        }

        // 在适当的位置插入本地新增的照片
        // 这里简单做法：添加到末尾
        result.addAll(localOnlyPhotos)

        return result
    }

    /**
     * 手动生成JSON（用于调试）
     */
    fun generateJsonManually() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val allDbPhotos = photoDao.getAllPhotosSync()
                Log.d("DashboardViewModel", "数据库中有 ${allDbPhotos.size} 张照片")

                val fullJson = generateFullJson(allDbPhotos)

                if (fullJson != null) {
                    localJsonManager.replaceJson(fullJson.weeks, fullJson.totalPhotos)
                    Log.d("DashboardViewModel", "手动生成JSON成功，共 ${fullJson.totalPhotos} 张照片")

                    // 验证
                    val savedJson = localJsonManager.loadJson()
                    if (savedJson != null) {
                        Log.d("DashboardViewModel", "验证成功：JSON中有 ${savedJson.totalPhotos} 张照片")
                    }
                }
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "手动生成JSON失败", e)
            } finally {
                _isLoading.value = false
            }
        }
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

    data class SyncProgress(
        val current: Int,
        val total: Int,
        val message: String
    )

    // 辅助方法
    private fun getFileNameFromUri(uri: Uri): String {
        var fileName = "unknown.jpg"
        try {
            // 方法1: 从ContentResolver查询
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        fileName = it.getString(nameIndex)
                    }
                }
            }

            // 方法2: 如果方法1失败，尝试从路径获取
            if (fileName == "unknown.jpg") {
                val path = uri.path
                if (path != null) {
                    fileName = path.substringAfterLast("/")
                }
            }
        } catch (e: Exception) {
            Log.e("DashboardViewModel", "获取文件名失败", e)
            // 降级方案：从URI的最后一段获取
            fileName = uri.lastPathSegment ?: "unknown.jpg"
        }
        return fileName
    }

    private fun extractWeekNum(weekName: String): Int {
        val regex = Regex("第 (\\d+) 周")
        return regex.find(weekName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

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