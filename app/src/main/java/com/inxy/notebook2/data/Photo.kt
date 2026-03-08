package com.inxy.notebook2.data

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.inxy.notebook2.R

data class Photo(
    val uri: Uri,
    val fileName: String,
    val filePath: String,
    val dateAdded: Long,
    val dateModified: Long,
    val size: Long,
    val mimeType: String
)

class PhotoAdapter(
    private val photos: List<PhotoEntity>,
    private val onItemClick: (PhotoEntity) -> Unit
) : RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image, parent, false)
        return PhotoViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(photos[position])
    }

    override fun getItemCount(): Int = photos.size

    class PhotoViewHolder(
        itemView: View,
        private val onItemClick: (PhotoEntity) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val imageView: ImageView = itemView.findViewById(R.id.imageView)
        private var currentPhoto: PhotoEntity? = null

        init {
            itemView.setOnClickListener {
                currentPhoto?.let { onItemClick(it) }
            }

            // 长按显示文件名
            itemView.setOnLongClickListener {
                currentPhoto?.let { photo ->
                    Toast.makeText(itemView.context, photo.fileName, Toast.LENGTH_SHORT).show()
                }
                true
            }
        }

        fun bind(photo: PhotoEntity) {
            currentPhoto = photo

            // 使用Glide加载缩略图
            Glide.with(itemView.context)
                .load(Uri.parse(photo.uri))
                .centerCrop()
                .placeholder(R.drawable.ic_keyboard_arrow_up)
                .error(R.drawable.ic_launcher_background)
                .into(imageView)
        }
    }
}