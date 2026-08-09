package com.example.musicplugin

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

/**
 * v1.1.7：听歌识曲页面
 *
 * 流程：
 * 1. 点击"开始识别" → 录音 10 秒
 * 2. 上传 Audd.io 识别
 * 3. 识别成功 → 显示歌名/歌手，用当前音乐源回查 → 命中则提示可播放
 */
class RecognizeActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnStart: Button
    private lateinit var btnPlay: Button
    private lateinit var btnReRecognize: Button

    private var audioFile: File? = null
    private var recorder: AudioRecorder? = null
    private val handler = Handler(Looper.getMainLooper())
    private var recognizeTimeoutRunnable: Runnable? = null
    private var isRecognizing = false

    // 识别结果
    private var matchedSong: MusicApi.OnlineSong? = null

    private val recordPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(this, "需要录音权限才能识曲", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.2.2：签名校验
        if (!SignatureVerifier.isOriginalSignature(this)) {
            AlertDialog.Builder(this)
                .setTitle("签名异常")
                .setMessage("你安装的不是原版软件，可能出现危险行为，请安装原版软件。")
                .setCancelable(false)
                .setPositiveButton("确定") { _, _ ->
                    finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
                .show()
            return
        }
        buildUI()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F0F0F.toInt())
            setPadding(48, 64, 48, 64)
        }

        TextView(this).apply {
            text = "听歌识曲"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 24f
            setPadding(0, 0, 0, 32)
            root.addView(this)
        }

        tvStatus = TextView(this).apply {
            text = "点击下方按钮，靠近音源识别"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 14f
            setPadding(0, 0, 0, 24)
            root.addView(this)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            root.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        btnStart = Button(this).apply {
            text = "开始识别"
            setBackgroundColor(0xFF1ED760.toInt())
            setTextColor(0xFF000000.toInt())
            setOnClickListener { checkPermissionAndRecord() }
            root.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 })
        }

        tvResult = TextView(this).apply {
            text = ""
            setTextColor(0xFF1ED760.toInt())
            textSize = 16f
            setPadding(0, 32, 0, 16)
            visibility = View.GONE
            root.addView(this)
        }

        btnPlay = Button(this).apply {
            text = "播放这首歌"
            setBackgroundColor(0xFF1ED760.toInt())
            setTextColor(0xFF000000.toInt())
            visibility = View.GONE
            setOnClickListener {
                val s = matchedSong ?: return@setOnClickListener
                // 跳转 MainActivity 并传递播放指令
                val intent = Intent(this@RecognizeActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("recognize_song_id", s.id)
                    putExtra("recognize_song_name", s.name)
                    putExtra("recognize_song_artist", s.artist)
                }
                startActivity(intent)
                finish()
            }
            root.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 })
        }

        btnReRecognize = Button(this).apply {
            text = "重新识别"
            setBackgroundColor(0xFF333333.toInt())
            setTextColor(0xFFDDDDDD.toInt())
            visibility = View.GONE
            setOnClickListener { checkPermissionAndRecord() }
            root.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 })
        }

        setContentView(root)
    }

    private fun checkPermissionAndRecord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        // 清理旧结果
        tvResult.visibility = View.GONE
        btnPlay.visibility = View.GONE
        btnReRecognize.visibility = View.GONE
        btnStart.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        tvStatus.text = "正在录音... 请靠近音源"

        // 准备录音文件
        val f = File(cacheDir, "recognize_${System.currentTimeMillis()}.wav")
        audioFile = f
        try {
            recorder = AudioRecorder(f).also { it.start() }
        } catch (e: SecurityException) {
            tvStatus.text = "录音权限被拒绝"
            btnStart.visibility = View.VISIBLE
            progressBar.visibility = View.GONE
            return
        }

        // 录音进度（12秒，提升识别准确率）
        val duration = 12000L
        val tick = 100L
        var elapsed = 0L
        val tickRunnable = object : Runnable {
            override fun run() {
                elapsed += tick
                progressBar.progress = ((elapsed.toFloat() / duration) * 100).toInt()
                if (elapsed < duration) {
                    handler.postDelayed(this, tick)
                } else {
                    stopAndRecognize()
                }
            }
        }
        handler.postDelayed(tickRunnable, tick)
    }

    private fun stopAndRecognize() {
        recorder?.stop()
        recorder = null
        progressBar.visibility = View.GONE
        tvStatus.text = "正在识别..."
        val f = audioFile
        if (f == null || !f.exists()) {
            tvStatus.text = "录音失败"
            btnStart.visibility = View.VISIBLE
            return
        }

        isRecognizing = true
        var callbackCalled = false

        // UI 超时兜底：20s 后强制超时
        recognizeTimeoutRunnable?.let { handler.removeCallbacks(it) }
        recognizeTimeoutRunnable = Runnable {
            if (!callbackCalled && isRecognizing) {
                isRecognizing = false
                tvStatus.text = "识别超时，请重试"
                btnStart.visibility = View.VISIBLE
                btnStart.text = "重新识别"
            }
        }
        handler.postDelayed(recognizeTimeoutRunnable!!, 20000)

        RecognizeApi.recognize(this, f) { result ->
            runOnUiThread {
                callbackCalled = true
                recognizeTimeoutRunnable?.let { handler.removeCallbacks(it) }
                recognizeTimeoutRunnable = null
                isRecognizing = false
                if (result.success) {
                    tvResult.visibility = View.VISIBLE
                    tvResult.text = "${result.title}\n${result.artist}"
                    tvStatus.text = "识别成功，正在回查曲库..."
                    searchInSource(result.title ?: "", result.artist ?: "")
                } else {
                    tvStatus.text = "识别失败：${result.errorMsg}"
                    btnStart.visibility = View.VISIBLE
                    btnStart.text = "重新识别"
                }
            }
        }
    }

    /**
     * 用识别到的歌名+歌手在当前音乐源搜索
     * 找到名字/歌手匹配的歌，供用户播放
     */
    private fun searchInSource(title: String, artist: String) {
        val keyword = if (artist.isNotEmpty()) "$title $artist" else title
        MusicApi.search(keyword) { list, err ->
            runOnUiThread {
                if (err != null || list == null) {
                    tvStatus.text = "回查失败：$err"
                    btnReRecognize.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                // 匹配歌名（包含或相等）
                val match = list.firstOrNull {
                    val nameMatch = it.name.contains(title, ignoreCase = true) ||
                        title.contains(it.name, ignoreCase = true)
                    val artistMatch = artist.isEmpty() ||
                        it.artist.contains(artist, ignoreCase = true) ||
                        artist.contains(it.artist, ignoreCase = true)
                    nameMatch && artistMatch
                } ?: list.firstOrNull()
                if (match != null) {
                    matchedSong = match
                    tvStatus.text = "已匹配到：${match.name} - ${match.artist}"
                    btnPlay.visibility = View.VISIBLE
                    btnReRecognize.visibility = View.VISIBLE
                } else {
                    tvStatus.text = "曲库未找到匹配歌曲"
                    btnReRecognize.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizeTimeoutRunnable?.let { handler.removeCallbacks(it) }
        recognizeTimeoutRunnable = null
    }
}
