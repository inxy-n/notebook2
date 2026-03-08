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
import androidx.recyclerview.widget.LinearLayoutManager
import com.inxy.notebook2.databinding.FragmentHomeBinding

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

    private fun loadImages() {
        try {
            val images = mutableListOf<Uri>()

            val projection = arrayOf(MediaStore.Images.Media._ID)

            val cursor = requireContext().contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )

            cursor?.use {
                val column = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

                var count = 0
                while (it.moveToNext() && count < 100) {
                    val id = it.getLong(column)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    images.add(uri)
                    count++
                }
            }

            // 打印日志查看是否获取到图片
            if (images.isEmpty()) {
                android.util.Log.d("HomeFragment", "没有找到图片")
                // 可以显示一个提示
                Toast.makeText(requireContext(), "没有找到图片", Toast.LENGTH_SHORT).show()
            } else {
                android.util.Log.d("HomeFragment", "找到 ${images.size} 张图片")
            }
            Log.e("TAG", "loadImages: $images", )

            // 处理数据分组
            processAndDisplayImages(images)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "加载图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
            adapter.submitList(emptyList())
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