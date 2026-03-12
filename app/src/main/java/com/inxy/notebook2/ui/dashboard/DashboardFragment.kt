package com.inxy.notebook2.ui.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.inxy.notebook2.adapter.PhotoAdapter
import com.inxy.notebook2.data.PhotoEntity
import com.inxy.notebook2.databinding.FragmentDashboardBinding
import com.inxy.notebook2.utils.UploadService
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: DashboardViewModel
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var fabRefresh: FloatingActionButton
    private lateinit var fabUpload: FloatingActionButton
    private lateinit var fabSync: FloatingActionButton
    private lateinit var photoAdapter: PhotoAdapter
    private lateinit var uploadService: UploadService
    private var uploadDialog: AlertDialog? = null
    private var syncDialog: AlertDialog? = null
    private var isSyncing = false
    private var pendingUpload = false

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
        initUploadService()
        initViewModel()
        setupRecyclerView()
        setupObservers()
        setupClickListeners()

        // 只检查权限，不自动同步
        checkPermissionAndLoad()
    }

    private fun initViews() {
        recyclerView = binding.recyclerView
        progressBar = binding.progressBar
        tvEmpty = binding.tvEmpty
        fabRefresh = binding.fabRefresh
        fabUpload = binding.fabUpload
        fabSync = binding.fabSync
    }

    private fun initUploadService() {
        uploadService = UploadService(requireContext())
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(this)[DashboardViewModel::class.java]
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        photoAdapter = PhotoAdapter(emptyList()) { photo ->
            showPhotoDetailsDialog(photo)
        }
        recyclerView.adapter = photoAdapter
    }

    private fun setupObservers() {
        viewModel.photos.observe(viewLifecycleOwner) { photos: List<PhotoEntity> ->
            photoAdapter.updateData(photos)
            updateEmptyState(photos.isEmpty())
        }

        viewModel.dataUpdated.observe(viewLifecycleOwner) { updated ->
            if (!updated) {
                Toast.makeText(requireContext(), "数据更新出错", Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading: Boolean ->
            progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            fabRefresh.isEnabled = !isLoading
            fabUpload.isEnabled = !isLoading
            fabSync.isEnabled = !isLoading
        }

        viewModel.error.observe(viewLifecycleOwner) { errorMessage: String? ->
            errorMessage?.let {
                Snackbar.make(recyclerView, it, Snackbar.LENGTH_LONG)
                    .setAction("重试") { refreshPhotos() }
                    .show()
                viewModel.clearError()
            }
        }

        // 观察同步进度
        // 修改观察同步进度的逻辑
        viewModel.syncProgress.observe(viewLifecycleOwner) { progress ->
            updateSyncProgress(progress.current, progress.total, progress.message)

            // 同步完成
            if (progress.current == progress.total && progress.total > 0) {
                syncDialog?.dismiss()
                isSyncing = false

                // 如果有待处理的上传任务，等待JSON更新完成
                if (pendingUpload) {
                    pendingUpload = false

                    // 添加延迟，等待JSON文件更新完成
                    lifecycleScope.launch {
                        // 显示等待状态
                        Toast.makeText(requireContext(), "同步完成，正在更新JSON...", Toast.LENGTH_SHORT).show()

                        // 等待JSON更新（给数据库和文件系统一些时间）
                        delay(1000)

                        // 重新加载一次数据确保最新
                        viewModel.loadPhotosFromDatabase()

                        // 再等待一下确保LiveData更新
                        delay(500)

                        // 开始上传流程
                        Toast.makeText(requireContext(), "开始上传JSON和图片...", Toast.LENGTH_SHORT).show()
                        performUploadAll()
                    }
                } else {
                    Toast.makeText(requireContext(), progress.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

    }

    private fun setupClickListeners() {
        fabRefresh.setOnClickListener {
            refreshPhotos()
        }

        fabUpload.setOnClickListener {
            showUploadOptionsDialog()
        }

        fabSync.setOnClickListener {
            showSyncDialog()
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
                // 只有权限，不自动刷新
                // 让用户手动点击刷新按钮
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
        val options = arrayOf("上传所有照片", "仅上传JSON文件", "先同步再上传所有（JSON+图片）")
        AlertDialog.Builder(requireContext())
            .setTitle("选择上传方式")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> uploadAllPhotos()
                    1 -> uploadJsonOnly()
                    2 -> syncThenUploadAll()  // 先同步再上传所有
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 先同步再上传所有照片（先JSON后图片）
     */
    private fun syncThenUploadAll() {
        val photos = viewModel.photos.value
        if (photos.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "没有可上传的照片", Toast.LENGTH_SHORT).show()
            return
        }

        // 显示确认对话框
        AlertDialog.Builder(requireContext())
            .setTitle("确认操作")
            .setMessage("将先同步服务器上的照片到本地，然后按顺序执行：\n\n1. 上传JSON文件\n2. 上传所有照片\n\n整个过程可能需要一些时间，是否继续？")
            .setPositiveButton("继续") { _, _ ->
                // 开始同步
                showSyncProgressDialog()
                isSyncing = true
                pendingUpload = true
                viewModel.syncWithServer()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示同步对话框
     */
    private fun showSyncDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("同步服务器")
            .setMessage("将从服务器同步照片到本地")
            .setPositiveButton("开始同步") { _, _ ->
                startSync()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 开始同步
     */
    private fun startSync() {
        showSyncProgressDialog()
        isSyncing = true
        pendingUpload = false
        viewModel.syncWithServer()
    }

    /**
     * 显示同步进度对话框
     */
    private fun showSyncProgressDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("同步中")
        builder.setMessage("正在连接服务器...")
        builder.setCancelable(false)

        syncDialog = builder.create()
        syncDialog?.show()
    }

    /**
     * 更新同步进度
     */
    private fun updateSyncProgress(current: Int, total: Int, message: String) {
        syncDialog?.setMessage(message)
    }

    /**
     * 执行上传所有照片（先JSON后图片）
     */
    private fun performUploadAll() {
        val photos = viewModel.photos.value
        if (photos.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "没有可上传的照片", Toast.LENGTH_SHORT).show()
            return
        }

        showUploadProgressDialog("正在准备上传...")

        lifecycleScope.launch {
            // 先重新加载一次ViewModel中的数据，确保最新
            viewModel.loadPhotosFromDatabase()

            // 等待数据更新
            delay(500)

            // 第一步：先上传JSON
            uploadService.uploadLocalJson(object : UploadService.UploadCallback {
                override fun onProgress(current: Int, total: Int, message: String) {
                    requireActivity().runOnUiThread {
                        updateUploadProgress(current, total, message)
                    }
                }

                override fun onSuccess(jsonResponse: String) {
                    Log.d("DashboardFragment", "JSON上传成功: $jsonResponse")

                    // 第二步：JSON上传成功后，开始上传图片
                    requireActivity().runOnUiThread {
                        updateUploadProgress(0, 1, "JSON上传成功，开始上传图片...")

                        lifecycleScope.launch {
                            delay(500)
                            uploadImages()
                        }
                    }
                }

                override fun onError(error: String) {
                    Log.e("DashboardFragment", "JSON上传失败: $error")
                    requireActivity().runOnUiThread {
                        uploadDialog?.dismiss()
                        AlertDialog.Builder(requireContext())
                            .setTitle("上传失败")
                            .setMessage("JSON上传失败: $error\n\n图片上传已取消")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }
            })
        }
    }

    /**
     * 上传图片
     */
    private fun uploadImages() {
        lifecycleScope.launch {
            uploadService.uploadPhotosBasedOnLocalJson(object : UploadService.UploadCallback {
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
                            .setMessage("✅ JSON上传成功\n✅ 图片上传成功：$response")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }

                override fun onError(error: String) {
                    requireActivity().runOnUiThread {
                        uploadDialog?.dismiss()
                        AlertDialog.Builder(requireContext())
                            .setTitle("图片上传失败")
                            .setMessage("✅ JSON已上传成功\n❌ 但图片上传失败：$error")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }
            })
        }
    }

    /**
     * 上传所有照片 - 使用本地JSON
     */
    private fun uploadAllPhotos() {
        val photos = viewModel.photos.value
        if (photos.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "没有可上传的照片", Toast.LENGTH_SHORT).show()
            return
        }

        // 直接上传图片（不经过JSON上传）
        showUploadProgressDialog("正在读取本地JSON...")

        lifecycleScope.launch {
            uploadService.uploadPhotosBasedOnLocalJson(object : UploadService.UploadCallback {
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

    /**
     * 仅上传JSON文件 - 使用本地已生成的JSON
     */
    private fun uploadJsonOnly() {
        showUploadProgressDialog("正在读取本地JSON...")

        lifecycleScope.launch {
            uploadService.uploadLocalJson(object : UploadService.UploadCallback {
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
        if (current == total && total > 0) {
            uploadDialog?.setMessage("本阶段完成，继续下一阶段...")
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
            来源: ${if (photo.source == "system") "系统相册" else "下载目录"}
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