package com.inxy.notebook2.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.inxy.notebook2.data.PhotoEntity
import com.inxy.notebook2.ui.home.ProcessCourses
import com.inxy.notebook2.ui.home.Week
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class UploadService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "http://gt.inxy.xyz:443"
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
     * 上传JSON文件 - 使用本地已生成的JSON
     */
    suspend fun uploadLocalJson(
        callback: UploadCallback
    ) = withContext(Dispatchers.IO) {
        try {
            callback.onProgress(0, 1, "正在读取本地JSON文件...")

            // 1. 获取本地JSON文件
            val localJsonManager = LocalJsonManager(context)
            val jsonFile = localJsonManager.jsonFile

            if (!jsonFile.exists()) {
                callback.onError("本地JSON文件不存在，请先在Dashboard中扫描照片")
                return@withContext
            }

            // 2. 上传JSON文件
            callback.onProgress(0, 1, "正在上传JSON文件...")
            uploadJsonFile(jsonFile, callback)

        } catch (e: Exception) {
            Log.e("UploadService", "上传JSON失败", e)
            callback.onError("上传JSON失败: ${e.message}")
        }
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
     * 批量上传照片 - 基于JSON分类的照片
     */
    suspend fun uploadPhotosBasedOnJson(
        photoEntities: List<PhotoEntity>,
        callback: UploadCallback
    ) = withContext(Dispatchers.IO) {
        try {
            callback.onProgress(0, 1, "正在分析照片分类...")

            // 1. 处理照片数据，生成按周和课程组织的结构
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
     * 构建照片JSON
     */
    private fun buildPhotosJson(
        weeks: List<Week>,
        allPhotos: List<PhotoEntity>
    ): JSONObject {
        val rootJson = JSONObject()
        val weeksArray = JSONArray()

        // 重新计算实际照片总数
        var actualTotalPhotos = 0

        weeks.forEachIndexed { index, week ->
            val weekJson = JSONObject()
            val weekNum = index + 1

            // 计算本周实际照片数
            val weekPhotosCount = week.courses.sumOf { it.images.size }
            actualTotalPhotos += weekPhotosCount

            weekJson.put("weekNum", weekNum)
            weekJson.put("weekName", "第 $weekNum 周")  // 使用实际数量

            val coursesArray = JSONArray()

            week.courses.forEach { course ->
                val courseJson = JSONObject()
                courseJson.put("courseId", extractCourseId(course.name))
                courseJson.put("courseName", extractCourseName(course.name))
                courseJson.put("courseType", extractCourseType(course.name))
                courseJson.put("dayOfWeek", extractDayOfWeek(course.name))

                val photosArray = JSONArray()

                course.images.forEach { uri ->
                    val photoJson = JSONObject()
                    val fileName = getFileNameFromUri(uri)
                    photoJson.put("fileName", fileName)
                    photoJson.put("uri", uri.toString())  // 保存完整URI
                    photoJson.put("uploadTime", LocalDateTime.now().format(jsonFormatter))

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
        rootJson.put("totalPhotos", actualTotalPhotos)  // 使用实际计算的总数

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
                .setType(MultipartBody.FORM)
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
        }
    }
    /**
     * 检查文件是否已在服务器存在
     */
    /**
     * 检查文件是否已在服务器存在
     */
    private suspend fun checkFileExistsOnServer(fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("UploadService", "正在检查文件是否存在: $fileName")

            // 使用 GET 请求，但设置较小的超时并只检查状态码
            val request = Request.Builder()
                .url("$baseUrl/checkphotos/$fileName")
                .get()  // 改用 GET 请求
                .build()

            val response = client.newCall(request).execute()

            // 检查响应码
            val exists = response.isSuccessful  // 2xx 状态码表示文件存在
            val code = response.code

            Log.d("UploadService", "检查结果 - 状态码: $code, 文件${if (exists) "存在" else "不存在"}: $fileName")

            response.close()
            return@withContext exists
        } catch (e: Exception) {
            Log.e("UploadService", "检查文件是否存在失败: $fileName", e)
            false
        }
    }

    /**
     * 上传所有照片 - 基于本地JSON中列出的照片（带存在检查）
     */
    suspend fun uploadPhotosBasedOnLocalJson(
        callback: UploadCallback
    ) = withContext(Dispatchers.IO) {
        try {
            callback.onProgress(0, 1, "正在读取本地JSON文件...")

            // 1. 获取本地JSON
            val localJsonManager = LocalJsonManager(context)
            val localJson = localJsonManager.loadJson()

            if (localJson == null) {
                callback.onError("本地JSON文件不存在，请先在Dashboard中扫描照片")
                return@withContext
            }

            // 2. 从本地JSON中提取所有需要上传的照片信息
            val photosToCheck = mutableListOf<Pair<String, String>>() // fileName, uri

            localJson.weeks.forEach { week ->
                week.courses.forEach { course ->
                    course.photos.forEach { photo ->
                        photosToCheck.add(Pair(photo.fileName, photo.uri))
                    }
                }
            }

            callback.onProgress(0, photosToCheck.size, "正在检查服务器上已有的文件...")

            // 3. 检查每个文件是否已在服务器存在
            val filesToUpload = mutableListOf<Pair<String, String>>()

            photosToCheck.forEachIndexed { index, (fileName, uriString) ->
                callback.onProgress(index + 1, photosToCheck.size, "检查第 ${index + 1}/${photosToCheck.size}: $fileName")

                val exists = checkFileExistsOnServer(fileName)
                if (!exists) {
                    filesToUpload.add(Pair(fileName, uriString))
                    Log.d("UploadService", "文件需要上传: $fileName")
                } else {
                    Log.d("UploadService", "文件已存在，跳过: $fileName")
                }
            }

            if (filesToUpload.isEmpty()) {
                callback.onSuccess("所有文件已存在于服务器，无需上传")
                return@withContext
            }

            callback.onProgress(0, filesToUpload.size, "准备上传 ${filesToUpload.size} 张新照片...")

            // 4. 上传需要上传的文件
            var successCount = 0
            var failCount = 0

            filesToUpload.forEachIndexed { index, (fileName, uriString) ->
                try {
                    callback.onProgress(index + 1, filesToUpload.size, "正在上传第 ${index + 1}/${filesToUpload.size}: $fileName")

                    // 创建临时的PhotoEntity
                    val uri = Uri.parse(uriString)
                    val photo = PhotoEntity(
                        uri = uriString,
                        fileName = fileName,
                        filePath = "",
                        dateAdded = System.currentTimeMillis() / 1000,
                        size = 0,
                        source = "system",
                        isSynced = false
                    )

                    val result = uploadSinglePhoto(photo)
                    if (result) {
                        successCount++
                        Log.d("UploadService", "上传成功: $fileName")
                    } else {
                        failCount++
                        Log.e("UploadService", "上传失败: $fileName")
                    }

                } catch (e: Exception) {
                    Log.e("UploadService", "上传异常: $fileName", e)
                    failCount++
                }
            }

            // 5. 返回结果
            val resultMessage = when {
                failCount == 0 -> "全部上传成功！共 $successCount 张"
                successCount == 0 -> "全部上传失败！共 $failCount 张"
                else -> "上传完成：成功 $successCount 张，失败 $failCount 张（已跳过 ${photosToCheck.size - filesToUpload.size} 张已存在的文件）"
            }

            callback.onSuccess(resultMessage)

        } catch (e: Exception) {
            Log.e("UploadService", "批量上传失败", e)
            callback.onError("批量上传失败: ${e.message}")
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
                .setType(MultipartBody.FORM)
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

    // 辅助方法
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