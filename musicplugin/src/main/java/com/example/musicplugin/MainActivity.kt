package com.example.musicplugin

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.musicplugin.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SongAdapter

    private var allSongs: List<Song> = emptyList()
    private var filteredSongs: List<Song> = emptyList()
    private var playlist: List<Song> = emptyList()
    private var currentIndex = -1

    private var isPlaying = false
    private var playMode = PlayMode.LOOP
    private var currentQuality = Quality.STANDARD

    // 在线搜索
    private var isOnlineMode = false
    private var isFavoritesMode = false
    // v1.1.5：本次会话是否已拒绝扫描本地音乐（拒绝后不再重复弹窗）
    private var hasDeclinedScan = false
    private var onlineSongs: List<MusicApi.OnlineSong> = emptyList()
    private var currentOnlineSong: MusicApi.OnlineSong? = null
    private var currentPlayingSong: Song? = null

    // 歌词
    private val lyricParser = LyricParser()
    private var lyricLines: List<LyricParser.LyricLine> = emptyList()
    private val lyricHandler = Handler(Looper.getMainLooper())
    private var lyricsVisible = false
    // v1.0.9：记录上次高亮的歌词行，只在变化时才更新UI和滚动，避免抢占用户手动滑动
    private var lastLyricIdx = -1
    // v1.0.9：用户手动滑动歌词时暂停自动滚动
    private var userScrollingLyrics = false
    private var userScrollResumeTime = 0L

    // v1.0.6：Service 持有 MediaPlayer，Activity 通过 Binder 控制（酷狗/网易云方案）
    private var musicService: MusicService? = null
    private var serviceBound = false

    private var pendingPlayPath: String? = null
    private val REQUEST_LOGIN = 8001

    // v1.0.4：当前播放的在线 URL（用于投屏）
    private var currentPlayingUrl: String? = null
    // v1.0.4：当前选中的投屏设备
    private var currentCastDevice: DlnaHelper.DlnaDevice? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val s = (service as? MusicService.MusicBinder)?.getService() ?: return
            musicService = s
            s.callback = object : MusicService.MusicCallback {
                override fun onPrepared(title: String, artist: String) {
                    runOnUiThread {
                        currentPlayingSong?.let {
                            updateBottomBar(it)
                            adapter.setPlaying(it.path)
                        }
                    }
                }
                override fun onCompletion() {
                    runOnUiThread { onSongComplete() }
                }
                override fun onError(what: Int, extra: Int, url: String?) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity,
                            "播放错误: what=$what extra=$extra\nURL: $url",
                            Toast.LENGTH_LONG).show()
                        isPlaying = false
                        binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    }
                }
                override fun onPlayStateChanged(playing: Boolean) {
                    runOnUiThread {
                        isPlaying = playing
                        binding.btnPlayPause.setImageResource(
                            if (isPlaying) android.R.drawable.ic_media_pause
                            else android.R.drawable.ic_media_play
                        )
                        if (playing) {
                            lyricHandler.post(progressRunnable)
                        } else {
                            lyricHandler.removeCallbacks(progressRunnable)
                        }
                    }
                }
                override fun onStop() {
                    runOnUiThread {
                        isPlaying = false
                        currentPlayingUrl = null
                        currentPlayingSong = null
                        binding.bottomBar.visibility = View.GONE
                        binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                        lyricHandler.removeCallbacks(progressRunnable)
                        // v1.0.7：停止时清除封面和歌词
                        binding.imgAlbumArt.setImageResource(android.R.drawable.ic_media_play)
                        binding.tvLyrics.text = ""
                        lyricLines = emptyList()
                        // v1.2.8：清空桌面歌词
                        DesktopLyricService.lyricLines = emptyList()
                        DesktopLyricService.songTitle = ""
                    }
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            musicService?.callback = null
            musicService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (hasPermission()) doScan() }

    // 歌词滚动 Runnable
    // v1.2.3：刷新间隔 300ms→80ms，解决歌词滞后（音乐已到下一句歌词还停上一句）
    private val lyricScrollRunnable = object : Runnable {
        override fun run() {
            updateLyricHighlight()
            lyricHandler.postDelayed(this, 80)
        }
    }

    // 进度条更新 Runnable
    private var userSeeking = false
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            lyricHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v1.2.2：签名校验，非原版签名直接弹窗退出
        if (!SignatureVerifier.isOriginalSignature(this)) {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
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

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // v1.2.7：启动公告弹窗（免责声明），勾选「永久不再接收」后不再弹出
        showAnnouncementIfNeeded()

        // 初始化 API（读取已保存的 Cookie）
        MusicApi.init(this)
        Favorites.init(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setTitle(R.string.local_music)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // v1.2.6：状态栏沉浸 + 封面圆角
        window.statusBarColor = android.graphics.Color.parseColor("#080808")
        binding.imgAlbumArt.clipToOutline = true
        binding.imgAlbumArt.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 12f)
            }
        }

        adapter = SongAdapter(
            onClick = { song, pos -> playSong(filteredSongs, pos) },
            onFavoriteClick = { song, _ -> toggleFavorite(song) },
            isFavorite = { id -> Favorites.contains(id) }
        )
        binding.recyclerSongs.layoutManager = LinearLayoutManager(this)
        binding.recyclerSongs.adapter = adapter

        // 搜索
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                if (isOnlineMode) {
                    // 在线模式：输入后不自动搜，按搜索键触发
                    return
                }
                if (isFavoritesMode) {
                    // 收藏模式：实时过滤收藏列表
                    val allFav = Favorites.getAll()
                    filteredSongs = if (q.isEmpty()) allFav
                    else allFav.filter {
                        it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
                    }
                    adapter.submit(filteredSongs)
                    playlist = filteredSongs
                    binding.tvEmpty.visibility = if (filteredSongs.isEmpty()) View.VISIBLE else View.GONE
                    if (filteredSongs.isEmpty()) binding.tvEmpty.text = getString(R.string.no_favorites)
                    return
                }
                filteredSongs = if (q.isEmpty()) allSongs
                else allSongs.filter {
                    it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
                }
                applyQualityFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 在线搜索：按回车触发
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH && isOnlineMode) {
                val keyword = binding.etSearch.text.toString().trim()
                if (keyword.isNotEmpty()) searchOnline(keyword)
                true
            } else false
        }

        // 音质切换
        binding.chipGroupQuality.setOnCheckedStateChangeListener { group, checkedIds ->
            currentQuality = when {
                R.id.chipLossless in checkedIds -> Quality.LOSSLESS
                R.id.chipHigh in checkedIds -> Quality.HIGH
                else -> Quality.STANDARD
            }
            if (!isOnlineMode) applyQualityFilter()
        }

        // Tab 切换
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                when (tab.position) {
                    0 -> { // 在线音乐
                        isOnlineMode = true
                        isFavoritesMode = false
                        binding.etSearch.hint = "输入歌曲名搜索在线音乐"
                        binding.qualityBar.visibility = View.GONE
                        if (onlineSongs.isEmpty()) {
                            binding.tvEmpty.text = "输入关键词搜索在线音乐"
                            binding.tvEmpty.visibility = View.VISIBLE
                            adapter.submit(emptyList())
                        }
                    }
                    1 -> { // 本地音乐
                        isOnlineMode = false
                        isFavoritesMode = false
                        binding.etSearch.hint = getString(R.string.search_hint)
                        binding.qualityBar.visibility = View.VISIBLE
                        // v1.1.5：不自动扫描，先询问用户是否扫描
                        if (allSongs.isEmpty()) {
                            promptScanLocal()
                        } else {
                            applyQualityFilter()
                        }
                    }
                    2 -> { // 收藏
                        isOnlineMode = false
                        isFavoritesMode = true
                        binding.etSearch.hint = "搜索收藏"
                        binding.qualityBar.visibility = View.GONE
                        showFavorites()
                    }
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })

        // 底部控制
        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnNext.setOnClickListener { playNext() }
        binding.btnPrev.setOnClickListener { playPrev() }
        binding.btnMode.setOnClickListener { cyclePlayMode() }
        binding.btnLyrics.setOnClickListener { toggleLyrics() }
        // v1.2.3：恢复 MV/PV 按钮入口
        binding.btnMv.setOnClickListener { playMv() }
        binding.btnCast.setOnClickListener { showCastDialog() }
        binding.btnFavorite.setOnClickListener { toggleCurrentFavorite() }

        // 进度条拖动
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
                val s = musicService
                if (s != null) {
                    val duration = s.getDuration()
                    if (duration > 0) {
                        val pos = (seekBar?.progress ?: 0) * duration / 100
                        s.seekTo(pos)
                    }
                }
            }
        })

        // 点击底部标题也切换歌词
        binding.tvCurrentTitle.setOnClickListener { toggleLyrics() }

        binding.tvLyrics.movementMethod = ScrollingMovementMethod()

        // v1.1.5：点击空提示可触发扫描本地音乐
        binding.tvEmpty.setOnClickListener {
            if (!isOnlineMode && !isFavoritesMode && allSongs.isEmpty()) {
                hasDeclinedScan = false
                promptScanLocal()
            }
        }

        // v1.2.3：双击返回键退出，防止误触（第一次提示，2秒内再按才退出）
        var backPressedTime = 0L
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (System.currentTimeMillis() - backPressedTime < 2000) {
                    finish()
                } else {
                    backPressedTime = System.currentTimeMillis()
                    Toast.makeText(this@MainActivity, "再按一次退出", Toast.LENGTH_SHORT).show()
                }
            }
        })

        // 请求通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }

        // 启动音乐服务
        val serviceIntent = Intent(this, MusicService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        serviceBound = true

        // 默认打开在线音乐 Tab（现在是第 0 栏，无需额外 select）
        isOnlineMode = true
        binding.etSearch.hint = "输入歌曲名搜索在线音乐"
        binding.qualityBar.visibility = View.GONE
        binding.tvEmpty.text = "输入关键词搜索在线音乐"
        binding.tvEmpty.visibility = View.VISIBLE

        intent?.getStringExtra("file_path")?.let { playPath ->
            pendingPlayPath = playPath
            // 外部打开时切到本地并扫描
            binding.tabLayout.getTabAt(1)?.select()
            if (hasPermission()) doScan() else requestPermission()
        }

        // v1.1.8：从听歌识曲跳转过来，自动搜索并播放匹配的歌曲
        handleRecognizeIntent(intent)
    }

    // v1.1.8：singleTop 模式下，识曲跳转会触发 onNewIntent 而非 onCreate
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRecognizeIntent(intent)
    }

    /**
     * v1.1.8：处理识曲播放的公共逻辑（onCreate / onNewIntent 共用）
     */
    private fun handleRecognizeIntent(intent: Intent?) {
        intent?.getLongExtra("recognize_song_id", -1L)?.takeIf { it > 0 }?.let { songId ->
            val name = intent.getStringExtra("recognize_song_name") ?: ""
            // 确保在线模式
            if (!isOnlineMode) {
                isOnlineMode = true
                binding.tabLayout.getTabAt(0)?.select()
            }
            binding.etSearch.setText(name)
            searchOnlineWithCallback(name) { list ->
                val match = list.firstOrNull { it.id == songId } ?: list.firstOrNull()
                match?.let { s ->
                    val song = Song(
                        id = s.id, title = s.name, artist = s.artist, album = s.album,
                        path = "online:${s.id}", duration = s.duration, size = 0, bitrate = 0
                    )
                    playOnlineSong(song)
                }
            }
        }
    }

    /**
     * v1.1.7：搜索在线音乐（带回调，供识曲跳转使用）
     */
    private fun searchOnlineWithCallback(keyword: String, onResult: (List<MusicApi.OnlineSong>) -> Unit) {
        collapseLyricsIfVisible()
        binding.tvEmpty.text = "搜索中..."
        binding.tvEmpty.visibility = View.VISIBLE
        adapter.submit(emptyList())

        MusicApi.search(keyword) { songs, err ->
            runOnUiThread {
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                    onResult(emptyList())
                    return@runOnUiThread
                }
                onlineSongs = songs ?: emptyList()
                if (songs != null && songs.isNotEmpty()) {
                    val displayList = songs.map { s ->
                        Song(
                            id = s.id, title = s.name, artist = s.artist, album = s.album,
                            path = "online:${s.id}", duration = s.duration, size = 0, bitrate = 0
                        )
                    }
                    adapter.submit(displayList)
                    filteredSongs = displayList
                    playlist = displayList
                    binding.tvEmpty.visibility = View.GONE
                } else {
                    binding.tvEmpty.text = "未找到结果"
                    binding.tvEmpty.visibility = View.VISIBLE
                }
                onResult(onlineSongs)
            }
        }
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * v1.1.5：进入本地音乐 Tab 时询问是否扫描
     * 不再自动扫描，先弹窗征求用户同意
     * 同意 → 检查权限并扫描；拒绝 → 显示空列表 + 提示"点击扫描"
     */
    private fun promptScanLocal() {
        if (hasDeclinedScan) {
            binding.tvEmpty.text = "点击此处扫描本地音乐"
            binding.tvEmpty.visibility = View.VISIBLE
            adapter.submit(emptyList())
            return
        }
        AlertDialog.Builder(this)
            .setTitle("扫描本地音乐")
            .setMessage("是否扫描设备上的本地音乐文件？")
            .setPositiveButton("扫描") { _, _ ->
                hasDeclinedScan = false
                if (hasPermission()) doScan()
                else requestPermission()
            }
            .setNegativeButton("暂不") { _, _ ->
                hasDeclinedScan = true
                binding.tvEmpty.text = "点击此处扫描本地音乐"
                binding.tvEmpty.visibility = View.VISIBLE
                adapter.submit(emptyList())
            }
            .setCancelable(false)
            .show()
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle(R.string.permission_required)
                .setMessage(getString(R.string.permission_required))
                .setPositiveButton(R.string.grant) { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                        permissionLauncher.launch(intent)
                    } catch (e: Exception) {
                        permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                if (it) doScan()
            }.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun doScan() {
        binding.tvEmpty.visibility = View.GONE
        binding.tvEmpty.text = getString(R.string.scanning)
        binding.tvEmpty.visibility = View.VISIBLE

        Thread {
            val songs = MusicScanner.scanAll(this)
            runOnUiThread {
                allSongs = songs
                filteredSongs = songs
                applyQualityFilter()
                if (songs.isEmpty()) {
                    binding.tvEmpty.text = getString(R.string.no_music)
                    binding.tvEmpty.visibility = View.VISIBLE
                } else {
                    binding.tvEmpty.visibility = View.GONE
                    Toast.makeText(this, getString(R.string.scan_complete, songs.size), Toast.LENGTH_SHORT).show()
                }
                pendingPlayPath?.let { path ->
                    pendingPlayPath = null
                    playFromExternalPath(path)
                }
            }
        }.start()
    }

    private fun playFromExternalPath(path: String) {
        val canonicalPath = try { java.io.File(path).canonicalPath } catch (e: Exception) { path }
        val song = allSongs.find { s ->
            try { java.io.File(s.path).canonicalPath == canonicalPath }
            catch (e: Exception) { s.path == path }
        }
        if (song != null) {
            val idx = filteredSongs.indexOf(song)
            if (idx >= 0) playSong(filteredSongs, idx)
            else playSong(listOf(song), 0)
        } else {
            val file = java.io.File(path)
            playSong(listOf(Song(
                id = path.hashCode().toLong(),
                title = file.nameWithoutExtension,
                artist = "<未知艺术家>",
                album = "<未知专辑>",
                path = path,
                duration = 0,
                size = if (file.exists()) file.length() else 0,
                bitrate = 0
            )), 0)
        }
    }

    private fun applyQualityFilter() {
        val q = currentQuality
        val list = if (q == Quality.STANDARD) filteredSongs
        else filteredSongs.filter {
            it.bitrate >= q.minBitrate || it.size >= q.minSizeMB * 1024 * 1024
        }
        adapter.submit(list)
        playlist = list
        binding.tvEmpty.visibility = if (list.isEmpty() && !isOnlineMode) View.VISIBLE else View.GONE
        if (list.isEmpty() && !isOnlineMode) binding.tvEmpty.text = getString(R.string.no_music)
    }

    /**
     * 在线搜索
     */
    private fun searchOnline(keyword: String) {
        collapseLyricsIfVisible()
        binding.tvEmpty.text = "搜索中..."
        binding.tvEmpty.visibility = View.VISIBLE
        adapter.submit(emptyList())

        MusicApi.search(keyword) { songs, error ->
            runOnUiThread {
                if (songs != null && songs.isNotEmpty()) {
                    onlineSongs = songs
                    // 将在线歌曲转换为本地 Song 格式显示
                    val displayList = songs.mapIndexed { idx, s ->
                        Song(
                            id = s.id,
                            title = s.name,
                            artist = s.artist,
                            album = s.album,
                            path = "online:${s.id}",
                            duration = s.duration,
                            size = 0,
                            bitrate = 0
                        )
                    }
                    adapter.submit(displayList)
                    filteredSongs = displayList
                    playlist = displayList
                    binding.tvEmpty.visibility = View.GONE
                } else {
                    binding.tvEmpty.text = error ?: "未找到结果"
                    binding.tvEmpty.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * 播放歌曲（本地或在线）
     */
    private fun playSong(list: List<Song>, index: Int) {
        if (index !in list.indices) return
        playlist = list
        currentIndex = index
        val song = list[index]
        currentPlayingSong = song

        if (song.path.startsWith("online:")) {
            // 在线播放
            playOnlineSong(song)
        } else {
            // 本地播放
            playLocalSong(song)
        }
    }

    /**
     * 显示收藏列表
     */
    private fun showFavorites() {
        val favs = Favorites.getAll()
        filteredSongs = favs
        playlist = favs
        adapter.submit(favs)
        if (favs.isEmpty()) {
            binding.tvEmpty.text = getString(R.string.no_favorites)
            binding.tvEmpty.visibility = View.VISIBLE
        } else {
            binding.tvEmpty.visibility = View.GONE
        }
    }

    /**
     * 切换歌曲收藏状态（来自列表项的爱心按钮）
     */
    private fun toggleFavorite(song: Song) {
        val added = Favorites.toggle(song, this)
        // 重新提交当前列表以更新爱心图标
        refreshCurrentList()
        if (currentPlayingSong?.id == song.id) updateFavoriteButton()
        Toast.makeText(this,
            if (added) "已收藏" else "已取消收藏",
            Toast.LENGTH_SHORT).show()
    }

    /**
     * 切换当前播放歌曲的收藏状态（来自底部栏爱心按钮）
     */
    private fun toggleCurrentFavorite() {
        val song = currentPlayingSong ?: run {
            Toast.makeText(this, "暂无播放歌曲", Toast.LENGTH_SHORT).show()
            return
        }
        val added = Favorites.toggle(song, this)
        refreshCurrentList()
        updateFavoriteButton()
        Toast.makeText(this,
            if (added) "已收藏" else "已取消收藏",
            Toast.LENGTH_SHORT).show()
    }

    /**
     * 刷新当前列表（保持爱心图标状态同步）
     */
    private fun refreshCurrentList() {
        val current = filteredSongs.toList()
        adapter.submit(current)
    }

    /**
     * 更新底部栏收藏按钮图标
     */
    private fun updateFavoriteButton() {
        val song = currentPlayingSong
        val fav = song != null && Favorites.contains(song.id)
        binding.btnFavorite.setImageResource(
            if (fav) R.drawable.ic_star_filled
            else R.drawable.ic_star_outline
        )
    }

    /**
     * 投屏对话框（v1.0.4：使用 DLNA，支持云视听小电视）
     */
    private fun showCastDialog() {
        // 检查是否在播放
        val url = currentPlayingUrl
        if (url == null) {
            AlertDialog.Builder(this)
                .setTitle("投屏")
                .setMessage("当前没有可投屏的歌曲\n\n请先播放在线歌曲\n（注：本地歌曲暂不支持投屏）")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        // 已有投屏设备：可断开
        currentCastDevice?.let { dev ->
            AlertDialog.Builder(this)
                .setTitle("投屏中")
                .setMessage("正在投屏到：${dev.name}\n是否停止投屏？")
                .setPositiveButton("停止投屏") { _, _ ->
                    DlnaHelper.stop(dev) { ok ->
                        runOnUiThread {
                            if (ok) {
                                Toast.makeText(this, "已停止投屏", Toast.LENGTH_SHORT).show()
                                currentCastDevice = null
                            } else {
                                Toast.makeText(this, "停止失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNeutralButton("切换设备") { _, _ ->
                    currentCastDevice = null
                    discoverAndCast(url)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        // 搜索 DLNA 设备
        discoverAndCast(url)
    }

    /**
     * 搜索 DLNA 设备并弹出选择对话框
     */
    private fun discoverAndCast(url: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("搜索投屏设备中...")
            .setMessage("正在搜索同一 Wi-Fi 下的 DLNA 设备\n（云视听小电视、小米电视、海信电视等）\n\n请确保：\n• 电视已开启\n• 与手机在同一 Wi-Fi")
            .setCancelable(false)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()

        DlnaHelper.discover(this) { devices ->
            runOnUiThread {
                dialog.dismiss()
                if (devices.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("未发现设备")
                        .setMessage("未搜索到 DLNA 设备\n\n请检查：\n• 电视已开启并连接同一 Wi-Fi\n• 电视的 DLNA 功能已开启\n• 路由器已开启多播")
                        .setPositiveButton("打开蓝牙设置") { _, _ ->
                            try {
                                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            } catch (e: Exception) {
                                Toast.makeText(this, "无法打开设置", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    return@runOnUiThread
                }
                val names = devices.map { it.name }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("选择投屏设备")
                    .setItems(names) { _, which ->
                        val dev = devices[which]
                        val title = currentPlayingSong?.let { "${it.title} - ${it.artist}" } ?: "MUSIC plan"
                        Toast.makeText(this, "正在投屏到 ${dev.name}...", Toast.LENGTH_SHORT).show()
                        DlnaHelper.cast(dev, url, title) { ok, msg ->
                            runOnUiThread {
                                if (ok) {
                                    currentCastDevice = dev
                                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this, "投屏失败: $msg", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    /**
     * v1.0.6：通知栏「关闭」完全由 Service 的 ACTION_STOP 处理，
     * Service.stop() 会触发 onStop 回调，Activity 在回调里更新 UI。
     */

    private fun playLocalSong(song: Song) {
        try {
            updateBottomBar(song)
            // v1.0.6：通过 Service 播放，Service 持有 MediaPlayer
            musicService?.play(song.path, song.title, song.artist, isOnline = false)
            // 尝试获取歌词（本地歌曲用文件名搜索在线歌词）
            fetchLyricsByTitle(song.title, song.artist)
        } catch (e: Exception) {
            Toast.makeText(this, "播放失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun playOnlineSong(song: Song) {
        val onlineId = song.id
        currentOnlineSong = onlineSongs.find { it.id == onlineId }

        // 显示加载提示
        Toast.makeText(this, "获取播放链接中...", Toast.LENGTH_SHORT).show()
        binding.bottomBar.visibility = View.VISIBLE
        binding.tvCurrentTitle.text = "${song.title} - ${song.artist} (加载中...)"
        binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)

        // 超时保护：15秒后如果没回调，提示错误
        val timeoutHandler = Handler(Looper.getMainLooper())
        var callbackCalled = false
        val timeoutRunnable = Runnable {
            if (!callbackCalled) {
                callbackCalled = true
                Toast.makeText(this, "请求超时，请检查网络后重试", Toast.LENGTH_LONG).show()
                binding.tvCurrentTitle.text = "${song.title} - ${song.artist} (超时)"
            }
        }
        timeoutHandler.postDelayed(timeoutRunnable, 15000)

        MusicApi.getSongUrlBySong(currentOnlineSong ?: MusicApi.OnlineSong(
            id = onlineId, name = song.title, artist = song.artist, album = "",
            duration = 0, songUrl = null, mvId = null, picUrl = null
        )) { url ->
            runOnUiThread {
                if (callbackCalled) return@runOnUiThread
                callbackCalled = true
                timeoutHandler.removeCallbacks(timeoutRunnable)

                if (url != null) {
                    // v1.0.4：记录 URL 用于投屏
                    currentPlayingUrl = url
                    // v1.0.7：通过 Service 播放（在线用 prepareAsync），传 picUrl 用于通知栏封面
                    val neteasePicUrl = currentOnlineSong?.picUrl
                    val songName = song.title
                    val songArtist = song.artist
                    updateBottomBar(song)
                    musicService?.play(url, song.title, song.artist, isOnline = true, picUrl = neteasePicUrl)
                    // v1.0.7：先显示网易云 picUrl（若有）
                    loadAlbumArt(neteasePicUrl)
                    // v1.1.1：按当前音乐源获取封面，对得上才展示
                    // 当前源取不到自动兜底（酷狗→QQ→网易云），歌名校验避免错误封面
                    MusicApi.getCover(songName, songArtist, neteasePicUrl) { coverUrl ->
                        runOnUiThread {
                            if (!coverUrl.isNullOrEmpty() && coverUrl != neteasePicUrl) {
                                musicService?.updateArtwork(coverUrl)
                                loadAlbumArt(coverUrl)
                            }
                        }
                    }
                    // v1.0.8：播放时自动展开歌词面板
                    if (!lyricsVisible) toggleLyrics()
                    // v1.1.2：歌词按源获取
                    // 网易云源用 onlineId，酷狗/QQ 源用标题搜网易云歌词
                    val os = currentOnlineSong
                    if (os != null && os.source == MusicApi.MusicSource.NETEASE) {
                        MusicApi.getLyrics(onlineId) { lrc, tlrc ->
                            runOnUiThread { displayLyrics(lrc, tlrc) }
                        }
                    } else {
                        // v1.1.2/v1.1.3：酷狗/QQ/酷我源用标题搜网易云歌词
                        fetchLyricsByTitle(song.title, song.artist)
                    }
                } else {
                    // 获取失败：可能是 VIP 歌曲需要登录
                    // v1.2.3：如果 Service 仍在播放上一首（切歌失败），不弹失败提示避免困惑
                    if (musicService?.isPlaying == true) {
                        Toast.makeText(this, "切歌失败，继续播放当前歌曲", Toast.LENGTH_SHORT).show()
                    } else if (!MusicApi.hasCookie()) {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("此歌曲需要登录")
                            .setMessage("这首是 VIP 歌曲，需要登录网易云账号才能播放\n\n免费歌曲可以直接播放，无需登录\n\n是否现在登录？")
                            .setPositiveButton("去登录") { _, _ -> showCookieDialog() }
                            .setNegativeButton("取消", null)
                            .show()
                    } else {
                        Toast.makeText(this, "无法获取播放链接（可能需要VIP或Cookie已过期）", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /**
     * 通过歌名搜索歌词（本地歌曲）
     */
    private fun fetchLyricsByTitle(title: String, artist: String) {
        lyricLines = emptyList()
        binding.tvLyrics.text = "搜索歌词中..."
        MusicApi.getLyricsByTitle(title, artist) { lrc, tlrc ->
            runOnUiThread { displayLyrics(lrc, tlrc) }
        }
    }

    /**
     * 显示歌词
     */
    private fun displayLyrics(lrc: String?, translation: String?) {
        lyricLines = lyricParser.parse(lrc, translation)
        // v1.0.9：新歌词载入时重置高亮索引，强制下次刷新
        lastLyricIdx = -1
        // v1.2.8：同步歌词到桌面歌词服务
        DesktopLyricService.lyricLines = lyricLines
        DesktopLyricService.songTitle = currentPlayingSong?.title ?: ""
        if (lyricLines.isEmpty()) {
            binding.tvLyrics.text = "暂无歌词"
            return
        }
        val sb = StringBuilder()
        for (line in lyricLines) {
            sb.append(line.text).append("\n")
            if (!line.translation.isNullOrEmpty()) {
                sb.append(line.translation).append("\n")
            }
        }
        binding.tvLyrics.text = sb.toString()
    }

    /**
     * 更新歌词高亮（当前行）
     * v1.0.10 修复：
     * - 用户手动滑动后，仍在 idx 变化时更新高亮（之前直接 return 导致"歌词不动了"）
     * - idx 变化（进入下一句）时恢复自动滚动到新位置（网易云行为）
     * - 滚动用 scrollTo 瞬间定位，替代 smoothScrollTo，避免滚动过头超过歌词位置
     */
    private fun updateLyricHighlight() {
        if (lyricLines.isEmpty() || !lyricsVisible) return
        val pos = musicService?.getCurrentPosition()?.toLong() ?: return
        val idx = lyricParser.getIndexAtTime(pos, lyricLines)
        if (idx < 0) return

        // 只在 idx 变化时才更新 UI，避免频繁 setText 重置布局
        if (idx == lastLyricIdx) return
        // idx 变化 = 进入下一句，恢复自动滚动（用户手动滑动状态清除）
        userScrollingLyrics = false
        lastLyricIdx = idx

        // 构建全部歌词文本，记录每行的起始偏移
        val sb = StringBuilder()
        val lineOffsets = mutableListOf<Int>()
        for (i in lyricLines.indices) {
            val line = lyricLines[i]
            lineOffsets.add(sb.length)
            sb.append(line.text).append("\n")
            if (!line.translation.isNullOrEmpty()) {
                sb.append(line.translation).append("\n")
            }
        }
        val fullText = sb.toString()

        // 用 Spannable 高亮：当前行白色加粗，其他灰色
        val span = android.text.SpannableStringBuilder(fullText)
        span.setSpan(
            android.text.style.ForegroundColorSpan(0xFF888888.toInt()),
            0, fullText.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        val curStart = lineOffsets.getOrElse(idx) { 0 }
        val curEnd = if (idx + 1 < lineOffsets.size) lineOffsets[idx + 1] else fullText.length
        span.setSpan(
            android.text.style.ForegroundColorSpan(0xFFFFFFFF.toInt()),
            curStart, curEnd,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        span.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            curStart, curEnd,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        span.setSpan(
            android.text.style.RelativeSizeSpan(1.1f),
            curStart, curEnd,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.tvLyrics.text = span

        // 自动滚动到当前行（居中）—— 用 scrollTo 瞬间精准定位，不过头
        // v1.2.7：lyricPanel 改为 FrameLayout，实际滚动的是内部的 lyricScroll
        binding.tvLyrics.post {
            val layout = binding.tvLyrics.layout ?: return@post
            val lineNum = layout.getLineForOffset(curStart)
            val lineTop = layout.getLineTop(lineNum)
            val lineHeight = layout.getLineBottom(lineNum) - lineTop
            // 让当前行垂直居中：滚动量 = 当前行顶部 - (面板高度/2) + (行高/2)
            val target = (lineTop - binding.lyricScroll.height / 2 + lineHeight / 2).coerceAtLeast(0)
            binding.lyricScroll.scrollTo(0, target)
        }
    }

    /**
     * 搜索时自动收回歌词面板
     */
    private fun collapseLyricsIfVisible() {
        if (lyricsVisible) {
            lyricsVisible = false
            binding.lyricPanel.visibility = View.GONE
            binding.recyclerSongs.visibility = View.VISIBLE
            lyricHandler.removeCallbacks(lyricScrollRunnable)
        }
    }

    /**
     * 更新进度条和时间显示
     */
    private fun updateProgress() {
        val s = musicService ?: return
        if (userSeeking) return
        val pos = s.getCurrentPosition()
        val dur = s.getDuration()
        if (dur > 0) {
            binding.seekBar.progress = (pos * 100 / dur).coerceIn(0, 100)
        }
        binding.tvCurrentTime.text = formatTime(pos)
        binding.tvTotalTime.text = formatTime(dur)
    }

    private fun formatTime(ms: Int): String {
        if (ms <= 0) return "00:00"
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }

    /**
     * 切换歌词面板显示
     * v1.0.9：给 ScrollView 加触摸监听，用户手动滑动时暂停自动滚动 5 秒
     */
    private fun toggleLyrics() {
        lyricsVisible = !lyricsVisible
        binding.lyricPanel.visibility = if (lyricsVisible) View.VISIBLE else View.GONE
        if (lyricsVisible) {
            binding.recyclerSongs.visibility = View.GONE
            // v1.0.9：用户触摸歌词时暂停自动滚动
            // v1.2.7：触摸监听挂在内部 ScrollView 上
            binding.lyricScroll.setOnTouchListener { _, _ ->
                userScrollingLyrics = true
                userScrollResumeTime = System.currentTimeMillis()
                false
            }
            lyricHandler.post(lyricScrollRunnable)
        } else {
            binding.recyclerSongs.visibility = View.VISIBLE
            lyricHandler.removeCallbacks(lyricScrollRunnable)
        }
    }

    /**
     * 播放 PV/MV
     * v1.2.4：统一跳转应用内 PV 播放器（PvSearchActivity），不再调系统播放器
     * 用当前歌曲名/歌手作为关键词，自动搜索并播放第一个结果
     */
    private fun playMv() {
        val keyword = when {
            currentOnlineSong != null -> "${currentOnlineSong!!.name} ${currentOnlineSong!!.artist}".trim()
            currentIndex >= 0 && currentIndex < playlist.size -> {
                val song = playlist[currentIndex]
                "${song.title} ${song.artist}".trim()
            }
            else -> {
                Toast.makeText(this, "请先选择一首歌曲", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val intent = Intent(this, PvSearchActivity::class.java).apply {
            putExtra("auto_play", true)
            putExtra("auto_play_keyword", keyword)
        }
        startActivity(intent)
    }

    private fun onSongComplete() {
        when (playMode) {
            PlayMode.SINGLE -> {
                currentIndex.let { if (it in playlist.indices) playSong(playlist, it) }
            }
            PlayMode.SHUFFLE -> {
                if (playlist.isNotEmpty()) {
                    playSong(playlist, (0 until playlist.size).random())
                }
            }
            PlayMode.LOOP -> playNext()
        }
    }

    private fun togglePlayPause() {
        // v1.0.6：播放状态由 Service 管理，按钮图标由 callback 更新
        val s = musicService ?: return
        if (s.isPlaying) s.pause() else s.resume()
    }

    private fun playNext() {
        if (playlist.isEmpty() || currentIndex < 0) return
        val next = when (playMode) {
            PlayMode.SHUFFLE -> (0 until playlist.size).random()
            else -> (currentIndex + 1) % playlist.size
        }
        playSong(playlist, next)
    }

    private fun playPrev() {
        if (playlist.isEmpty() || currentIndex < 0) return
        val prev = if (currentIndex == 0) playlist.size - 1 else currentIndex - 1
        playSong(playlist, prev)
    }

    private fun cyclePlayMode() {
        playMode = when (playMode) {
            PlayMode.LOOP -> PlayMode.SINGLE
            PlayMode.SINGLE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.LOOP
        }
        val msg = when (playMode) {
            PlayMode.LOOP -> R.string.mode_loop
            PlayMode.SINGLE -> R.string.mode_single
            PlayMode.SHUFFLE -> R.string.mode_shuffle
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun updateBottomBar(song: Song) {
        binding.bottomBar.visibility = View.VISIBLE
        binding.tvCurrentTitle.text = "${song.title} - ${song.artist}"
        binding.btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        updateFavoriteButton()
    }

    /**
     * v1.0.7：加载专辑封面到底部栏 ImageView
     * picUrl 为空时显示默认图标
     */
    private fun loadAlbumArt(picUrl: String?) {
        if (picUrl.isNullOrEmpty()) {
            binding.imgAlbumArt.setImageResource(android.R.drawable.ic_media_play)
            return
        }
        BitmapLoader.get(picUrl) { bmp ->
            runOnUiThread {
                if (bmp != null) binding.imgAlbumArt.setImageBitmap(bmp)
                else binding.imgAlbumArt.setImageResource(android.R.drawable.ic_media_play)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.scan_music).setIcon(android.R.drawable.ic_menu_search)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 0, "Cookie设置").setIcon(android.R.drawable.ic_menu_manage)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        // v1.0.4：设置改为齿轮图标
        menu.add(0, 3, 0, R.string.settings).setIcon(R.drawable.ic_settings)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                when {
                    isOnlineMode -> searchOnline(binding.etSearch.text.toString())
                    isFavoritesMode -> showFavorites()
                    else -> doScan()
                }
            }
            2 -> showCookieDialog()
            // v1.0.4：跳转到新的设置页面
            3 -> startActivity(Intent(this, SettingsActivity::class.java))
            android.R.id.home -> finish()
        }
        return true
    }

    /**
     * 网易云 Cookie 设置对话框
     * 提供「网页登录」和「手动输入」两种方式
     */
    private fun showCookieDialog() {
        val currentCookie = MusicApi.getCookie()
        val status = if (currentCookie.isNotEmpty()) 
            "已配置 (前10位: ${currentCookie.take(10)}...)" else "未配置"

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("网易云登录")
            .setMessage("当前状态：$status\n\n推荐使用「网页登录」，自动获取 Cookie\n\n手动输入需在浏览器开发者工具中复制 MUSIC_U 值")
            .setPositiveButton("网页登录") { _, _ ->
                // 打开内嵌 WebView 登录页
                val intent = Intent(this, LoginWebActivity::class.java)
                startActivityForResult(intent, REQUEST_LOGIN)
            }
            .setNegativeButton("手动输入") { _, _ ->
                showManualCookieInput(currentCookie)
            }
            .setNeutralButton("清除") { _, _ ->
                MusicApi.saveCookie(this, "")
                Toast.makeText(this, "已清除 Cookie", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showManualCookieInput(currentCookie: String) {
        val editText = android.widget.EditText(this).apply {
            hint = "粘贴 MUSIC_U Cookie 值"
            setText(currentCookie)
            setSingleLine(false)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            setPadding(32, 24, 32, 24)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("手动输入 MUSIC_U")
            .setMessage("在浏览器登录 music.163.com 后\nF12 → Application → Cookies → MUSIC_U\n复制 Value 值粘贴到下方")
            .setView(editText)
            .setPositiveButton("保存") { _, _ ->
                val cookie = editText.text.toString().trim()
                if (cookie.isNotEmpty()) {
                    MusicApi.saveCookie(this, cookie)
                    Toast.makeText(this, "Cookie 已保存", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_LOGIN && resultCode == RESULT_OK) {
            Toast.makeText(this, "登录成功！现在可以播放在线音乐了", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        lyricHandler.removeCallbacks(lyricScrollRunnable)
        // v1.0.6：MediaPlayer 由 Service 持有，Activity 销毁时只解绑，不停止播放（后台继续）
        musicService?.callback = null
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    /**
     * v1.2.7：启动公告弹窗
     * - 首次启动 / 用户没勾「永久不再接收」时弹出
     * - 内容：免责声明
     * - 用户必须点「确定」才能继续使用 App
     * - 点「退出」直接退出 App
     * - 勾选「永久不再接收」+ 点「确定」后 SharedPreferences 记录，下次不再弹
     */
    private fun showAnnouncementIfNeeded() {
        val sp = getSharedPreferences("announcement_prefs", MODE_PRIVATE)
        if (sp.getBoolean("never_show_again", false)) {
            return  // 用户已勾选永久不接收
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_announcement, null)
        val cbNeverShow = dialogView.findViewById<android.widget.CheckBox>(R.id.cbNeverShow)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<android.widget.Button>(R.id.btnAccept).setOnClickListener {
            // 用户同意，如果勾选了「永久不再接收」就记录
            if (cbNeverShow.isChecked) {
                sp.edit().putBoolean("never_show_again", true).apply()
            }
            dialog.dismiss()
        }

        dialogView.findViewById<android.widget.Button>(R.id.btnDecline).setOnClickListener {
            // 用户拒绝，退出 App
            dialog.dismiss()
            finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        dialog.show()
    }
}
