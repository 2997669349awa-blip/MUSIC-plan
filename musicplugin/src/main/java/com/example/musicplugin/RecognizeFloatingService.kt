package com.example.musicplugin

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * v1.1.9：听歌识曲全局悬浮球服务（酷狗风格动画版）
 *
 * 悬浮球状态机：
 * 1. IDLE        绿色圆球 + 白色音符 ♪，长按 2 秒关闭
 * 2. RECORDING   音符 360° 旋转淡出 → 5 条音浪柱上下跳动（红色），录音 10s，显示 Xs/10s
 * 3. RECOGNIZING 音浪柱淡出 → 旋转加载圈（橙色），显示 识别中...
 * 4. RESULT      加载圈淡出 → 绿色对勾 ✓ 弹性缩放 → 0.5s 后展开结果卡片，点击播放，10s 后自动复位
 * 5. FAILED      球下方显示错误文字，3s 后复位 IDLE
 */
class RecognizeFloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var handler: Handler
    private var floatingContainer: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    // 悬浮球视图
    private lateinit var ballView: FrameLayout
    private lateinit var ballDrawable: GradientDrawable
    private lateinit var iconView: ImageView          // 应用图标（IDLE 显示）
    private lateinit var barsContainer: LinearLayout // 音浪柱容器
    private val bars = mutableListOf<View>()
    private lateinit var progressBar: ProgressBar    // 识别中转圈
    private lateinit var tvCheck: TextView           // 对勾 ✓
    private lateinit var tvCross: TextView           // 叉号 ✗（失败显示）
    private lateinit var tvStatus: TextView          // 球下方状态文字
    private lateinit var resultPanel: LinearLayout   // 结果展开卡片
    private lateinit var tvResultName: TextView
    private lateinit var tvResultArtist: TextView

    private var audioFile: File? = null
    private var recorder: AudioRecorder? = null
    private var matchedSong: MusicApi.OnlineSong? = null

    // 回调 / 动画引用
    private var longPressRunnable: Runnable? = null
    private var recordTickRunnable: Runnable? = null
    private var recognizeTimeoutRunnable: Runnable? = null
    private var resultResetRunnable: Runnable? = null
    private var failedResetRunnable: Runnable? = null
    private var noteTransition: AnimatorSet? = null
    private val barAnimators = mutableListOf<ValueAnimator>()

    private enum class State { IDLE, RECORDING, RECOGNIZING, RESULT, FAILED }
    private var currentState = State.IDLE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())
        startForegroundNotify()
        showFloatingBall()
    }

    private fun startForegroundNotify() {
        val channelId = "recognize_floating"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "听歌识曲悬浮球", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("听歌识曲悬浮球已开启")
            .setContentText("点击悬浮球识别当前歌曲")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(2001, notification)
    }

    private fun showFloatingBall() {
        val ballSize = dp(48)

        // 整体容器：悬浮球 + 下方状态文字 + 结果卡片
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // ===== 悬浮球（FrameLayout，内含 音符/音浪/转圈/对勾）=====
        ballDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_GREEN)
            setStroke(dp(2), Color.WHITE)
        }
        ballView = FrameLayout(this).apply {
            background = ballDrawable
            isClickable = true
            isFocusable = true
        }
        container.addView(ballView, LinearLayout.LayoutParams(ballSize, ballSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // 应用图标（IDLE 显示）
        iconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_music_plugin_foreground)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.VISIBLE
        }
        ballView.addView(iconView, FrameLayout.LayoutParams(dp(30), dp(30)).apply {
            gravity = Gravity.CENTER
        })

        // 音浪柱容器（RECORDING 显示）
        barsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        ballView.addView(barsContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, dp(28)
        ).apply { gravity = Gravity.CENTER })
        for (i in 0 until 5) {
            val bar = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.WHITE)
                    cornerRadius = dp(2).toFloat()
                }
            }
            val lp = LinearLayout.LayoutParams(dp(3), dp(8)).apply {
                marginStart = if (i == 0) 0 else dp(2)
                marginEnd = dp(2)
            }
            barsContainer.addView(bar, lp)
            bars.add(bar)
        }

        // 识别中转圈（RECOGNIZING 显示）
        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
        }
        ballView.addView(progressBar, FrameLayout.LayoutParams(dp(24), dp(24)).apply {
            gravity = Gravity.CENTER
        })

        // 对勾 ✓（RESULT 显示）
        tvCheck = TextView(this).apply {
            text = "✓"
            setTextColor(COLOR_GREEN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        ballView.addView(tvCheck, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })

        // 叉号 ✗（FAILED 显示）
        tvCross = TextView(this).apply {
            text = "✗"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        ballView.addView(tvCross, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })

        // 球下方状态文字
        tvStatus = TextView(this).apply {
            text = ""
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
            visibility = View.GONE
        }
        container.addView(tvStatus, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        // 结果卡片（RESULT 展开后显示）
        resultPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundDrawable(GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xDD1A1A1A.toInt())
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0x33FFFFFF)
            })
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
            isClickable = true
        }
        tvResultName = TextView(this).apply {
            text = ""
            setTextColor(COLOR_GREEN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setSingleLine(true)
            setPadding(0, 0, dp(12), 0)
        }
        resultPanel.addView(tvResultName)
        tvResultArtist = TextView(this).apply {
            text = ""
            setTextColor(0xFFCCCCCC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            setSingleLine(true)
            setPadding(0, dp(2), dp(12), 0)
        }
        resultPanel.addView(tvResultArtist)
        val playHint = TextView(this).apply {
            text = "点击播放"
            setTextColor(COLOR_GREEN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        resultPanel.addView(playHint)
        container.addView(resultPanel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(6)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 40
        layoutParams.y = 400

        // 拖动 + 长按关闭 + 点击
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var longPressTriggered = false

        ballView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    longPressTriggered = false
                    longPressRunnable = Runnable {
                        if (!isDragging && currentState == State.IDLE) {
                            longPressTriggered = true
                            stopSelf()
                        }
                    }
                    longPressRunnable?.let { handler.postDelayed(it, 2000) }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 225) {
                        isDragging = true
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                    }
                    layoutParams.x = initialX + dx.toInt()
                    layoutParams.y = initialY + dy.toInt()
                    try { windowManager.updateViewLayout(container, layoutParams) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    if (isDragging || longPressTriggered) return@setOnTouchListener true
                    when (currentState) {
                        State.IDLE -> startRecording()
                        State.RESULT -> playMatchedSong()
                        else -> {}
                    }
                }
            }
            true
        }

        resultPanel.setOnClickListener {
            if (currentState == State.RESULT) playMatchedSong()
        }

        floatingContainer = container
        try {
            windowManager.addView(container, layoutParams)
        } catch (e: Exception) {
            // 无悬浮窗权限
        }
    }

    // ==================== 状态切换 + 动画 ====================

    private fun setBallStyle(bgColor: Int, strokeColor: Int) {
        ballDrawable.setColor(bgColor)
        ballDrawable.setStroke(dp(2), strokeColor)
    }

    private fun enterIdle() {
        currentState = State.IDLE
        setBallStyle(COLOR_GREEN, Color.WHITE)
        tvStatus.visibility = View.GONE
        iconView.visibility = View.VISIBLE
        iconView.rotation = 0f
        iconView.alpha = 0f
        iconView.animate().alpha(1f).setDuration(200).start()
        tvCross.visibility = View.GONE
    }

    private fun enterRecording() {
        currentState = State.RECORDING
        setBallStyle(COLOR_RED, Color.WHITE)
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "0s/12s"

        // 图标 360° 旋转 + 淡出，结束后显示音浪柱
        noteTransition?.cancel()
        val rotate = ObjectAnimator.ofFloat(iconView, View.ROTATION, 0f, 360f).apply { duration = 400 }
        val fade = ObjectAnimator.ofFloat(iconView, View.ALPHA, 1f, 0f).apply { duration = 400 }
        noteTransition = AnimatorSet().apply {
            playTogether(rotate, fade)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    iconView.visibility = View.GONE
                    iconView.alpha = 1f
                    iconView.rotation = 0f
                    showBars()
                }
            })
            start()
        }
    }

    private fun showBars() {
        barsContainer.visibility = View.VISIBLE
        barsContainer.alpha = 0f
        barsContainer.animate().alpha(1f).setDuration(150).start()
        startBarsAnimation()
    }

    private fun enterRecognizing() {
        currentState = State.RECOGNIZING
        setBallStyle(COLOR_ORANGE, Color.WHITE)
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "识别中..."

        // 保留音浪柱继续跳动（随音律动），不停止
        if (barsContainer.visibility != View.VISIBLE) {
            showBars()
        }
    }

    private fun showSpinner() {
        progressBar.visibility = View.VISIBLE
        progressBar.alpha = 0f
        progressBar.animate().alpha(1f).setDuration(150).start()
    }

    private fun enterResult(song: MusicApi.OnlineSong) {
        currentState = State.RESULT
        resultResetRunnable?.let { handler.removeCallbacks(it) }

        val showCheck = {
            setBallStyle(Color.WHITE, COLOR_GREEN)
            // 停止音浪柱并隐藏
            stopBarsAnimation()
            barsContainer.visibility = View.GONE
            barsContainer.alpha = 1f
            // 对勾弹性缩放
            tvCheck.visibility = View.VISIBLE
            tvCheck.alpha = 1f
            tvCheck.scaleX = 0f
            tvCheck.scaleY = 0f
            val pvhX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0f, 1f)
            val pvhY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0f, 1f)
            ObjectAnimator.ofPropertyValuesHolder(tvCheck, pvhX, pvhY).apply {
                duration = 400
                interpolator = OvershootInterpolator(2f)
                start()
            }
            // 0.5s 后展开结果卡片
            handler.postDelayed({
                if (currentState != State.RESULT) return@postDelayed
                tvResultName.text = song.name
                tvResultArtist.text = song.artist
                resultPanel.visibility = View.VISIBLE
                resultPanel.alpha = 0f
                resultPanel.scaleX = 0.6f
                resultPanel.scaleY = 0.6f
                resultPanel.pivotY = 0f
                resultPanel.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(280)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            }, 500)
            // 10s 后自动复位
            resultResetRunnable = Runnable { resetToIdle() }
            handler.postDelayed(resultResetRunnable!!, 10000)
        }

        // 音浪柱淡出后显示对勾
        if (barsContainer.visibility == View.VISIBLE) {
            stopBarsAnimation()
            barsContainer.animate().alpha(0f).setDuration(180)
                .withEndAction { showCheck() }.start()
        } else {
            showCheck()
        }
    }

    private fun enterFailed(msg: String) {
        currentState = State.FAILED
        failedResetRunnable?.let { handler.removeCallbacks(it) }
        stopBarsAnimation()
        if (barsContainer.visibility == View.VISIBLE) {
            barsContainer.animate().alpha(0f).setDuration(120)
                .withEndAction { barsContainer.visibility = View.GONE; barsContainer.alpha = 1f }
                .start()
        }
        setBallStyle(COLOR_RED, Color.WHITE)
        resultPanel.visibility = View.GONE
        // 显示 ✗ 叉号
        tvCross.visibility = View.VISIBLE
        tvCross.alpha = 0f
        tvCross.scaleX = 0f
        tvCross.scaleY = 0f
        val pvhX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0f, 1f)
        val pvhY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0f, 1f)
        val alphaAnim = ObjectAnimator.ofFloat(tvCross, View.ALPHA, 0f, 1f)
        ObjectAnimator.ofPropertyValuesHolder(tvCross, pvhX, pvhY).apply {
            duration = 300
            interpolator = OvershootInterpolator(2f)
            start()
        }
        alphaAnim.duration = 200
        alphaAnim.start()
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "识别失败"
        failedResetRunnable = Runnable { resetToIdle() }
        handler.postDelayed(failedResetRunnable!!, 3000)
    }

    private fun resetToIdle() {
        cancelPendingRunnables()
        stopBarsAnimation()
        noteTransition?.cancel()
        noteTransition = null
        matchedSong = null
        barsContainer.visibility = View.GONE
        barsContainer.alpha = 1f
        progressBar.visibility = View.GONE
        progressBar.alpha = 1f
        tvCheck.visibility = View.GONE
        tvCheck.scaleX = 1f
        tvCheck.scaleY = 1f
        tvCross.visibility = View.GONE
        tvCross.scaleX = 1f
        tvCross.scaleY = 1f
        resultPanel.visibility = View.GONE
        resultPanel.alpha = 1f
        resultPanel.scaleX = 1f
        resultPanel.scaleY = 1f
        enterIdle()
    }

    // ==================== 音浪柱动画 ====================

    private fun startBarsAnimation() {
        stopBarsAnimation()
        val minH = dp(6)
        val maxH = dp(24)
        bars.forEachIndexed { index, bar ->
            val targetMax = (minH + (maxH - minH) * (0.5 + Math.random() * 0.5)).toInt()
            val va = ValueAnimator.ofInt(minH, targetMax).apply {
                duration = (180 + (Math.random() * 280)).toLong()
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                startDelay = (index * 40 + (Math.random() * 80)).toLong()
                addUpdateListener { a ->
                    val lp = bar.layoutParams
                    lp.height = a.animatedValue as Int
                    bar.layoutParams = lp
                }
            }
            va.start()
            barAnimators.add(va)
        }
    }

    private fun stopBarsAnimation() {
        barAnimators.forEach { it.cancel() }
        barAnimators.clear()
    }

    // ==================== 录音 / 识别流程 ====================

    private fun startRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (android.content.pm.PackageManager.PERMISSION_GRANTED !=
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)) {
                tvStatus.text = "无录音权限"
                tvStatus.visibility = View.VISIBLE
                handler.postDelayed({ resetToIdle() }, 2000)
                return
            }
        }

        val f = File(cacheDir, "recognize_floating_${System.currentTimeMillis()}.wav")
        audioFile = f
        try {
            recorder = AudioRecorder(f).also { it.start() }
        } catch (e: SecurityException) {
            enterFailed("录音失败")
            return
        }

        enterRecording()

        val duration = 12000L
        val tick = 100L
        var elapsed = 0L
        recordTickRunnable = object : Runnable {
            override fun run() {
                elapsed += tick
                tvStatus.text = "${elapsed / 1000}s/12s"
                if (elapsed < duration) {
                    handler.postDelayed(this, tick)
                } else {
                    stopAndRecognize()
                }
            }
        }
        handler.postDelayed(recordTickRunnable!!, tick)
    }

    private fun stopAndRecognize() {
        recorder?.stop()
        recorder = null
        recordTickRunnable?.let { handler.removeCallbacks(it) }
        recordTickRunnable = null

        enterRecognizing()

        val f = audioFile
        if (f == null || !f.exists()) {
            enterFailed("录音失败")
            return
        }

        var callbackCalled = false

        // UI 超时兜底：20s 后强制超时（比 API 超时 15s 多 5s 缓冲）
        recognizeTimeoutRunnable?.let { handler.removeCallbacks(it) }
        recognizeTimeoutRunnable = Runnable {
            if (!callbackCalled && currentState == State.RECOGNIZING) {
                enterFailed("识别超时")
            }
        }
        handler.postDelayed(recognizeTimeoutRunnable!!, 20000)

        RecognizeApi.recognize(this, f) { result ->
            handler.post {
                callbackCalled = true
                recognizeTimeoutRunnable?.let { handler.removeCallbacks(it) }
                recognizeTimeoutRunnable = null
                if (result.success) {
                    searchAndShowResult(result.title ?: "", result.artist ?: "")
                } else {
                    enterFailed(result.errorMsg ?: "识别失败")
                }
            }
        }
    }

    private fun searchAndShowResult(title: String, artist: String) {
        val keyword = if (artist.isNotEmpty()) "$title $artist" else title
        MusicApi.search(keyword) { list, err ->
            handler.post {
                if (err != null || list == null) {
                    enterFailed("回查失败")
                    return@post
                }
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
                    enterResult(match)
                } else {
                    enterFailed("未找到歌曲")
                }
            }
        }
    }

    private fun playMatchedSong() {
        val s = matchedSong ?: return
        resultResetRunnable?.let { handler.removeCallbacks(it) }
        resultResetRunnable = null
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("recognize_song_id", s.id)
            putExtra("recognize_song_name", s.name)
            putExtra("recognize_song_artist", s.artist)
        }
        startActivity(intent)
        resetToIdle()
    }

    // ==================== 工具 ====================

    private fun cancelPendingRunnables() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        recordTickRunnable?.let { handler.removeCallbacks(it) }
        recognizeTimeoutRunnable?.let { handler.removeCallbacks(it) }
        resultResetRunnable?.let { handler.removeCallbacks(it) }
        failedResetRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
        recordTickRunnable = null
        recognizeTimeoutRunnable = null
        resultResetRunnable = null
        failedResetRunnable = null
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onDestroy() {
        super.onDestroy()
        cancelPendingRunnables()
        stopBarsAnimation()
        noteTransition?.cancel()
        recorder?.stop()
        recorder = null
        floatingContainer?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        floatingContainer = null
    }

    companion object {
        private val COLOR_GREEN = 0xFF1ED760.toInt()
        private val COLOR_RED = 0xFFE53935.toInt()
        private val COLOR_ORANGE = 0xFFFF9800.toInt()

        fun canDrawOverlays(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                Settings.canDrawOverlays(ctx)
            else true
        }
    }
}
