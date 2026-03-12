package com.inxy.notebook2.ui.home

import android.net.Uri

data class Course(
    val name: String,
    val images: List<Uri>,
    var expanded: Boolean = false,
    val courseType: String
)