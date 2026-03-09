package com.inxy.notebook2.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uri: String,
    val fileName: String,
    val filePath: String,
    val dateAdded: Long,  // 时间戳
    val dateModified: Long? = null,  // 添加这个字段，可以为空
    val mimeType: String? = null,    // 添加这个字段，可以为空
    val size: Long,
    val isSynced: Boolean = false
)