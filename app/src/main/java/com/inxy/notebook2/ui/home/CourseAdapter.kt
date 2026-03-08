package com.inxy.notebook2.ui.home

import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.inxy.notebook2.R

class CourseAdapter : RecyclerView.Adapter<CourseAdapter.ViewHolder>() {

    private val data = mutableListOf<Course>()
    private val expandedStates = mutableMapOf<Int, Boolean>()
    fun submitList(list: List<Course>) {
        Log.d("CourseAdapter", "submitList: 收到 ${list.size} 个Course")
        data.clear()
        data.addAll(list)
        data.indices.forEach { index ->
            expandedStates[index] = false  // 默认不展开
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_course, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(data[position])
    }

    override fun getItemCount() = data.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // 使用 lateinit var 并延迟初始化
        private lateinit var title: TextView
        private lateinit var recyclerView: RecyclerView
        private val imageAdapter = ImageAdapter()

        init {
            try {
                // 安全地查找视图
                title = itemView.findViewById(R.id.courseTitle)
                recyclerView = itemView.findViewById(R.id.imageRecyclerView)

                // 配置RecyclerView
                recyclerView.layoutManager = LinearLayoutManager(
                    recyclerView.context,
                    LinearLayoutManager.HORIZONTAL,
                    false
                )
                recyclerView.adapter = imageAdapter

                Log.d("CourseAdapter", "ViewHolder初始化成功")
            } catch (e: Exception) {
                Log.e("CourseAdapter", "ViewHolder初始化失败", e)
                throw e
            }
        }

        fun bind(course: Course) {
            try {
                title.text = "${course.name} (${course.images.size})"

                imageAdapter.submitList(course.images)
                val isExpanded = expandedStates[position] ?: false  // 默认false
                recyclerView.visibility = if (isExpanded) View.VISIBLE else View.GONE
                title.setOnClickListener(null)
                title.setOnClickListener {
                    Log.d("CourseAdapter", "点击了课程标题: ${course.name}")

                    // 切换展开状态
                    val currentState = expandedStates[position] ?: false
                    expandedStates[position] = !currentState

                    // 只更新当前项，提高性能
                    notifyItemChanged(position)

                    // 可选：添加点击动画效果
                    title.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100)
                        .withEndAction {
                            title.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                        }.start()
                }
                Log.d("CourseAdapter", "绑定Course: ${course.name}, 图片数: ${course.images.size}")
            } catch (e: Exception) {
                Log.e("CourseAdapter", "绑定Course失败", e)
            }
        }
    }
}