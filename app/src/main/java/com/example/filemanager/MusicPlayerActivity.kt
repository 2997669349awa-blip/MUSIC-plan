package com.example.filemanager

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import java.io.File

class MusicPlayerActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var songTitle: TextView
    private lateinit var songArtist: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnPlaylist: ImageButton

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPrepared = false
    private var isRepeat = false
    private var isShuffle = false

    private var playlist: MutableList<File> = mutableListOf()
    private var currentIndex = 0

    private val updateRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { mp ->
                if (isPrepared) {
                    seekBar.progress = mp.currentPosition
                    currentTimeText.text = formatTime(mp.currentPosition)
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_player)

        toolbar = findViewById(R.id.toolbar)
        songTitle = findViewById(R.id.songTitle)
        songArtist = findViewById(R.id.songArtist)
        seekBar = findViewById(R.id.seekBar)
        currentTimeText = findViewById(R.id.currentTime)
        totalTimeText = findViewById(R.id.totalTime)
        btnPlay = findViewById(R.id.btnPlay)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnPlaylist = findViewById(R.id.btnPlaylist)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // 获取播放列表和当前索引
        val filePath = intent.getStringExtra("file_path") ?: run {
            finish()
            return
        }
        val dir = intent.getStringExtra("dir")
        val currentFile = File(filePath)

        // 构建播放列表：同目录下的所有音频文件
        playlist = buildPlaylist(currentFile, dir)
        currentIndex = playlist.indexOfFirst { it.absolutePath == currentFile.absolutePath }
        if (currentIndex < 0) {
            playlist.add(0, currentFile)
            currentIndex = 0
        }

        btnPlay.setOnClickListener {
            if (mediaPlayer?.isPlaying == true) {
                pause()
            } else {
                play()
            }
        }

        btnPrev.setOnClickListener { playPrevious() }
        btnNext.setOnClickListener { playNext() }

        btnRepeat.setOnClickListener {
            isRepeat = !isRepeat
            btnRepeat.setColorFilter(if (isRepeat) 0xFF7c4dff.toInt() else 0xFF888888.toInt())
        }

        btnShuffle.setOnClickListener {
            isShuffle = !isShuffle
            btnShuffle.setColorFilter(if (isShuffle) 0xFF7c4dff.toInt() else 0xFF888888.toInt())
        }

        btnPlaylist.setOnClickListener { showPlaylist() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && isPrepared) {
                    mediaPlayer?.seekTo(progress)
                    currentTimeText.text = formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 开始播放
        loadAndPlay(currentIndex)
    }

    private fun buildPlaylist(currentFile: File, dir: String?): MutableList<File> {
        val parentDir = if (dir != null) File(dir) else currentFile.parentFile ?: return mutableListOf(currentFile)
        if (!parentDir.canRead()) return mutableListOf(currentFile)
        val audioExts = setOf("mp3", "flac", "wav", "ogg", "aac", "m4a", "wma", "opus", "amr", "mid", "midi")
        return parentDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in audioExts }
            ?.sortedBy { it.name.lowercase() }
            ?.toMutableList() ?: mutableListOf(currentFile)
    }

    private fun loadAndPlay(index: Int) {
        if (index < 0 || index >= playlist.size) return
        currentIndex = index
        val file = playlist[index]

        songTitle.text = file.nameWithoutExtension
        songArtist.text = file.parentFile?.name ?: ""

        releasePlayer()
        isPrepared = false

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { mp ->
                    isPrepared = true
                    seekBar.max = mp.duration
                    totalTimeText.text = formatTime(mp.duration)
                    mp.start()
                    btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                    handler.post(updateRunnable)
                }
                setOnCompletionListener {
                    if (isRepeat) {
                        it.start()
                    } else {
                        playNext()
                    }
                }
                setOnErrorListener { _, _, _ ->
                    isPrepared = false
                    btnPlay.setImageResource(android.R.drawable.ic_media_play)
                    false
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            songArtist.text = "播放失败: ${e.message}"
        }
    }

    private fun play() {
        if (isPrepared && mediaPlayer?.isPlaying == false) {
            mediaPlayer?.start()
            btnPlay.setImageResource(android.R.drawable.ic_media_pause)
            handler.post(updateRunnable)
        }
    }

    private fun pause() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            btnPlay.setImageResource(android.R.drawable.ic_media_play)
            handler.removeCallbacks(updateRunnable)
        }
    }

    private fun playNext() {
        if (playlist.isEmpty()) return
        val nextIndex = if (isShuffle) {
            (0 until playlist.size).random()
        } else {
            (currentIndex + 1) % playlist.size
        }
        loadAndPlay(nextIndex)
    }

    private fun playPrevious() {
        if (playlist.isEmpty()) return
        val prevIndex = if (currentIndex > 0) currentIndex - 1 else playlist.size - 1
        loadAndPlay(prevIndex)
    }

    private fun showPlaylist() {
        if (playlist.isEmpty()) return
        val items = playlist.mapIndexed { idx, file ->
            val prefix = if (idx == currentIndex) "▶ " else "   "
            "$prefix${file.nameWithoutExtension}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.playlist)
            .setItems(items) { _, which ->
                loadAndPlay(which)
            }
            .setPositiveButton("OK", null)
            .show()
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }

    private fun releasePlayer() {
        handler.removeCallbacks(updateRunnable)
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }
}
