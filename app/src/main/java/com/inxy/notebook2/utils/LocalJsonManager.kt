package com.inxy.notebook2.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// 修改 PhotoInJson，添加 uri 字段
data class PhotoInJson(
    val fileName: String,
    val uri: String,  // 添加uri字段
    val uploadTime: String
)

data class CourseInJson(
    val courseId: String,
    val courseName: String,
    val courseType: String,
    val dayOfWeek: Int,
    val photos: List<PhotoInJson>
)

data class WeekInJson(
    val weekNum: Int,
    val weekName: String,
    val courses: List<CourseInJson>
)

data class PhotosJson(
    val weeks: List<WeekInJson>,
    val lastUpdated: String,
    val totalPhotos: Int
)
class LocalJsonManager(private val context: Context) {

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    // 这是一个属性，Kotlin会自动生成 getJsonFile() 方法
    val jsonFile: File
        get() = File(context.filesDir, "allphoto.json")

    /**
     * 保存JSON到本地
     */
    fun saveJson(photosJson: PhotosJson) {
        try {
            val jsonString = gson.toJson(photosJson)
            jsonFile.writeText(jsonString)
            android.util.Log.d("LocalJsonManager", "JSON已保存到: ${jsonFile.absolutePath}")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 读取本地JSON
     */
    fun loadJson(): PhotosJson? {
        return try {
            if (!jsonFile.exists()) {
                return null
            }
            val jsonString = jsonFile.readText()
            val type = object : TypeToken<PhotosJson>() {}.type
            gson.fromJson(jsonString, type)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    fun checkJsonFile(): String {
        return "JSON文件路径: ${jsonFile.absolutePath}, 是否存在: ${jsonFile.exists()}"
    }
    /**
     * 完全替换JSON（不是增量更新）
     */
    fun replaceJson(newWeeks: List<WeekInJson>, totalPhotos: Int) {
        val updatedJson = PhotosJson(
            weeks = newWeeks,
            lastUpdated = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            totalPhotos = totalPhotos
        )
        saveJson(updatedJson)
    }

    /**
     * 获取所有照片文件名
     */
    fun getAllPhotoFileNames(): Set<String> {
        val json = loadJson() ?: return emptySet()
        return json.weeks.flatMap { week ->
            week.courses.flatMap { course ->
                course.photos.map { it.fileName }
            }
        }.toSet()
    }
    /**
     * 更新JSON（增量更新）
     */
    fun updateJson(newWeeks: List<WeekInJson>) {
        val existingJson = loadJson()

        val mergedWeeks = if (existingJson != null) {
            mergeWeeks(existingJson.weeks, newWeeks)
        } else {
            newWeeks
        }

        val updatedJson = PhotosJson(
            weeks = mergedWeeks,
            lastUpdated = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            totalPhotos = mergedWeeks.sumOf { week ->
                week.courses.sumOf { course -> course.photos.size }
            }
        )

        saveJson(updatedJson)
        Log.d("LocalJsonManager", "JSON增量更新完成，总照片数: ${updatedJson.totalPhotos}")
    }

    /**
     * 合并周数据（保留旧的，添加新的）
     */
    private fun mergeWeeks(oldWeeks: List<WeekInJson>, newWeeks: List<WeekInJson>): List<WeekInJson> {
        val weekMap = oldWeeks.associateBy { it.weekNum }.toMutableMap()

        newWeeks.forEach { newWeek ->
            val existingWeek = weekMap[newWeek.weekNum]
            if (existingWeek != null) {
                // 合并课程
                val mergedCourses = mergeCourses(existingWeek.courses, newWeek.courses)
                weekMap[newWeek.weekNum] = existingWeek.copy(courses = mergedCourses)
            } else {
                // 新增周
                weekMap[newWeek.weekNum] = newWeek
            }
        }

        return weekMap.values.sortedBy { it.weekNum }
    }

    /**
     * 合并课程（保留旧的，添加新的）
     */
    private fun mergeCourses(oldCourses: List<CourseInJson>, newCourses: List<CourseInJson>): List<CourseInJson> {
        val courseMap = oldCourses.associateBy {
            "${it.courseId}_${it.dayOfWeek}"
        }.toMutableMap()

        newCourses.forEach { newCourse ->
            val key = "${newCourse.courseId}_${newCourse.dayOfWeek}"
            val existingCourse = courseMap[key]

            if (existingCourse != null) {
                // 合并照片
                val existingFileNames = existingCourse.photos.map { it.fileName }.toSet()
                val newPhotos = newCourse.photos.filter { !existingFileNames.contains(it.fileName) }
                val mergedPhotos = existingCourse.photos + newPhotos
                courseMap[key] = existingCourse.copy(photos = mergedPhotos)
            } else {
                // 新增课程
                courseMap[key] = newCourse
            }
        }

        return courseMap.values.toList()
    }
    // 删除这个方法，因为 Kotlin 已经自动生成了
    // fun getJsonFile(): File { ... }
}