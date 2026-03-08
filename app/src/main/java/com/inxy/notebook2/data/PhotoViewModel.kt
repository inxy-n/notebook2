package com.inxy.notebook2.data
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class PhotoViewModel(application: Application) : AndroidViewModel(application) {

    private val database = PhotoDatabase.getInstance(application)
    private val repository = PhotoRepository(application, database.photoDao())

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _photoCount = MutableLiveData(0)
    val photoCount: LiveData<Int> = _photoCount

    private val _photos = MutableLiveData<List<PhotoEntity>>()
    val photos: LiveData<List<PhotoEntity>> = _photos

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    init {
        observePhotos()
    }

    private fun observePhotos() {
        viewModelScope.launch {
            repository.getAllPhotos().collect { photoList ->
                _photos.postValue(photoList)
                _photoCount.postValue(photoList.size)
            }
        }
    }

    /**
     * 刷新照片数据
     */
    fun refreshPhotos() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                repository.refreshPhotos()
            } catch (e: Exception) {
                _error.value = "刷新照片失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 只从系统读取但不保存到数据库
     */
    fun loadPhotosFromSystem(onResult: (List<Photo>) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val photos = repository.loadPhotosFrom2026March1()
                onResult(photos)
            } catch (e: Exception) {
                _error.value = "加载照片失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 删除照片
     */
    fun deletePhoto(uri: String) {
        viewModelScope.launch {
            try {
                repository.deletePhoto(uri)
            } catch (e: Exception) {
                _error.value = "删除照片失败: ${e.message}"
            }
        }
    }

    /**
     * 格式化日期
     */
    fun formatDate(timestamp: Long): String {
        return repository.formatDate(timestamp)
    }

    fun clearError() {
        _error.value = null
    }
}