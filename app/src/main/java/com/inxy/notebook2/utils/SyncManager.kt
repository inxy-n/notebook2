package com.inxy.notebook2.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.inxy.notebook2.data.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class SyncManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "http://gt.inxy.xyz:443"
    private val downloadDir: File by lazy {
        File(context.getExternalFilesDir(null), "downloaded_photos").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    private val gson: Gson = GsonBuilder().create()

    /**
     * 从服务器下载JSON文件
     */
    suspend fun downloadServerJson(): PhotosJson? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/photos")  // 根据实际API调整
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val jsonString = response.body?.string() ?: return@withContext null
                return@withContext gson.fromJson(jsonString, PhotosJson::class.java)
            } else {
                Log.e("SyncManager", "下载$baseUrl/api/photos JSON失败: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e("SyncManager", "下载JSON异常", e)
            null
        }
    }

    /**
     * 从服务器JSON中提取所有照片文件名
     */
    fun extractFileNamesFromJson(serverJson: PhotosJson): Set<String> {
        val fileNames = mutableSetOf<String>()
        serverJson.weeks.forEach { week ->
            week.courses.forEach { course ->
                course.photos.forEach { photo ->
                    fileNames.add(photo.fileName)
                }
            }
        }
        return fileNames
    }

    /**
     * 下载单张照片
     */
    suspend fun downloadPhoto(fileName: String): PhotoEntity? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/photos/$fileName")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val file = File(downloadDir, fileName)
                response.body?.byteStream()?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                PhotoEntity(
                    uri = Uri.fromFile(file).toString(),
                    fileName = fileName,
                    filePath = file.absolutePath,
                    dateAdded = System.currentTimeMillis() / 1000,
                    size = file.length(),
                    source = "downloaded",
                    isSynced = true
                )
            } else {
                Log.e("SyncManager", "下载照片失败: $fileName, code: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e("SyncManager", "下载照片异常: $fileName", e)
            null
        }
    }
}