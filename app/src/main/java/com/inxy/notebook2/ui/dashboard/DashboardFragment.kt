package com.inxy.notebook2.ui.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.inxy.notebook2.adapter.PhotoAdapter  // 确保导入正确的适配器
import com.inxy.notebook2.data.PhotoEntity
import com.inxy.notebook2.databinding.FragmentDashboardBinding
import com.inxy.notebook2.ui.dashboard.UploadService
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: DashboardViewModel
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var fabRefresh: FloatingActionButton
    private lateinit var fabUpload: FloatingActionButton  // 添加上传按钮引用
    private lateinit var photoAdapter: PhotoAdapter
    private lateinit var uploadService: UploadService  // 这个需要在初始化
    private var uploadDialog: AlertDialog? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            refreshPhotos()
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews()
        // 在这里初始化 UploadService
        initUploadService()
        initViewModel()
        setupRecyclerView()
        setupObservers()
        setupClickListeners()

        checkPermissionAndLoad()
    }

    private fun initViews() {
        recyclerView = binding.recyclerView
        progressBar = binding.progressBar
        tvEmpty = binding.tvEmpty
        fabRefresh = binding.fabRefresh
        fabUpload = binding.fabUpload  // 确保你的布局中有这个id
    }

    private fun initUploadService() {
        uploadService = UploadService(requireContext())
    }
    private fun initViewModel() {
        viewModel = ViewModelProvider(this)[DashboardViewModel::class.java]
    }

    private fun setupRecyclerView() {
        // 使用GridLayoutManager，每行3列
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)

        // 初始化适配器（空列表）
        photoAdapter = PhotoAdapter(emptyList()) { photo ->
            showPhotoDetailsDialog(photo)
        }
        recyclerView.adapter = photoAdapter
    }

    private fun setupObservers() {
        // 观察照片列表变化
        viewModel.photos.observe(viewLifecycleOwner) { photos: List<PhotoEntity> ->
            // 更新现有适配器的数据，而不是创建新适配器
            photoAdapter.updateData(photos)
            updateEmptyState(photos.isEmpty())
        }

        // 观察加载状态
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading: Boolean ->
            progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            fabRefresh.isEnabled = !isLoading
        }

        // 观察错误信息
        viewModel.error.observe(viewLifecycleOwner) { errorMessage: String? ->
            errorMessage?.let {
                Snackbar.make(recyclerView, it, Snackbar.LENGTH_LONG)
                    .setAction("重试") { refreshPhotos() }
                    .show()
                viewModel.clearError()
            }
        }
    }

    private fun setupClickListeners() {
        fabRefresh.setOnClickListener {
            refreshPhotos()
        }

        // 添加上传按钮（你需要先在布局中添加一个上传FAB）
        binding.fabUpload.setOnClickListener {
            showUploadOptionsDialog()
        }
    }

    private fun checkPermissionAndLoad() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED -> {
                refreshPhotos()
            }
            else -> {
                requestPermissionLauncher.launch(permission)
            }
        }
    }

    /**
     * 显示上传选项对话框
     */
    private fun showUploadOptionsDialog() {
        val options = arrayOf("上传所有照片", "仅上传JSON文件")
        AlertDialog.Builder(requireContext())
            .setTitle("选择上传方式")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> uploadAllPhotos()
                    1 -> uploadJsonOnly()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 上传所有照片
     */
    /**
     * 上传所有照片 - 现在只上传JSON中分类的照片
     */
    private fun uploadAllPhotos() {
        val photos = viewModel.photos.value
        if (photos.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "没有可上传的照片", Toast.LENGTH_SHORT).show()
            return
        }

        showUploadProgressDialog("正在分析照片分类...")

        lifecycleScope.launch {
            // 使用新方法 uploadPhotosBasedOnJson
            uploadService.uploadPhotosBasedOnJson(photos, object : UploadService.UploadCallback {
                override fun onProgress(current: Int, total: Int, message: String) {
                    requireActivity().runOnUiThread {
                        updateUploadProgress(current, total, message)
                    }
                }

                override fun onSuccess(response: String) {
                    requireActivity().runOnUiThread {
                        uploadDialog?.dismiss()
                        AlertDialog.Builder(requireContext())
                            .setTitle("上传成功")
                            .setMessage(response)
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }

                override fun onError(error: String) {
                    requireActivity().runOnUiThread {
                        uploadDialog?.dismiss()
                        Snackbar.make(recyclerView, error, Snackbar.LENGTH_LONG).show()
                    }
                }
            })
        }
    }

    private fun uploadJsonOnly() {
        val photos = viewModel.photos.value
        if (photos.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "没有可上传的照片", Toast.LENGTH_SHORT).show()
            return
        }

        showUploadProgressDialog("正在生成JSON文件...")

        lifecycleScope.launch {  // 同样使用 lifecycleScope
            uploadService.generateAndUploadJson(photos, object : UploadService.UploadCallback {
                override fun onProgress(current: Int, total: Int, message: String) {
                    requireActivity().runOnUiThread {
                        updateUploadProgress(current, total, message)
                    }
                }

                override fun onSuccess(response: String) {
                    requireActivity().runOnUiThread {
                        uploadDialog?.dismiss()
                        AlertDialog.Builder(requireContext())
                            .setTitle("JSON上传成功")
                            .setMessage(response)
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }

                override fun onError(error: String) {
                    requireActivity().runOnUiThread {
                        uploadDialog?.dismiss()
                        Snackbar.make(recyclerView, error, Snackbar.LENGTH_LONG).show()
                    }
                }
            })
        }
    }

    /**
     * 显示上传进度对话框
     */
    private fun showUploadProgressDialog(initialMessage: String) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("上传中")
        builder.setMessage(initialMessage)
        builder.setCancelable(false)

        uploadDialog = builder.create()
        uploadDialog?.show()
    }

    /**
     * 更新上传进度
     */
    private fun updateUploadProgress(current: Int, total: Int, message: String) {
        uploadDialog?.setMessage(message)
        if (current == total) {
            uploadDialog?.setMessage("上传完成，正在处理...")
        }
    }

    private fun refreshPhotos() {
        viewModel.refreshPhotos()
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("需要权限")
            .setMessage("需要存储权限才能读取照片。请到设置中授予权限。")
            .setPositiveButton("去设置") { _, _ ->
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${requireContext().packageName}")
                )
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPhotoDetailsDialog(photo: PhotoEntity) {
        val message = """
            文件名: ${photo.fileName}
            添加时间: ${viewModel.formatDate(photo.dateAdded)}
            文件大小: ${formatFileSize(photo.size)}
            路径: ${photo.filePath}
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("照片详情")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .setNegativeButton("删除记录") { _, _ ->
                showDeleteConfirmationDialog(photo)
            }
            .show()
    }

    private fun showDeleteConfirmationDialog(photo: PhotoEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除记录")
            .setMessage("确定要删除这张照片的记录吗？这不会删除实际的照片文件。")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deletePhoto(photo.uri)
                Toast.makeText(requireContext(), "记录已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "${size / (1024 * 1024 * 1024)} GB"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}