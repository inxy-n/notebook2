package com.inxy.notebook2.ui.home

import android.net.Uri
import android.util.Log
import com.inxy.notebook2.data.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit

class ProcessCourses {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // 学期开始日期
    private val termStartDate: LocalDate = LocalDate.of(2026, 3, 2)

    // 时间格式化器
    private val timeFormatter = DateTimeFormatter.ofPattern("H:mm")
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.getDefault())

    /**
     * 从网络下载并处理课表，然后根据照片数据生成周课程列表
     * @param photoEntities 从数据库读取的照片列表
     * @return 处理好的 Week 列表
     */
    suspend fun processPhotoData(photoEntities: List<PhotoEntity>): MutableList<Week> {
        // 1. 先从网络下载课表
        val scheduleResult = fetchScheduleFromNetwork()

        // 2. 根据课表处理照片
        return if (scheduleResult.isNotEmpty()) {
            // 使用网络课表分类照片
            processWithSchedule(photoEntities, scheduleResult)
        } else {
            Log.w("ProcessCourses", "网络课表加载失败，使用默认分组")
            // 网络失败，使用默认分组
            createDefaultWeeks(photoEntities.map { Uri.parse(it.uri) })
        }
    }

    /**
     * 从网络下载课表
     */
    private suspend fun fetchScheduleFromNetwork(): List<CourseSession> = withContext(Dispatchers.IO) {
        return@withContext try {
            val request = Request.Builder()
                .url("https://inxy.xyz/notebook/courselist/schedule.csv")
                .addHeader("Cache-Control", "no-cache")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e("ProcessCourses", "网络请求失败: ${response.code}")
                return@withContext emptyList()
            }

            response.body?.byteStream()?.bufferedReader()?.use { reader ->
                val csvContent = reader.readText()
                Log.d("ProcessCourses", "下载的CSV内容: $csvContent")
                parseCsvToCourseSessions(csvContent.lines())
            } ?: emptyList()

        } catch (e: Exception) {
            Log.e("ProcessCourses", "下载课表失败", e)
            emptyList()
        }
    }

    /**
     * 解析CSV到课程会话
     * CSV格式: course_id,course_name,course_type,week,course_start,course_end
     */
    private fun parseCsvToCourseSessions(lines: List<String>): List<CourseSession> {
        val sessions = mutableListOf<CourseSession>()

        // 跳过表头
        lines.drop(1).forEachIndexed { index, line ->
            try {
                if (line.isBlank()) return@forEachIndexed

                val parts = line.split(",").map { it.trim() }
                if (parts.size >= 6) {
                    val courseId = parts[0]
                    val courseName = parts[1]
                    val courseType = parts[2]
                    val dayOfWeek = parts[3].toInt()  // 注意：week列实际表示星期几
                    val startTime = parts[4]
                    val endTime = parts[5]

                    sessions.add(CourseSession(
                        courseId = courseId,
                        courseName = courseName,
                        courseType = courseType,
                        dayOfWeek = dayOfWeek,
                        startTime = startTime,
                        endTime = endTime
                    ))

                    Log.d("ProcessCourses", "解析课程: $courseName, 星期$dayOfWeek, $startTime-$endTime")
                } else {
                    Log.w("ProcessCourses", "CSV行格式不正确: $line")
                }
            } catch (e: Exception) {
                Log.e("ProcessCourses", "解析行失败: $line", e)
            }
        }
        return sessions
    }

    /**
     * 使用课表处理照片
     */
    private fun processWithSchedule(
        photoEntities: List<PhotoEntity>,
        schedule: List<CourseSession>
    ): MutableList<Week> {
        // 按周组织的照片：周数 -> 照片列表
        val weekMap = mutableMapOf<Int, MutableList<Pair<Uri, LocalDateTime>>>()

        // 第一步：解析所有照片的时间，按周分组
        photoEntities.forEach { photo ->
            val fileName = photo.fileName
            val dateTime = extractDateTimeFromFilename(fileName)

            if (dateTime != null) {
                val uri = Uri.parse(photo.uri)
                val weekNum = calculateWeekNumber(dateTime)
                if (weekNum > 0 && weekNum <= 30) { // 限制最大周数，避免异常数据
                    weekMap.getOrPut(weekNum) { mutableListOf() }.add(Pair(uri, dateTime))
                }
            } else {
                Log.d("ProcessCourses", "无法解析文件名: $fileName")
            }
        }

        // 第二步：按周组织数据
        val weeks = mutableListOf<Week>()
        val sortedWeeks = weekMap.keys.sorted()

        sortedWeeks.forEach { weekNum ->
            val weekPhotos = weekMap[weekNum] ?: emptyList()

            // 将本周的照片按课程分组（区分不同日期）
            val courseGroups = groupPhotosByCourseWithDay(weekPhotos, schedule, weekNum)

            // 创建课程列表
            val courses = mutableListOf<Course>()

            // 添加课程分组，按星期排序
            if (courseGroups.isNotEmpty()) {
                // 按星期排序
                val sortedGroups = courseGroups.entries.sortedBy { it.key.dayOfWeek }

                sortedGroups.forEach { (session, uris) ->
                    // 获取星期几的中文表示
                    val dayChinese = getChineseDayOfWeek(session.dayOfWeek)
                    courses.add(
                        Course(
                            name = "${session.courseName} ${session.courseType} $dayChinese ",
                            images = uris,
                            courseType = session.courseType

                        )
                    )
                }
            }

            // 如果课程分组为空，将所有照片放在一个默认课程中

            /*if (courses.isEmpty()) {
                courses.add(
                    Course(
                        name = "其他照片 ",
                        images = weekPhotos.map { it.first },
                        courseType = "L"
                    )
                )
            }*/
            if (!courses.isEmpty()) {
                weeks.add(
                    Week(
                        name = "第 $weekNum 周",
                        courses = courses,
                        expanded = false
                    )
                )
            }
        }

        return weeks
    }

    /**
     * 获取星期几的中文表示
     */
    private fun getChineseDayOfWeek(day: Int): String {
        return when (day) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            7 -> "周日"
            else -> "周$day"
        }
    }

    /**
     * 将照片按课程和日期分组
     * 返回: Map<课程会话, 照片列表>，每个课程会话对应特定星期几的特定时间段
     */
    private fun groupPhotosByCourseWithDay(
        weekPhotos: List<Pair<Uri, LocalDateTime>>,
        schedule: List<CourseSession>,
        weekNum: Int
    ): Map<CourseSession, List<Uri>> {
        val courseMap = mutableMapOf<CourseSession, MutableList<Uri>>()

        weekPhotos.forEach { (uri, dateTime) ->
            val session = findMatchingCourse(uri, dateTime, schedule)

            if (session != null) {
                // 直接使用CourseSession作为键，区分不同日期的同一课程
                courseMap.getOrPut(session) { mutableListOf() }.add(uri)
            } else {
                Log.d("ProcessCourses", "未找到匹配课程: $dateTime")
            }
        }

        return courseMap
    }

    /**
     * 查找匹配的课程（考虑星期几和时间段）
     */
    private fun findMatchingCourse(
        uri: Uri,
        dateTime: LocalDateTime,
        schedule: List<CourseSession>
    ): CourseSession? {
        val photoDayOfWeek = dateTime.dayOfWeek.value
        val photoTime = dateTime.toLocalTime()

        // 查找完全匹配星期几和时间段的课程
        return schedule.firstOrNull { session ->
            session.dayOfWeek == photoDayOfWeek && isTimeInRange(photoTime, session)
        }
    }

    /**
     * 判断时间是否在课程时间段内（考虑15分钟容差）
     */
    private fun isTimeInRange(photoTime: LocalTime, session: CourseSession): Boolean {
        return try {
            val startTime = parseTimeString(session.startTime)
            val endTime = parseTimeString(session.endTime)

            // 允许照片时间比课程开始时间早15分钟，或比结束时间晚15分钟
            val adjustedStart = startTime.minusMinutes(1)
            val adjustedEnd = endTime.plusMinutes(1)

            (photoTime.isAfter(adjustedStart) || photoTime.equals(adjustedStart)) &&
                    (photoTime.isBefore(adjustedEnd) || photoTime.equals(adjustedEnd))
        } catch (e: Exception) {
            Log.e("ProcessCourses", "时间解析错误", e)
            false
        }
    }

    /**
     * 解析时间字符串
     */
    private fun parseTimeString(timeStr: String): LocalTime {
        val parts = timeStr.split(":")
        return LocalTime.of(parts[0].toInt(), parts[1].toInt())
    }

    /**
     * 从文件名解析日期时间
     * 支持格式: IMG_20251013_143022.jpg 或 20251013_143022.jpg
     */
    private fun extractDateTimeFromFilename(filename: String): LocalDateTime? {
        // 匹配 IMG_20251013_143022.jpg 或 20251013_143022.jpg
        val regex = Regex("(?:IMG_)?(\\d{8})_(\\d{6})\\.jpg$", RegexOption.IGNORE_CASE)
        val matchResult = regex.find(filename)

        return matchResult?.let {
            val datePart = it.groupValues[1]  // 20251013
            val timePart = it.groupValues[2]  // 143022
            try {
                LocalDateTime.parse(datePart + timePart, dateTimeFormatter)
            } catch (e: Exception) {
                Log.e("ProcessCourses", "日期解析失败: $datePart$timePart", e)
                null
            }
        }
    }

    /**
     * 计算照片属于第几周
     */
    private fun calculateWeekNumber(photoDateTime: LocalDateTime): Int {
        val photoDate = photoDateTime.toLocalDate()
        val daysBetween = ChronoUnit.DAYS.between(termStartDate, photoDate)

        // 如果照片日期在学期开始之前，返回0（表示不属于任何周）
        if (daysBetween < 0) {
            return 0
        }

        return (daysBetween / 7).toInt() + 1
    }

    /**
     * 创建默认的周分组（当没有课表时使用）
     */
    private fun createDefaultWeeks(uris: List<Uri>): MutableList<Week> {
        val weeks = mutableListOf<Week>()

        if (uris.isEmpty()) {
            return weeks
        }

        // 按时间排序
        val sortedUris = uris.map { uri ->
            val fileName = uri.lastPathSegment ?: ""
            val dateTime = extractDateTimeFromFilename(fileName)
            Pair(uri, dateTime)
        }.sortedBy { it.second ?: LocalDateTime.MIN }

        // 简单分组：每50张为一周
        val chunked = sortedUris.chunked(50)

        chunked.forEachIndexed { index, chunk ->
            val photoUris = chunk.map { it.first }
            val courses = mutableListOf<Course>()

            // 每个周再分为两组课程
            val course1Size = minOf(25, photoUris.size)
            val course1Uris = photoUris.take(course1Size)
            val course2Uris = if (photoUris.size > 25) photoUris.drop(25) else emptyList()

            if (course1Uris.isNotEmpty()) {
                courses.add(Course("课程1 (${course1Uris.size}张)", course1Uris, courseType = "L"))
            }

            if (course2Uris.isNotEmpty()) {
                courses.add(Course("课程2 (${course2Uris.size}张)", course2Uris,courseType = "L"))
            }

            weeks.add(
                Week(
                    name = "第${index + 1}周 (${photoUris.size}张)",
                    courses = courses,
                    expanded = false
                )
            )
        }

        return weeks
    }

    /**
     * 课程会话数据类 - 重写equals和hashCode以确保正确作为Map的键
     */
    data class CourseSession(
        val courseId: String,
        val courseName: String,
        val courseType: String,
        val dayOfWeek: Int,
        val startTime: String,
        val endTime: String
    ) {
        // 重写equals和hashCode以确保相同课程不同时间被视为不同的键
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CourseSession

            if (courseId != other.courseId) return false
            if (courseName != other.courseName) return false
            if (courseType != other.courseType) return false
            if (dayOfWeek != other.dayOfWeek) return false
            if (startTime != other.startTime) return false
            if (endTime != other.endTime) return false

            return true
        }

        override fun hashCode(): Int {
            var result = courseId.hashCode()
            result = 31 * result + courseName.hashCode()
            result = 31 * result + courseType.hashCode()
            result = 31 * result + dayOfWeek
            result = 31 * result + startTime.hashCode()
            result = 31 * result + endTime.hashCode()
            return result
        }
    }
}