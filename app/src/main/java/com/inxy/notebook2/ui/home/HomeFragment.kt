package com.inxy.notebook2.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.inxy.notebook2.data.PhotoDatabase
import com.inxy.notebook2.databinding.FragmentHomeBinding
import com.inxy.notebook2.utils.LocalJsonManager
import com.inxy.notebook2.utils.PhotoInJson
import com.inxy.notebook2.utils.PhotosJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: WeekAdapter
    private lateinit var homeViewModel: HomeViewModel
    private lateinit var localJsonManager: LocalJsonManager
    private lateinit var photoDatabase: PhotoDatabase

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        homeViewModel = ViewModelProvider(this).get(HomeViewModel::class.java)
        _binding = FragmentHomeBinding.inflate(inflater, container, false)

        initManagers()
        setupRecyclerView()

        // 加载本地JSON并显示
        loadFromLocalJson()

        return binding.root
    }

    private fun initManagers() {
        localJsonManager = LocalJsonManager(requireContext())
        photoDatabase = PhotoDatabase.getInstance(requireContext())
    }

    private fun setupRecyclerView() {
        adapter = WeekAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun loadFromLocalJson() {
        lifecycleScope.launch {
            try {
                // 1. 先检查数据库是否有数据
                val dbCount = withContext(Dispatchers.IO) {
                    photoDatabase.photoDao().getAllPhotosSync().size
                }
                Log.d("HomeFragment", "数据库中有 $dbCount 张照片")

                // 2. 加载JSON
                val photosJson = withContext(Dispatchers.IO) {
                    localJsonManager.loadJson()
                }

                if (photosJson != null) {
                    Log.d("HomeFragment", "从本地JSON加载，共 ${photosJson.totalPhotos} 张照片")
                    Log.d("HomeFragment", "JSON weeks 数量: ${photosJson.weeks.size}")

                    // 打印JSON结构
                    photosJson.weeks.forEachIndexed { weekIndex, weekInJson ->
                        Log.d("HomeFragment", "Week $weekIndex: ${weekInJson.weekName}, courses: ${weekInJson.courses.size}")
                        weekInJson.courses.forEachIndexed { courseIndex, courseInJson ->
                            Log.d("HomeFragment", "  Course $courseIndex: ${courseInJson.courseName}, photos: ${courseInJson.photos.size}")
                            courseInJson.photos.forEachIndexed { photoIndex, photoInJson ->
                                Log.d("HomeFragment", "    Photo $photoIndex: ${photoInJson.fileName}")
                            }
                        }
                    }

                    // 3. 转换JSON
                    val weeks = withContext(Dispatchers.IO) {
                        convertJsonToWeeks(photosJson)
                    }

                    Log.d("HomeFragment", "转换后生成 ${weeks.size} 周数据")

                    // 打印转换结果
                    weeks.forEachIndexed { index, week ->
                        Log.d("HomeFragment", "转换后 Week $index: ${week.name}, courses: ${week.courses.size}")
                    }

                    // 4. 更新UI
                    adapter.submitList(weeks)
                    adapter.notifyDataSetChanged()
                } else {
                    Log.d("HomeFragment", "本地JSON不存在")
                    Toast.makeText(requireContext(), "请先在Dashboard中扫描照片", Toast.LENGTH_SHORT).show()
                    adapter.submitList(emptyList())
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "加载失败", e)
                Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                adapter.submitList(emptyList())
            }
        }
    }

    /**
     * 将JSON格式转换为Week列表
     */
    private suspend fun convertJsonToWeeks(photosJson: PhotosJson): List<Week> {
        val weeks = mutableListOf<Week>()

        photosJson.weeks.forEach { weekInJson ->
            val courses = mutableListOf<Course>()
            var weekPhotoCount = 0  // 记录本周实际照片数

            weekInJson.courses.forEach { courseInJson ->
                val uris = findUrisByFileNames(courseInJson.photos)

                if (uris.isNotEmpty()) {
                    weekPhotoCount += uris.size  // 累加本周照片数

                    val dayChinese = getChineseDayOfWeek(courseInJson.dayOfWeek)
                    courses.add(
                        Course(
                            name = "${courseInJson.courseName} ${courseInJson.courseType} $dayChinese",
                            images = uris,
                            courseType = courseInJson.courseType

                        )
                    )
                }
            }

            if (courses.isNotEmpty()) {
                weeks.add(
                    Week(
                        name = "${weekInJson.weekName} ($weekPhotoCount 张)",  // 使用实际数量
                        courses = courses,
                        expanded = false
                    )
                )
            }
        }

        return weeks
    }

    /**
     * 根据文件名从数据库查找URI
     */
    /**
     * 根据文件名从数据库查找URI
     */
    private suspend fun findUrisByFileNames(photos: List<PhotoInJson>): List<Uri> {
        val uris = mutableListOf<Uri>()

        Log.d("HomeFragment", "查找 ${photos.size} 个文件的URI")

        // 一次性获取所有数据库照片，避免重复查询
        val photoEntities = withContext(Dispatchers.IO) {
            photoDatabase.photoDao().getAllPhotosSync()
        }

        Log.d("HomeFragment", "  数据库中有 ${photoEntities.size} 张照片")

        // 打印数据库中的URI供调试
        photoEntities.take(5).forEach {
            Log.d("HomeFragment", "    数据库URI: ${it.uri}")
        }

        photos.forEach { photoInJson ->
            try {
                // 方法1：尝试直接通过URI查找
                // 构造可能的URI
                val possibleUri = "content://media/external/images/media/${photoInJson.fileName}"

                // 在数据库中查找匹配的URI
                val match = photoEntities.find {
                    it.uri == possibleUri ||
                            it.uri.endsWith("/${photoInJson.fileName}") ||
                            it.fileName == photoInJson.fileName  // 也尝试用文件名匹配
                }

                if (match != null) {
                    uris.add(Uri.parse(match.uri))
                    Log.d("HomeFragment", "    在数据库中找到文件: ${photoInJson.fileName} -> ${match.uri}")
                } else {
                    // 如果在数据库找不到，检查下载目录
                    val downloadedFile = File(
                        requireContext().getExternalFilesDir(null),
                        "downloaded_photos/${photoInJson.fileName}"
                    )
                    if (downloadedFile.exists()) {
                        uris.add(Uri.fromFile(downloadedFile))
                        Log.d("HomeFragment", "    在下载目录找到文件: ${photoInJson.fileName}")
                    } else {
                        Log.d("HomeFragment", "    找不到文件: ${photoInJson.fileName}")
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "查找文件失败: ${photoInJson.fileName}", e)
            }
        }

        Log.d("HomeFragment", "总共找到 ${uris.size} 个URI")
        return uris
    }

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}