package com.example.filemanager

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.filemanager.databinding.ActivityApkToolsBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ApkToolsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApkToolsBinding
    private var apkFile: File? = null
    private val logBuilder = StringBuilder()
    private var contentsVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityApkToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val path = intent.getStringExtra("apk_path")
        if (path == null) {
            Toast.makeText(this, "未指定APK文件", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        apkFile = File(path)
        if (!apkFile!!.exists() || !apkFile!!.canRead()) {
            Toast.makeText(this, "文件不存在或无法读取", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.toolbar.title = "APK工具 - ${apkFile!!.name}"
        loadApkInfo()
        checkSignature()

        binding.btnSign.setOnClickListener { signApk() }
        binding.btnRemoveSig.setOnClickListener { removeSignature() }
        binding.btnCheckSig.setOnClickListener { checkSignature() }
        binding.btnViewContents.setOnClickListener { toggleContents() }
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logBuilder.append("[$time] $msg\n")
        binding.tvLog.text = logBuilder.toString()
    }

    private fun loadApkInfo() {
        val info = ApkUtils.getApkInfo(this, apkFile!!)
        if (info == null) {
            binding.tvApkInfo.text = "无法解析APK信息"
            log("解析APK信息失败")
            return
        }

        val sb = StringBuilder()
        sb.append("应用名称: ${info.label}\n")
        sb.append("包名: ${info.packageName}\n")
        sb.append("版本: ${info.versionName} (${info.versionCode})\n")
        sb.append("minSdk: ${info.minSdk} (Android ${sdkToVersion(info.minSdk)})\n")
        sb.append("targetSdk: ${info.targetSdk} (Android ${sdkToVersion(info.targetSdk)})\n")
        sb.append("文件大小: ${FileItem.formatFileSize(info.fileSize)}\n")
        sb.append("\n权限列表 (${info.permissions.size}):\n")
        info.permissions.take(50).forEach { sb.append("  - $it\n") }
        if (info.permissions.size > 50) {
            sb.append("  ... 共 ${info.permissions.size} 个权限\n")
        }
        sb.append("\n组件:\n")
        sb.append("  Activities: ${info.activities.size}\n")
        sb.append("  Services: ${info.services.size}\n")
        sb.append("  Receivers: ${info.receivers.size}\n")
        sb.append("  Providers: ${info.providers.size}\n")

        if (info.activities.isNotEmpty()) {
            sb.append("\nActivities:\n")
            info.activities.take(20).forEach { sb.append("  - $it\n") }
            if (info.activities.size > 20) sb.append("  ... 共 ${info.activities.size} 个\n")
        }

        binding.tvApkInfo.text = sb.toString()
        log("APK信息加载完成: ${info.label} v${info.versionName}")
    }

    private fun checkSignature() {
        val signed = ApkUtils.isSigned(apkFile!!)
        binding.tvSigStatus.visibility = android.view.View.VISIBLE
        binding.tvSigStatus.text = if (signed) {
            "签名状态: 已签名 (V1 JAR签名)"
        } else {
            "签名状态: 未签名"
        }
        log("签名检查: ${if (signed) "已签名" else "未签名"}")
    }

    private fun signApk() {
        log("开始签名APK...")
        Toast.makeText(this, "正在签名，请稍候...", Toast.LENGTH_SHORT).show()

        Thread {
            val outputFile = File(apkFile!!.parentFile, "${apkFile!!.nameWithoutExtension}_signed.apk")
            val ok = ApkUtils.signApk(apkFile!!, outputFile)

            runOnUiThread {
                if (ok) {
                    log("签名成功! 输出: ${outputFile.name}")
                    Toast.makeText(this, "签名成功: ${outputFile.name}", Toast.LENGTH_LONG).show()
                    checkSignature()
                } else {
                    log("签名失败!")
                    Toast.makeText(this, "签名失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun removeSignature() {
        log("开始去除签名...")
        Toast.makeText(this, "正在处理...", Toast.LENGTH_SHORT).show()

        Thread {
            val outputFile = File(apkFile!!.parentFile, "${apkFile!!.nameWithoutExtension}_unsigned.apk")
            val ok = ApkUtils.removeSignature(apkFile!!, outputFile)

            runOnUiThread {
                if (ok) {
                    log("去除签名成功! 输出: ${outputFile.name}")
                    Toast.makeText(this, "已去除签名: ${outputFile.name}", Toast.LENGTH_LONG).show()
                } else {
                    log("去除签名失败!")
                    Toast.makeText(this, "去除签名失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun toggleContents() {
        contentsVisible = !contentsVisible
        if (contentsVisible) {
            val entries = ApkUtils.listApkContents(apkFile!!)
            val sb = StringBuilder()
            sb.append("共 ${entries.size} 个文件\n\n")
            entries.take(200).forEach { entry ->
                val type = if (entry.isDirectory) "[DIR]" else "     "
                sb.append("$type ${entry.name}  (${FileItem.formatFileSize(entry.size)})\n")
            }
            if (entries.size > 200) {
                sb.append("\n... 仅显示前200个文件")
            }
            binding.tvContents.text = sb.toString()
            binding.tvContents.visibility = android.view.View.VISIBLE
            log("显示文件列表: ${entries.size} 个文件")
        } else {
            binding.tvContents.visibility = android.view.View.GONE
        }
    }

    private fun sdkToVersion(sdk: Int): String {
        return when (sdk) {
            29 -> "10"
            30 -> "11"
            31 -> "12"
            32 -> "12L"
            33 -> "13"
            34 -> "14"
            35 -> "15"
            else -> "API $sdk"
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
