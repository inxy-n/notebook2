// ImagePagerAdapter.kt
package com.inxy.notebook2.ui.home

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.github.chrisbanes.photoview.PhotoView
import com.inxy.notebook2.R

class ImagePagerAdapter(
    private val context: android.content.Context,
    private val imageUris: List<Uri>
) : RecyclerView.Adapter<ImagePagerAdapter.PagerViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagerViewHolder {
        // 直接创建一个 PhotoView 实例作为 Item 布局
        val photoView = PhotoView(parent.context)
        photoView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // 设置缩放类型，PhotoView 会处理得很好
        photoView.setScaleType(ImageView.ScaleType.FIT_CENTER)
        return PagerViewHolder(photoView)
    }

    override fun onBindViewHolder(holder: PagerViewHolder, position: Int) {
        val uri = imageUris[position]
        // 用 Glide 把图片加载到 PhotoView 里
        Glide.with(context)
            .load(uri)
            .into(holder.photoView) // Glide 支持直接 into PhotoView
    }

    override fun getItemCount() = imageUris.size

    inner class PagerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // itemView 就是我们创建的 PhotoView
        val photoView: PhotoView = itemView as PhotoView
    }
}