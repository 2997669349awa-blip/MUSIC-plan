package com.example.filemanager

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.filemanager.databinding.ActivityImageViewerBinding
import java.io.File

class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding
    private var file: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        val path = intent.getStringExtra("file_path")
        if (path == null) {
            Toast.makeText(this, "无法打开图片", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        file = File(path)
        if (!file!!.exists() || !file!!.canRead()) {
            // 尝试Root读取
            loadWithRoot(path)
            return
        }

        binding.toolbar.title = file!!.name
        loadImage()
    }

    private fun loadImage() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvError.visibility = View.GONE

        Thread {
            try {
                val options = BitmapFactory.Options()
                options.inSampleSize = 1
                // 先获取图片尺寸
                options.inJustDecodeBounds = true
                BitmapFactory.decodeFile(file!!.absolutePath, options)

                // 计算采样率以避免OOM
                val maxDim = 4096
                var sampleSize = 1
                while (options.outWidth / sampleSize > maxDim || options.outHeight / sampleSize > maxDim) {
                    sampleSize *= 2
                }

                options.inJustDecodeBounds = false
                options.inSampleSize = sampleSize
                val bitmap = BitmapFactory.decodeFile(file!!.absolutePath, options)

                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    if (bitmap != null) {
                        binding.imageView.setImageBitmap(bitmap)
                    } else {
                        binding.tvError.text = "无法解码图片"
                        binding.tvError.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.tvError.text = "加载失败: ${e.message}"
                    binding.tvError.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun loadWithRoot(path: String) {
        if (!RootUtils.isRootAvailable()) {
            Toast.makeText(this, "文件无法读取且设备未Root", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        Thread {
            try {
                val tempFile = File(cacheDir, "temp_image_${System.currentTimeMillis()}")
                val ok = RootUtils.copyFileWithRoot(path, tempFile.absolutePath)
                if (ok && tempFile.exists()) {
                    file = tempFile
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        binding.toolbar.title = File(path).name
                        loadImage()
                    }
                } else {
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this, "Root读取失败", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this, "读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
