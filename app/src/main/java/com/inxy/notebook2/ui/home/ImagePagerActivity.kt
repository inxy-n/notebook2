// ImagePagerActivity.kt
package com.inxy.notebook2.ui.home // 放在合适目录下

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView // 导入 PhotoView
import com.inxy.notebook2.databinding.ActivityImagePagerBinding // 假设你创建了对应的布局

class ImagePagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImagePagerBinding
    private lateinit var imageUris: List<Uri>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImagePagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 获取传递过来的数据
        val urlsString = intent.getStringExtra(EXTRA_IMAGE_URLS)
        imageUris = urlsString?.split(",")?.map { Uri.parse(it) } ?: emptyList()
        val initialPosition = intent.getIntExtra(EXTRA_POSITION, 0)

        // 2. 设置 ViewPager2 的 Adapter
        binding.viewPager.adapter = ImagePagerAdapter(this, imageUris)
        // 3. 跳转到点击的那张图
        binding.viewPager.setCurrentItem(initialPosition, false)
    }

    // 伴生对象，用于定义启动 Activity 的静态方法
    companion object {
        private const val EXTRA_IMAGE_URLS = "extra_image_urls"
        private const val EXTRA_POSITION = "extra_position"

        fun start(context: Context, imageUris: List<Uri>, position: Int) {
            val intent = Intent(context, ImagePagerActivity::class.java).apply {
                // 将 URI 列表转成字符串传递（简单处理，也可以用 Parcelable）
                putExtra(EXTRA_IMAGE_URLS, imageUris.joinToString(",") { it.toString() })
                putExtra(EXTRA_POSITION, position)
            }
            context.startActivity(intent)
        }
    }
}