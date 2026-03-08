package com.inxy.notebook2.ui.home

import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.inxy.notebook2.R

class ImageAdapter : RecyclerView.Adapter<ImageAdapter.ViewHolder>() {

    private val data = mutableListOf<Uri>()
    // 添加一个变量持有完整的图片列表（用于点击跳转）
    private var allImages: List<Uri> = emptyList()

    fun submitList(list: List<Uri>) {
        Log.d("ImageAdapter", "submitList: 收到 ${list.size} 张图片")
        data.clear()
        data.addAll(list)
        // 同时保存完整列表（注意：这里假设传入的就是完整列表）
        allImages = list
        Log.d("ImageAdapter", "data现在有 ${data.size} 张图片")
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(data[position])
    }

    override fun getItemCount() = data.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.imageView)

        fun bind(uri: Uri) {
            Log.d("ImageAdapter", "加载图片: $uri")
            Glide.with(itemView.context)
                .load(uri)
                .centerCrop()
                .placeholder(R.drawable.ic_home_black_24dp)
                .error(R.drawable.ic_launcher_background)
                .into(imageView)

            // 添加点击事件
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    // 使用 allImages 而不是未定义的 imageUris
                    ImagePagerActivity.start(itemView.context, allImages, position)
                }
            }
        }
    }
}