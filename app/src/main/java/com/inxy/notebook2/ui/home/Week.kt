package com.inxy.notebook2.ui.home

// 修改 Week 数据类
// 修改 Week 数据类
data class Week(
    val name: String,
    val courses: List<Course>,
    var expanded: Boolean = false
) {
    // 为每个 Week 项创建并保持自己的 adapter
    val courseAdapter: CourseAdapter by lazy {
        CourseAdapter().apply {
            submitList(courses)
        }
    }
}