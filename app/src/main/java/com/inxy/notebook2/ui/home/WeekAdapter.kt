package com.inxy.notebook2.ui.home

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.inxy.notebook2.R

class WeekAdapter : RecyclerView.Adapter<WeekAdapter.VH>() {

    private val data = mutableListOf<Week>()

    fun submitList(list: List<Week>) {
        Log.d("WeekAdapter", "submitList: 收到 ${list.size} 个Week")
        data.clear()
        data.addAll(list)
        Log.d("WeekAdapter", "data现在有 ${data.size} 个Week")
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.weekTitle)
        val recycler: RecyclerView = view.findViewById(R.id.courseRecycler)

        // 为每个ViewHolder保存CourseAdapter的引用
        private val courseAdapter = CourseAdapter()

        init {
            // 只在初始化时设置一次LayoutManager和Adapter
            recycler.layoutManager = LinearLayoutManager(recycler.context)
            recycler.adapter = courseAdapter
            Log.d("WeekAdapter", "ViewHolder初始化完成")
        }

        fun bind(week: Week) {
            Log.d("WeekAdapter", "绑定Week: ${week.name}, expanded=${week.expanded}")
            title.text = week.name

            // 更新CourseAdapter的数据
            courseAdapter.submitList(week.courses)

            // 根据展开状态设置可见性
            recycler.visibility = if (week.expanded) View.VISIBLE else View.GONE

            // 移除旧的点击监听，避免重复添加
            title.setOnClickListener(null)
            title.setOnClickListener {
                Log.d("WeekAdapter", "点击了 ${week.name}")
                week.expanded = !week.expanded
                notifyItemChanged(adapterPosition)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        Log.d("WeekAdapter", "onCreateViewHolder")
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_week, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int {
        Log.d("WeekAdapter", "getItemCount: ${data.size}")
        return data.size
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        Log.d("WeekAdapter", "onBindViewHolder position=$position")

        holder.bind(data[position])
    }
}