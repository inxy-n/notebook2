package com.inxy.notebook2.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uri: String,          // 照片的URI
    val fileName: String,      // 文件名
    val filePath: String,      // 文件路径
    val dateAdded: Long,       // 添加时间戳
    val dateModified: Long,    // 修改时间戳
    val size: Long,            // 文件大小
    val mimeType: String,      // MIME类型
    val isSynced: Boolean = false // 是否已同步
) : Serializable