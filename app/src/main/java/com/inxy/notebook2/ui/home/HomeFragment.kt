package com.inxy.notebook2.ui.home

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.inxy.notebook2.data.PhotoDatabase
import com.inxy.notebook2.data.PhotoEntity
import com.inxy.notebook2.databinding.FragmentHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.util.logging.Handler

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    // 在 HomeFragment 中
    private lateinit var adapter: WeekAdapter  // 改为 HomeWeekAdapter

// 在 onCreateView 中

    // 注册权限请求回调
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // 权限被授予，加载图片
            loadImages()
        } else {
            // 权限被拒绝
            Toast.makeText(
                requireContext(),
                "需要存储权限才能显示图片",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val homeViewModel = ViewModelProvider(this).get(HomeViewModel::class.java)

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val textView: TextView = binding.textHome
        homeViewModel.text.observe(viewLifecycleOwner) {
            textView.text = it
        }

        adapter = WeekAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        // 检查并请求权限
        checkAndRequestPermissions()

        return root
    }

    private fun checkAndRequestPermissions() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13 及以上使用 READ_MEDIA_IMAGES
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            // Android 12 及以下使用 READ_EXTERNAL_STORAGE
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                permission
            ) == PackageManager.PERMISSION_GRANTED -> {
                // 已经有权限，直接加载图片
                loadImages()
            }
            else -> {
                // 没有权限，请求权限
                requestPermissionLauncher.launch(permission)
            }
        }
    }
    // 在 HomeFragment.kt 中添加这个方法
    private suspend fun getPhotosFromDatabase(): List<PhotoEntity> {
        return try {
            val database = PhotoDatabase.getInstance(requireContext())
            // 使用同步方法查询所有照片
            database.photoDao().getAllPhotosSync()
        } catch (e: Exception) {
            Log.e("HomeFragment", "读取数据库失败", e)
            emptyList()
        }
    }
    private fun loadImages() {
        // 显示加载状态（如果您有ProgressBar）
        // binding.progressBar.visibility = View.VISIBLE

        // 使用 lifecycleScope 在后台线程执行数据库操作
        lifecycleScope.launch {
            try {
                // 在后台线程获取数据库数据
                val photos = withContext(Dispatchers.IO) {
                    getPhotosFromDatabase()
                }

                if (photos.isNotEmpty()) {
                    Log.d("HomeFragment", "从数据库读取到 ${photos.size} 张照片")

                    // 处理照片数据（也在后台线程执行，因为涉及网络请求）
                    val weeks = withContext(Dispatchers.IO) {
                        val processCourses = ProcessCourses()
                        processCourses.processPhotoData(photos)
                    }

                    // 回到主线程更新UI
                    adapter.submitList(weeks)
                    adapter.notifyDataSetChanged()

                    Log.d("HomeFragment", "处理完成，生成 ${weeks.size} 周数据")
                } else {
                    Log.d("HomeFragment", "数据库中没有照片")
                    Toast.makeText(requireContext(), "数据库中没有照片，请先在Dashboard扫描", Toast.LENGTH_SHORT).show()
                    adapter.submitList(emptyList())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "加载照片失败: ${e.message}", Toast.LENGTH_SHORT).show()
                adapter.submitList(emptyList())
            } finally {
                // 隐藏加载状态
                // binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun processAndDisplayImages(images: List<Uri>) {
        Log.d("HomeFragment", "processAndDisplayImages: 收到 ${images.size} 张图片")

        if (images.isEmpty()) {
            Log.d("HomeFragment", "images为空，提交空列表")
            adapter.submitList(emptyList())
            return
        }

        // 简单分组示例（处理可能不足50张的情况）
        val week1Size = minOf(50, images.size)
        val week1 = images.take(week1Size)
        val week2 = if (images.size > 50) images.drop(50) else emptyList()

        Log.d("HomeFragment", "week1: ${week1.size}张, week2: ${week2.size}张")

        val data = mutableListOf<Week>()

        if (week1.isNotEmpty()) {
            val course1Size = minOf(25, week1.size)
            val course1 = week1.take(course1Size)
            val course2 = if (week1.size > 25) week1.drop(25) else emptyList()

            data.add(Week(
                name = "第1周 (${week1.size}张)",
                courses = listOf(
                    Course("必修课 (${course1.size}张)", course1),
                    Course("选修课 (${course2.size}张)", course2)
                ),
                expanded = false  // 默认展开
            ))
        }

        // 添加 Week2（如果有数据）- 设置 expanded = true
        if (week2.isNotEmpty()) {
            val course1Size = minOf(25, week2.size)
            val course1 = week2.take(course1Size)
            val course2 = if (week2.size > 25) week2.drop(25) else emptyList()

            data.add(Week(
                name = "第2周 (${week2.size}张)",
                courses = listOf(
                    Course("必修课 (${course1.size}张)", course1),
                    Course("选修课 (${course2.size}张)", course2)
                ),
                expanded = false  // 默认展开
            ))
        }

        adapter.submitList(data)

        // 强制刷新
        adapter.notifyDataSetChanged()

        // 检查adapter中的数据
        android.os.Handler(Looper.getMainLooper()).postDelayed({
            Log.d("HomeFragment", "延迟检查 - adapter item count: ${adapter.itemCount}")
        }, 500)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}