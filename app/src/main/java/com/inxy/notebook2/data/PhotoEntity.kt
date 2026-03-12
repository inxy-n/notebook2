package com.inxy.notebook2.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uri: String,
    val fileName: String,
    val filePath: String,
    val dateAdded: Long,
    val dateModified: Long? = null,  // 添加这个字段，可为空
    val mimeType: String? = null,     // 添加这个字段，可为空
    val size: Long,
    val source: String = "system",
    val isSynced: Boolean = false
)