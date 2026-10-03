package com.example.musicplugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver

/**
 * 音乐播放服务 v1.0.6
 *
 * 照抄酷狗/网易云方案：
 * - Service 持有真正的 MediaPlayer，负责实际播放
 * - Activity 通过 Binder 绑定 Service 控制播放
 * - 通知栏所有按钮（播放/暂停/上一首/下一首/关闭）通过 getService 发给 Service 自己处理
 * - 「关闭」按钮不再依赖 Activity，直接在 Service 内停止播放 + stopSelf
 * 这样即使 Activity 被系统杀死，通知栏控制依然有效。
 */
class MusicService : Service() {

    companion object {
        private const val TAG = "MusicService"
        const val CHANNEL_ID = "music_plugin_playback"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.example.musicplugin.PLAY"
        const val ACTION_PAUSE = "com.example.musicplugin.PAUSE"
        const val ACTION_NEXT = "com.example.musicplugin.NEXT"
        const val ACTION_PREV = "com.example.musicplugin.PREV"
        const val ACTION_STOP = "com.example.musicplugin.STOP"
        const val ACTION_PLAY_PAUSE = "com.example.musicplugin.PLAY_PAUSE"
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_IS_ONLINE = "is_online"
        const val EXTRA_PIC_URL = "pic_url"
    }

    /**
     * 回调接口：通知 Activity 播放状态变化，由 Activity 决定 UI 更新和切歌逻辑
     */
    interface MusicCallback {
        fun onPrepared(title: String, artist: String) {}
        fun onCompletion() {}
        fun onError(what: Int, extra: Int, url: String?) {}
        fun onPlayStateChanged(isPlaying: Boolean) {}
        fun onStop() {}
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    private val binder = MusicBinder()
    var callback: MusicCallback? = null

    private var mediaPlayer: MediaPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var currentTitle = "MUSIC plan"
    private var currentArtist = ""
    private var currentUrl: String? = null
    private var currentPicUrl: String? = null
    private var currentArtwork: android.graphics.Bitmap? = null
    private var prepared = false

    // 通知栏进度条定时刷新
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    // v1.2.1：加密音乐解密用后台线程，避免阻塞 UI
    private val decodeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val notificationUpdateRunnable = object : Runnable {
        override fun run() {
            if (isPlaying) {
                updateNotification()
                handler.postDelayed(this, 1000)
            }
        }
    }

    val isPlaying: Boolean
        get() = prepared && (mediaPlayer?.isPlaying == true)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        createMediaSession()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun createMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicPlugin").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { callback?.onCompletion() }
                override fun onSkipToPrevious() { callback?.onCompletion() }
            })
            isActive = true
        }
    }

    /**
     * 播放（本地用 prepare，在线用 prepareAsync）
     *
     * v1.0.7：增加 picUrl 参数，用于通知栏显示专辑封面（仿网易云）
     */
    fun play(path: String, title: String = "", artist: String = "",
             isOnline: Boolean = false, picUrl: String? = null) {
        currentTitle = if (title.isNotBlank()) title else "正在播放"
        currentArtist = artist
        currentUrl = path
        currentPicUrl = picUrl
        currentArtwork = null
        prepared = false

        // v1.2.1：本地加密格式先解密到临时文件再播放
        if (!isOnline && EncryptedMusicDecoder.isEncrypted(path)) {
            updateNotification()
            decodeExecutor.execute {
                val decoded = EncryptedMusicDecoder.decodeToTempFile(path, cacheDir)
                handler.post {
                    if (decoded != null) {
                        startPlayback(decoded.absolutePath, title, artist, picUrl, isOnline = false)
                    } else {
                        // 解密失败：尝试直接播放原文件（加密文件通常无法播放，会触发 onError）
                        startPlayback(path, title, artist, picUrl, isOnline = false)
                    }
                }
            }
            return
        }
        startPlayback(path, title, artist, picUrl, isOnline)
    }

    private fun startPlayback(path: String, title: String, artist: String, picUrl: String?, isOnline: Boolean) {
        // v1.0.7：异步加载专辑封面，用于通知栏大图
        if (!picUrl.isNullOrEmpty()) {
            BitmapLoader.get(picUrl) { bmp ->
                if (bmp != null) {
                    currentArtwork = bmp
                    // 更新 MediaSession 元数据（锁屏也显示封面）
                    updateMetadata()
                    updateNotification()
                }
            }
        }

        try {
            mediaPlayer?.release()
        } catch (e: Exception) {}

        mediaPlayer = MediaPlayer()
        try {
            mediaPlayer!!.setDataSource(path)
        } catch (e: Exception) {
            Log.e(TAG, "setDataSource 失败: ${e.message}")
            callback?.onError(-1, -1, path)
            return
        }

        mediaPlayer!!.setOnCompletionListener {
            callback?.onCompletion()
        }
        mediaPlayer!!.setOnErrorListener { _, what, extra ->
            callback?.onError(what, extra, path)
            true
        }
        mediaPlayer!!.setOnPreparedListener { mp ->
            prepared = true
            mp.start()
            updateMetadata()
            updateNotification()
            handler.removeCallbacks(notificationUpdateRunnable)
            handler.postDelayed(notificationUpdateRunnable, 1000)
            callback?.onPrepared(title, artist)
            callback?.onPlayStateChanged(true)
        }

        try {
            if (isOnline) {
                mediaPlayer!!.prepareAsync()
            } else {
                mediaPlayer!!.prepare()
                // 本地 prepare 是同步的，onPrepared 已触发
            }
        } catch (e: Exception) {
            Log.e(TAG, "prepare 失败: ${e.message}")
            callback?.onError(-1, -1, path)
        }

        // 立即更新通知栏标题（在线加载中也显示歌名）
        updateNotification()
    }

    fun pause() {
        try { mediaPlayer?.pause() } catch (e: Exception) {}
        handler.removeCallbacks(notificationUpdateRunnable)
        updateNotification()
        callback?.onPlayStateChanged(false)
    }

    fun resume() {
        if (!prepared) return
        try { mediaPlayer?.start() } catch (e: Exception) {}
        handler.removeCallbacks(notificationUpdateRunnable)
        handler.postDelayed(notificationUpdateRunnable, 1000)
        updateNotification()
        callback?.onPlayStateChanged(true)
    }

    fun stop() {
        callback?.onStop()
        handler.removeCallbacks(notificationUpdateRunnable)
        try { mediaPlayer?.release() } catch (e: Exception) {}
        mediaPlayer = null
        prepared = false
        currentUrl = null
        stopForeground(true)
        stopSelf()
    }

    fun seekTo(pos: Int) {
        try { mediaPlayer?.seekTo(pos) } catch (e: Exception) {}
    }

    /**
     * v1.0.8：播放后异步更新封面（picUrl 兜底获取后调用）
     */
    fun updateArtwork(picUrl: String) {
        currentPicUrl = picUrl
        if (currentArtwork != null) return  // 已有封面则不重复加载
        BitmapLoader.get(picUrl) { bmp ->
            if (bmp != null) {
                currentArtwork = bmp
                updateMetadata()
                updateNotification()
            }
        }
    }

    fun getCurrentPosition(): Int = try { mediaPlayer?.currentPosition ?: 0 } catch (e: Exception) { 0 }
    fun getDuration(): Int = try { mediaPlayer?.duration ?: 0 } catch (e: Exception) { 0 }

    private fun updateMetadata() {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, getDuration().toLong())
        currentArtwork?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it) }
        mediaSession?.setMetadata(builder.build())
    }

    private fun updateNotification() {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, getCurrentPosition().toLong(), 1f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .build()
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingContent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause, "暂停",
                buildActionPendingIntent(ACTION_PAUSE)
            ).build()
        } else {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play, "播放",
                buildActionPendingIntent(ACTION_PLAY)
            ).build()
        }

        // v1.0.6：关闭按钮直接用 getService 发给 Service 自己处理，不再依赖 Activity
        val stopPendingIntent = buildActionPendingIntent(ACTION_STOP)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            // v1.0.7：仿网易云，通知栏显示专辑封面大图
            .setLargeIcon(currentArtwork)
            .setContentIntent(pendingContent)
            .addAction(android.R.drawable.ic_media_previous, "上一首",
                buildActionPendingIntent(ACTION_PREV))
            .addAction(playPauseAction)
            .addAction(android.R.drawable.ic_media_next, "下一首",
                buildActionPendingIntent(ACTION_NEXT))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭",
                stopPendingIntent)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                // v1.2.1：紧凑视图显示 上一首/播放暂停/下一首，关闭按钮仅在展开视图显示
                .setShowActionsInCompactView(0, 1, 2))
            .setOngoing(isPlaying)
            .build()
    }

    private fun buildActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val path = intent.getStringExtra(EXTRA_PATH)
                if (path != null) {
                    play(path,
                        intent.getStringExtra(EXTRA_TITLE) ?: "",
                        intent.getStringExtra(EXTRA_ARTIST) ?: "",
                        intent.getBooleanExtra(EXTRA_IS_ONLINE, false),
                        intent.getStringExtra(EXTRA_PIC_URL))
                } else {
                    resume()
                }
            }
            ACTION_PAUSE -> pause()
            ACTION_PLAY_PAUSE -> { if (isPlaying) pause() else resume() }
            ACTION_NEXT -> callback?.onCompletion()
            ACTION_PREV -> callback?.onCompletion()
            ACTION_STOP -> stop()
            null -> { /* 启动 */ }
            else -> MediaButtonReceiver.handleIntent(mediaSession, intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(notificationUpdateRunnable)
        try { mediaPlayer?.release() } catch (e: Exception) {}
        mediaPlayer = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
