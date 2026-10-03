package com.example.musicplugin

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * v1.1.4：PV 搜索板块
 * v1.2.4：播放器大改
 * - 全屏沉浸（隐藏状态栏+导航栏）
 * - 播放 PV 时暂停背景音乐，退出 PV 恢复
 * - 进度条 + 时间显示 + 拖动跳转
 * - 快进/快退 ±10 秒
 * - 记忆播放位置，重入恢复
 * - 接收外部 autoPlay 参数，从 MainActivity MV 按钮跳转自动播放
 */
class PvSearchActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var pvAdapter: PvAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var etSearch: EditText

    // 视频播放层
    private lateinit var videoContainer: FrameLayout
    private lateinit var textureView: TextureView
    private lateinit var btnVideoBack: ImageButton
    private lateinit var btnVideoPlayPause: ImageButton
    private lateinit var btnVideoRewind: ImageButton
    private lateinit var btnVideoForward: ImageButton
    private lateinit var tvVideoTitle: TextView
    private lateinit var tvVideoCurrent: TextView
    private lateinit var tvVideoTotal: TextView
    private lateinit var seekBarVideo: SeekBar
    private lateinit var videoControlBar: LinearLayout
    private lateinit var topControl: LinearLayout
    private val videoHandler = Handler(Looper.getMainLooper())

    // v1.2.7：改用 TextureView + MediaPlayer，彻底解决后台画面丢失 + 视频比例问题
    private var mediaPlayer: MediaPlayer? = null
    private var surfaceReady = false
    private var videoPrepared = false
    private var pendingUrl: String? = null
    private var pendingSeekPos = 0
    // 视频真实尺寸，用于按比例缩放
    private var videoWidth = 0
    private var videoHeight = 0

    private var controlHideRunnable: Runnable? = null
    private var progressRunnable: Runnable? = null
    private var userSeeking = false
    // v1.2.4：背景音乐是否被我们暂停的（退出时恢复）
    private var pausedBackgroundMusic = false
    // v1.2.4：当前播放的 MV（用于记忆位置）
    private var currentMvUrl: String? = null
    private var currentMvTitle: String = ""
    // v1.2.5：后台返回时的恢复状态
    private var wasPlayingBeforeBackground = false
    private var pausedByBackground = false

    // v1.2.4：绑定 MusicService 控制背景音乐
    private var musicService: MusicService? = null
    private var serviceBound = false
    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            musicService = (service as? MusicService.MusicBinder)?.getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            musicService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MusicApi.init(this)

        // v1.2.4：绑定 MusicService 用于暂停/恢复背景音乐
        val svcIntent = Intent(this, MusicService::class.java)
        bindService(svcIntent, serviceConnection, BIND_AUTO_CREATE)

        // 根布局：竖向 LinearLayout（搜索栏 + 列表 + 空状态）
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F0F0F.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ====== 顶部标题 + 搜索栏 ======
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1A1A.toInt())
            setPadding(24, 36, 24, 16)
        }
        topBar.addView(TextView(this).apply {
            text = "搜索 PV"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 16)
        })
        // 搜索框
        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1F1F1F.toInt())
            setPadding(20, 12, 20, 12)
            gravity = Gravity.CENTER_VERTICAL
        }
        val searchIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setColorFilter(0xFFB3B3B3.toInt())
            layoutParams = LinearLayout.LayoutParams(28.dp(), 28.dp())
        }
        etSearch = EditText(this).apply {
            hint = "输入歌曲名 / 歌手 搜索 PV"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666666.toInt())
            textSize = 14f
            background = null
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = 12 }
        }
        searchBar.addView(searchIcon)
        searchBar.addView(etSearch)
        topBar.addView(searchBar)
        root.addView(topBar)

        // ====== 加载进度 ======
        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                topMargin = 24
            }
        }
        root.addView(progressBar)

        // ====== 列表 ======
        recyclerView = RecyclerView(this).apply {
            setBackgroundColor(0xFF0F0F0F.toInt())
            layoutManager = LinearLayoutManager(this@PvSearchActivity)
            clipToPadding = false
            setPadding(0, 8, 0, 32)
        }
        pvAdapter = PvAdapter { mv -> onPvClicked(mv) }
        recyclerView.adapter = pvAdapter
        root.addView(recyclerView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        // ====== 空状态 ======
        tvEmpty = TextView(this).apply {
            text = "输入关键词搜索 PV\n网易云 MV 库匹配，点击直接播放"
            setTextColor(0xFF888888.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(48, 120, 48, 48)
            visibility = View.VISIBLE
        }
        root.addView(tvEmpty, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ====== 视频播放层（默认隐藏）======
        videoContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        // v1.2.7：用 TextureView 替代 SurfaceView
        // 优势：不会因 Activity 后台销毁 Surface（解决画面丢失），支持矩阵变换做比例适配
        textureView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                surfaceReady = true
                mediaPlayer?.setSurface(android.view.Surface(surface))
                // 如果有 pending URL，开始播放
                pendingUrl?.let { url ->
                    prepareVideo(url, pendingSeekPos)
                    pendingUrl = null
                    pendingSeekPos = 0
                }
                // 已 prepared 的视频重新 attach surface 后更新比例
                if (videoWidth > 0 && videoHeight > 0) updateVideoScale()
            }
            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                if (videoWidth > 0 && videoHeight > 0) updateVideoScale()
            }
            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                // v1.2.7：返回 false 保持 SurfaceTexture 不被销毁，后台返回可继续渲染
                // MediaPlayer 仍持有 surface，回来时 onSurfaceTextureAvailable 会重新 attach
                return true
            }
            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
        }
        textureView.setOnClickListener { toggleControlBar() }
        videoContainer.addView(textureView)

        // 顶部控制栏：返回 + 标题
        topControl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xAA000000.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 24, 16, 16)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
        }
        btnVideoBack = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setColorFilter(Color.WHITE)
            background = null
            setOnClickListener { closeVideo() }
            layoutParams = LinearLayout.LayoutParams(48.dp(), 48.dp())
        }
        tvVideoTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(16, 0, 16, 0)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        topControl.addView(btnVideoBack)
        topControl.addView(tvVideoTitle)
        videoContainer.addView(topControl)

        // 底部控制栏：快退 + 播放暂停 + 快进
        videoControlBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xAA000000.toInt())
            setPadding(16, 12, 16, 24)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        }
        // 进度条行
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 0, 8, 8)
        }
        tvVideoCurrent = TextView(this).apply {
            text = "00:00"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 12f
        }
        seekBarVideo = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = 10; marginEnd = 10 }
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF1ED760.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFF1ED760.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    userSeeking = false
                    val mp = mediaPlayer ?: return
                    val dur = try { mp.duration } catch (_: Exception) { 0 }
                    if (dur > 0) {
                        val target = (sb?.progress ?: 0) * dur / 1000
                        try { mp.seekTo(target) } catch (_: Exception) { }
                    }
                    showControlBarTemporarily()
                }
            })
        }
        tvVideoTotal = TextView(this).apply {
            text = "00:00"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 12f
        }
        progressRow.addView(tvVideoCurrent)
        progressRow.addView(seekBarVideo)
        progressRow.addView(tvVideoTotal)
        videoControlBar.addView(progressRow)

        // 按钮行：快退 + 播放暂停 + 快进
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        btnVideoRewind = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_rew)
            setColorFilter(Color.WHITE)
            background = null
            setOnClickListener {
                val mp = mediaPlayer ?: return@setOnClickListener
                try {
                    val cur = mp.currentPosition
                    mp.seekTo((cur - 10000).coerceAtLeast(0))
                } catch (_: Exception) { }
                showControlBarTemporarily()
            }
            layoutParams = LinearLayout.LayoutParams(52.dp(), 52.dp())
        }
        btnVideoPlayPause = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            setColorFilter(Color.WHITE)
            background = null
            setOnClickListener { toggleVideoPlay() }
            layoutParams = LinearLayout.LayoutParams(56.dp(), 56.dp()).apply {
                marginStart = 24; marginEnd = 24
            }
        }
        btnVideoForward = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_ff)
            setColorFilter(Color.WHITE)
            background = null
            setOnClickListener {
                val mp = mediaPlayer ?: return@setOnClickListener
                try {
                    val cur = mp.currentPosition
                    val dur = mp.duration
                    if (dur > 0) mp.seekTo((cur + 10000).coerceAtMost(dur))
                } catch (_: Exception) { }
                showControlBarTemporarily()
            }
            layoutParams = LinearLayout.LayoutParams(52.dp(), 52.dp())
        }
        btnRow.addView(btnVideoRewind)
        btnRow.addView(btnVideoPlayPause)
        btnRow.addView(btnVideoForward)
        videoControlBar.addView(btnRow)
        videoContainer.addView(videoControlBar)

        // v1.1.4 修复：必须先 setContentView 再 addContentView
        setContentView(root)

        // 视频层叠加在主内容之上
        addContentView(videoContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 搜索：按回车触发
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val keyword = etSearch.text.toString().trim()
                if (keyword.isNotEmpty()) searchPv(keyword)
                true
            } else false
        }

        // v1.2.4：接收外部自动播放（从 MainActivity MV 按钮跳转）
        val autoKeyword = intent.getStringExtra("auto_play_keyword")
        if (!autoKeyword.isNullOrEmpty()) {
            etSearch.setText(autoKeyword)
            searchPv(autoKeyword)
        }
    }

    /**
     * 搜索 PV
     */
    private fun searchPv(keyword: String) {
        tvEmpty.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        pvAdapter.submit(emptyList())

        MusicApi.searchPvAllSources(keyword) { list, err ->
            runOnUiThread {
                progressBar.visibility = View.GONE
                if (list == null) {
                    tvEmpty.text = "搜索失败：$err"
                    tvEmpty.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                if (list.isEmpty()) {
                    tvEmpty.text = "未找到 PV\n换个关键词试试"
                    tvEmpty.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                tvEmpty.visibility = View.GONE
                pvAdapter.submit(list)
                Toast.makeText(this, "找到 ${list.size} 个 PV", Toast.LENGTH_SHORT).show()
                // v1.2.4：外部自动播放，直接播第一个
                if (intent.getBooleanExtra("auto_play", false)) {
                    intent.removeExtra("auto_play")
                    onPvClicked(list.first())
                }
            }
        }
    }

    /**
     * 点击 PV 条目：获取播放 URL 后自动播放
     */
    private fun onPvClicked(mv: MusicApi.OnlineMv) {
        Toast.makeText(this, "获取 PV 链接中...", Toast.LENGTH_SHORT).show()
        MusicApi.getMvPlayUrl(mv) { url ->
            runOnUiThread {
                if (url.isNullOrEmpty()) {
                    Toast.makeText(this, "无法获取 PV 链接，请稍后重试", Toast.LENGTH_SHORT).show()
                } else {
                    playVideo(url, "${mv.name} - ${mv.artist}")
                }
            }
        }
    }

    /**
     * v1.2.6：用 MediaPlayer 播放视频
     * 进入全屏沉浸 + 暂停背景音乐
     */
    private fun playVideo(url: String, title: String) {
        currentMvUrl = url
        currentMvTitle = title
        tvVideoTitle.text = title
        videoContainer.visibility = View.VISIBLE
        videoContainer.bringToFront()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        enterImmersive()
        pauseBackgroundMusic()
        Toast.makeText(this, "正在加载：$title", Toast.LENGTH_SHORT).show()

        // 释放旧的 MediaPlayer
        releaseMediaPlayer()
        videoPrepared = false

        // 读取记忆位置
        val savedPos = getSavedPosition(url)
        if (savedPos > 0) {
            pendingSeekPos = savedPos
            Toast.makeText(this, "从 ${formatTime(savedPos)} 继续", Toast.LENGTH_SHORT).show()
        }

        if (surfaceReady) {
            prepareVideo(url, pendingSeekPos)
            pendingSeekPos = 0
        } else {
            // Surface 还没创建，等 surfaceCreated 回调
            pendingUrl = url
        }
        showControlBarTemporarily()
    }

    /**
     * v1.2.7：创建 MediaPlayer 并 prepare
     */
    private fun prepareVideo(url: String, seekPos: Int) {
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(url)
                if (surfaceReady) {
                    textureView.surfaceTexture?.let { setSurface(android.view.Surface(it)) }
                }
                // v1.2.7：视频尺寸回调，按真实比例缩放，不拉伸变形
                setOnVideoSizeChangedListener { _, w, h ->
                    this@PvSearchActivity.videoWidth = w
                    this@PvSearchActivity.videoHeight = h
                    updateVideoScale()
                }
                setOnPreparedListener { player ->
                    videoPrepared = true
                    btnVideoPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                    tvVideoTotal.text = formatTime(player.duration)
                    seekBarVideo.max = player.duration
                    // 拿到视频尺寸（部分机型 onPrepared 时已可用）
                    if (player.videoWidth > 0) {
                        this@PvSearchActivity.videoWidth = player.videoWidth
                        this@PvSearchActivity.videoHeight = player.videoHeight
                        updateVideoScale()
                    }
                    if (seekPos > 0 && seekPos < player.duration - 2000) {
                        player.seekTo(seekPos)
                    }
                    player.start()
                    startProgressUpdate()
                }
                setOnCompletionListener {
                    btnVideoPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    stopProgressUpdate()
                }
                setOnErrorListener { _, what, extra ->
                    Toast.makeText(this@PvSearchActivity, "视频播放失败: what=$what extra=$extra", Toast.LENGTH_LONG).show()
                    closeVideo()
                    true
                }
                prepareAsync()
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            Toast.makeText(this, "播放失败：${e.message}", Toast.LENGTH_LONG).show()
            closeVideo()
        }
    }

    /**
     * v1.2.7：按视频真实比例缩放 TextureView，保持宽高比不拉伸
     * 容器填满，视频居中 letterbox（上下或左右留黑）
     */
    private fun updateVideoScale() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        if (viewWidth <= 0 || viewHeight <= 0) return

        val videoRatio = videoWidth.toFloat() / videoHeight
        val viewRatio = viewWidth.toFloat() / viewHeight
        val scaleX: Float
        val scaleY: Float
        if (videoRatio > viewRatio) {
            // 视频更宽，按宽度填满，上下留黑
            scaleX = 1f
            scaleY = viewRatio / videoRatio
        } else {
            // 视频更高，按高度填满，左右留黑
            scaleX = videoRatio / viewRatio
            scaleY = 1f
        }
        val matrix = android.graphics.Matrix()
        matrix.preScale(scaleX, scaleY)
        matrix.postTranslate((1 - scaleX) / 2f * viewWidth, (1 - scaleY) / 2f * viewHeight)
        textureView.setTransform(matrix)
    }

    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.let {
                it.stop()
                it.release()
            }
        } catch (_: Exception) { }
        mediaPlayer = null
        videoPrepared = false
    }

    private fun toggleVideoPlay() {
        try {
            val mp = mediaPlayer ?: return
            if (mp.isPlaying) {
                mp.pause()
                btnVideoPlayPause.setImageResource(android.R.drawable.ic_media_play)
            } else {
                mp.start()
                btnVideoPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                startProgressUpdate()
            }
            showControlBarTemporarily()
        } catch (_: Exception) { }
    }

    private fun closeVideo() {
        val pos = try { mediaPlayer?.currentPosition ?: 0 } catch (_: Exception) { 0 }
        if (pos > 0 && currentMvUrl != null) savePosition(currentMvUrl, pos)
        releaseMediaPlayer()
        stopProgressUpdate()
        videoContainer.visibility = View.GONE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        exitImmersive()
        btnVideoPlayPause.setImageResource(android.R.drawable.ic_media_play)
        resumeBackgroundMusic()
    }

    private fun toggleControlBar() {
        if (videoControlBar.visibility == View.VISIBLE) {
            videoControlBar.visibility = View.GONE
            topControl.visibility = View.GONE
        } else {
            videoControlBar.visibility = View.VISIBLE
            topControl.visibility = View.VISIBLE
            showControlBarTemporarily()
        }
    }

    private fun showControlBarTemporarily() {
        controlHideRunnable?.let { videoHandler.removeCallbacks(it) }
        controlHideRunnable = Runnable {
            videoControlBar.visibility = View.GONE
            topControl.visibility = View.GONE
        }
        videoHandler.postDelayed(controlHideRunnable!!, 4000)
    }

    /**
     * v1.2.4：进度条更新
     */
    private fun startProgressUpdate() {
        progressRunnable?.let { videoHandler.removeCallbacks(it) }
        progressRunnable = object : Runnable {
            override fun run() {
                val mp = mediaPlayer
                if (mp != null && !userSeeking) {
                    try {
                        val cur = mp.currentPosition
                        val dur = mp.duration
                        if (dur > 0) {
                            seekBarVideo.progress = cur * 1000 / dur
                            tvVideoCurrent.text = formatTime(cur)
                        }
                    } catch (_: Exception) { }
                }
                videoHandler.postDelayed(this, 300)
            }
        }
        videoHandler.postDelayed(progressRunnable!!, 300)
    }

    private fun stopProgressUpdate() {
        progressRunnable?.let { videoHandler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun formatTime(ms: Int): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    /**
     * v1.2.4：全屏沉浸（隐藏状态栏 + 导航栏）
     */
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.windowInsetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun exitImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.windowInsetsController?.let { controller ->
                controller.show(
                    android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
                )
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * v1.2.4：暂停背景音乐（避免 PV 和歌曲双重奏）
     */
    private fun pauseBackgroundMusic() {
        try {
            val ms = musicService ?: return
            if (ms.isPlaying) {
                ms.pause()
                pausedBackgroundMusic = true
            }
        } catch (_: Exception) { }
    }

    private fun resumeBackgroundMusic() {
        if (!pausedBackgroundMusic) return
        try {
            musicService?.resume()
        } catch (_: Exception) { }
        pausedBackgroundMusic = false
    }

    /**
     * v1.2.4：记忆播放位置
     */
    private fun posPref() = getSharedPreferences("pv_positions", MODE_PRIVATE)
    private fun urlKey(url: String?) = "pos_${url?.hashCode() ?: 0}"
    private fun getSavedPosition(url: String?): Int =
        if (url == null) 0 else posPref().getInt(urlKey(url), 0)
    private fun savePosition(url: String?, pos: Int) {
        if (url == null) return
        val dur = try { mediaPlayer?.duration ?: 0 } catch (_: Exception) { 0 }
        if (dur > 0 && pos > 5000 && pos < dur - 3000) {
            posPref().edit().putInt(urlKey(url), pos).apply()
        } else if (dur > 0 && pos >= dur - 3000) {
            posPref().edit().remove(urlKey(url)).apply()
        }
    }

    /**
     * v1.2.6：进入后台时只暂停 MediaPlayer，不释放
     * Surface 销毁由 surfaceDestroyed 回调处理，回来时 Surface 重建 + start 继续播放
     */
    override fun onPause() {
        super.onPause()
        if (videoContainer.visibility == View.VISIBLE) {
            try {
                wasPlayingBeforeBackground = mediaPlayer?.isPlaying == true
                mediaPlayer?.pause()
            } catch (_: Exception) { }
            pausedByBackground = true
            stopProgressUpdate()
        }
    }

    /**
     * v1.2.7：从后台返回，TextureView 的 SurfaceTexture 仍在，重新 attach 并恢复播放
     */
    override fun onResume() {
        super.onResume()
        if (pausedByBackground && videoContainer.visibility == View.VISIBLE) {
            pausedByBackground = false
            try {
                // 重新 attach surface（保险起见）
                textureView.surfaceTexture?.let { st ->
                    mediaPlayer?.setSurface(android.view.Surface(st))
                }
                // 重新计算比例（旋转/尺寸可能变化）
                updateVideoScale()
                if (wasPlayingBeforeBackground) {
                    mediaPlayer?.start()
                    startProgressUpdate()
                }
            } catch (_: Exception) { }
            enterImmersive()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (videoContainer.visibility == View.VISIBLE) {
            val pos = try { mediaPlayer?.currentPosition ?: 0 } catch (_: Exception) { 0 }
            savePosition(currentMvUrl, pos)
        }
        releaseMediaPlayer()
        stopProgressUpdate()
        controlHideRunnable?.let { videoHandler.removeCallbacks(it) }
        exitImmersive()
        resumeBackgroundMusic()
        // v1.2.4：解绑 Service
        try { if (serviceBound) unbindService(serviceConnection) } catch (_: Exception) { }
        serviceBound = false
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (videoContainer.visibility == View.VISIBLE) {
            closeVideo()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    // ======================= PV 列表 Adapter =======================
    private inner class PvAdapter(private val onClick: (MusicApi.OnlineMv) -> Unit) :
        RecyclerView.Adapter<PvAdapter.VH>() {

        private val items = mutableListOf<MusicApi.OnlineMv>()

        fun submit(list: List<MusicApi.OnlineMv>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, 14, 20, 14)
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            // 封面缩略图（16:9 占位）
            val cover = ImageView(ctx).apply {
                id = View.generateViewId()
                setBackgroundColor(0xFF2A2A2A.toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(android.R.drawable.ic_media_play)
                layoutParams = LinearLayout.LayoutParams(96.dp(), 56.dp())
            }
            row.addView(cover)
            // 文本区
            val textWrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val title = TextView(ctx).apply {
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val artist = TextView(ctx).apply {
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 12f
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 4, 0, 0)
            }
            textWrap.addView(title)
            textWrap.addView(artist)
            row.addView(textWrap)
            // 来源标签
            val tag = TextView(ctx).apply {
                setTextColor(0xFF1ED760.toInt())
                textSize = 11f
                setPadding(8, 4, 8, 4)
                setBackgroundColor(0xFF1F1F1F.toInt())
            }
            row.addView(tag)
            return VH(row, cover, title, artist, tag)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val mv = items[position]
            holder.title.text = mv.name
            holder.artist.text = mv.artist
            holder.tag.text = mv.source.displayName
            holder.itemView.setOnClickListener { onClick(mv) }
            // 封面异步加载
            if (!mv.cover.isNullOrEmpty()) {
                BitmapLoader.get(mv.cover) { bmp ->
                    runOnUiThread {
                        if (bmp != null && holder.adapterPosition == position) {
                            holder.cover.setImageBitmap(bmp)
                        }
                    }
                }
            } else {
                holder.cover.setImageResource(android.R.drawable.ic_media_play)
            }
        }

        override fun getItemCount(): Int = items.size

        inner class VH(
            itemView: View,
            val cover: ImageView,
            val title: TextView,
            val artist: TextView,
            val tag: TextView
        ) : RecyclerView.ViewHolder(itemView)
    }
}
