package com.example.musicplugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * 桌面歌词服务 v1.2.8
 *
 * 仿酷狗桌面歌词：
 * - 悬浮于所有应用上层，显示当前歌词行 + 下一句
 * - 可拖动定位，点击锁定/关闭
 * - 绑定 MusicService 获取播放进度，自动滚动歌词
 */
class DesktopLyricService : Service() {

    companion object {
        const val CHANNEL_ID = "desktop_lyric"
        const val NOTIFICATION_ID = 2002

        // 静态歌词数据，由 MainActivity 更新
        @Volatile
        var lyricLines: List<LyricParser.LyricLine> = emptyList()
        @Volatile
        var songTitle: String = ""

        fun canDrawOverlays(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else true
        }
    }

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var tvCurrent: TextView? = null
    private var tvNext: TextView? = null
    private var tvTitle: TextView? = null
    private var controlBar: LinearLayout? = null
    private var btnLock: TextView? = null
    private var btnClose: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private var musicService: MusicService? = null
    private var serviceBound = false
    private var locked = false
    private var lastIdx = -1

    private val lyricParser = LyricParser()

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateLyric()
            handler.postDelayed(this, 100)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MusicService.MusicBinder
            musicService = binder?.getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            serviceBound = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // 绑定 MusicService 获取播放进度
        val intent = Intent(this, MusicService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createFloatingView()

        handler.post(updateRunnable)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "桌面歌词", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingContent = android.app.PendingIntent.getActivity(
            this, 0, contentIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("桌面歌词")
            .setContentText("桌面歌词运行中")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingContent)
            .setOngoing(true)
            .build()
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
        )
    }

    private fun createFloatingView() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 歌曲标题
        tvTitle = TextView(this).apply {
            text = songTitle.ifEmpty { "MUSIC plan" }
            setTextColor(Color.parseColor("#AAFFFFFF"))
            textSize = 12f
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
            setPadding(dp(12f).toInt(), dp(2f).toInt(), dp(12f).toInt(), dp(2f).toInt())
        }
        container.addView(tvTitle)

        // 控制栏（锁定/关闭）
        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(4f).toInt(), 0, dp(4f).toInt())
        }
        btnLock = TextView(this).apply {
            text = "🔒"
            textSize = 16f
            setPadding(dp(16f).toInt(), dp(4f).toInt(), dp(16f).toInt(), dp(4f).toInt())
            setOnClickListener {
                locked = !locked
                btnLock?.text = if (locked) "🔓" else "🔒"
                controlBar?.visibility = View.GONE
            }
        }
        btnClose = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(16f).toInt(), dp(4f).toInt(), dp(16f).toInt(), dp(4f).toInt())
            setOnClickListener { stopSelf() }
        }
        controlBar!!.addView(btnLock)
        controlBar!!.addView(btnClose)
        container.addView(controlBar)

        // 当前行歌词
        tvCurrent = TextView(this).apply {
            text = "暂无歌词"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(6f, 2f, 2f, Color.BLACK)
            setPadding(dp(16f).toInt(), dp(2f).toInt(), dp(16f).toInt(), dp(2f).toInt())
            gravity = Gravity.CENTER
            maxLines = 1
        }
        container.addView(tvCurrent)

        // 下一句歌词
        tvNext = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#88FFFFFF"))
            textSize = 14f
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
            setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), dp(4f).toInt())
            gravity = Gravity.CENTER
            maxLines = 1
        }
        container.addView(tvNext)

        rootView = container

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(120f).toInt()
        }

        // 拖动 + 点击切换控制栏
        var initialY = 0
        var initialTouchY = 0f
        var isDragging = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!locked) {
                        val dy = event.rawY - initialTouchY
                        if (dy > 10 || dy < -10) isDragging = true
                        params.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(container, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // 点击：切换控制栏显示
                        controlBar?.visibility =
                            if (controlBar?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(container, params)
    }

    private fun updateLyric() {
        val lines = lyricLines
        val pos = musicService?.getCurrentPosition()?.toLong() ?: return

        if (lines.isEmpty()) {
            if (lastIdx != -2) {
                tvCurrent?.text = songTitle.ifEmpty { "MUSIC plan" }
                tvNext?.text = ""
                lastIdx = -2
            }
            return
        }

        val idx = lyricParser.getIndexAtTime(pos, lines)
        if (idx == lastIdx) return
        lastIdx = idx

        if (idx >= 0 && idx < lines.size) {
            tvCurrent?.text = lines[idx].text
            // 显示翻译（如果有）
            val nextText = if (idx + 1 < lines.size) lines[idx + 1].text else ""
            tvNext?.text = nextText
        }

        tvTitle?.text = songTitle.ifEmpty { "MUSIC plan" }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(updateRunnable)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        rootView?.let { windowManager?.removeView(it) }
        rootView = null
        super.onDestroy()
    }
}
