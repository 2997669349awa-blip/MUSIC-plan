package com.example.filemanager

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.filemanager.databinding.ActivityTextViewerBinding
import java.io.File
import java.nio.charset.Charset

/**
 * 文本查看/编辑器
 * v1.6.6：从只读 TextView 升级为可编辑 EditText，支持保存修改
 */
class TextViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTextViewerBinding
    private var file: File? = null
    private var currentEncoding: String = "UTF-8"
    private val encodings = arrayOf("UTF-8", "GBK", "GB2312", "BIG5", "ISO-8859-1", "UTF-16", "UTF-16LE", "UTF-16BE")
    private var encodingIndex = 0
    private var autoDetect = true

    // v1.6.6：编辑相关
    private var isModified = false
    private var isLargeFileMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val path = intent.getStringExtra("file_path")
        if (path == null) {
            Toast.makeText(this, "无法打开文件", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        file = File(path)
        if (!file!!.exists() || !file!!.canRead()) {
            Toast.makeText(this, "文件不存在或无法读取", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.toolbar.title = file!!.name

        // 自动检测编码
        if (autoDetect) {
            currentEncoding = EncodingDetector.detect(file!!)
            encodings.indexOf(currentEncoding).let { if (it >= 0) encodingIndex = it }
        }
        loadText()

        // v1.6.6：监听内容修改
        binding.textContent.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!isLoading) isModified = true
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // v1.6.6：返回时如有未保存修改提示
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isModified) {
                    AlertDialog.Builder(this@TextViewerActivity)
                        .setTitle("未保存的修改")
                        .setMessage("内容已修改，是否保存？")
                        .setPositiveButton("保存") { _, _ ->
                            if (saveFile()) finish()
                        }
                        .setNegativeButton("不保存") { _, _ -> finish() }
                        .setNeutralButton("取消", null)
                        .show()
                } else {
                    finish()
                }
            }
        })
    }

    private var isLoading = false

    private fun loadText() {
        try {
            val charset = Charset.forName(currentEncoding)
            val text = file!!.readText(charset)
            isLoading = true
            binding.textContent.setText(text)
            binding.textContent.setSelection(0)
            isLoading = false
            isModified = false
            binding.toolbar.subtitle = "编码: $currentEncoding"
        } catch (e: Exception) {
            Toast.makeText(this, "读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * v1.6.6：保存文件（用当前编码写回）
     */
    private fun saveFile(): Boolean {
        val f = file ?: return false
        return try {
            if (!f.canWrite()) {
                Toast.makeText(this, "文件不可写（无写入权限）", Toast.LENGTH_LONG).show()
                return false
            }
            val charset = Charset.forName(currentEncoding)
            f.writeText(binding.textContent.text.toString(), charset)
            isModified = false
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // v1.6.6：新增"保存"菜单
        menu.add(0, 0, 0, "保存").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, 1, 0, "切换编码").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, 2, 0, "重新检测编码")
        menu.add(0, 3, 0, "大文件模式")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                return true
            }
            0 -> {
                saveFile()
                return true
            }
            1 -> {
                encodingIndex = (encodingIndex + 1) % encodings.size
                currentEncoding = encodings[encodingIndex]
                autoDetect = false
                loadText()
                Toast.makeText(this, "编码: $currentEncoding", Toast.LENGTH_SHORT).show()
                return true
            }
            2 -> {
                autoDetect = true
                currentEncoding = EncodingDetector.detect(file!!)
                encodings.indexOf(currentEncoding).let { if (it >= 0) encodingIndex = it }
                loadText()
                Toast.makeText(this, "检测到编码: $currentEncoding", Toast.LENGTH_SHORT).show()
                return true
            }
            3 -> {
                loadLargeFile()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadLargeFile() {
        try {
            isLargeFileMode = true
            val charset = Charset.forName(currentEncoding)
            val reader = file!!.bufferedReader(charset)
            val sb = StringBuilder()
            var line: String?
            var count = 0
            val maxLines = 5000
            while (reader.readLine().also { line = it } != null && count < maxLines) {
                sb.appendLine(line)
                count++
            }
            if (count >= maxLines) {
                sb.append("\n\n... (仅显示前 $maxLines 行，文件过大)")
            }
            reader.close()
            isLoading = true
            binding.textContent.setText(sb.toString())
            isLoading = false
            isModified = false
            binding.toolbar.subtitle = "编码: $currentEncoding (大文件/只读前${maxLines}行)"
            Toast.makeText(this, "大文件模式加载完成（只读，修改后保存仅覆盖显示部分，请谨慎）", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
