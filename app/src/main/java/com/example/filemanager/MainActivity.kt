package com.example.filemanager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.filemanager.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FileAdapter

    private var currentDir: File = FileUtils.getStorageRoot()
    private var showHidden = false
    private var sortMode = SortMode.NAME
    private val dirStack = ArrayDeque<File>()

    private var clipboard: List<FileItem> = emptyList()
    private var isCut = false

    private var gridMode = true

    private var zipBrowsing: File? = null
    private var zipEntries: List<ZipEntryInfo> = emptyList()

    // Root模式
    private var rootMode = false
    private var rootCurrentPath: String = ""

    // Shizuku 权限请求码
    private val SHIZUKU_REQUEST_CODE = 1001
    // 标记是否正在等待 Shizuku 授权结果（授权后用于决定是否继续访问目录）
    private var pendingPathAfterPermission: String? = null

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasAllFilesAccess()) {
            loadDirectory(currentDir)
        } else {
            loadDirectory(currentDir)
        }
    }

    // Shizuku 权限授权结果监听器
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            runOnUiThread {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, R.string.shizuku_granted, Toast.LENGTH_LONG).show()
                    // 授权成功，如果有等待的目录则继续访问
                    pendingPathAfterPermission?.let { path ->
                        pendingPathAfterPermission = null
                        browseSystemDir(path)
                    } ?: run {
                        // 默认进入 /data
                        tryAccessDataDir()
                    }
                } else {
                    Toast.makeText(this, R.string.shizuku_denied, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Shizuku 服务状态变化监听器（用户在 Shizuku 中启动/停止服务时回调）
    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            // 当 Shizuku 服务可用时，自动检查授权
            if (AdbUtils.isShizukuAuthorized()) {
                Toast.makeText(this, R.string.adb_available, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        // 服务断开时无需特别处理
    }

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("language", "zh") ?: "zh"
        val locale = if (lang == "en") Locale.ENGLISH else Locale.CHINESE
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        adapter = FileAdapter(
            onClick = { handleClick(it) },
            onLongClick = { handleSelectionChanged(); true },
            onMoreClick = { item -> showItemMenu(binding.root, item) }
        )
        setupLayoutManager()
        binding.recycler.adapter = adapter

        binding.emptyView.visibility = View.GONE

        // 返回上级按钮
        binding.btnGoUp.setOnClickListener { goUp() }
        // 主页按钮
        binding.btnHome.setOnClickListener { goHome() }

        // 检查Root设置
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        rootMode = prefs.getBoolean("root_enabled", false) && AdbUtils.isPrivilegedAccessAvailable()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.selectionMode) {
                    adapter.clearSelection()
                    updateToolbarTitle()
                    return
                }
                if (zipBrowsing != null) {
                    zipBrowsing = null
                    zipEntries = emptyList()
                    loadDirectory(currentDir)
                    return
                }
                goUp()
            }
        })

        if (!hasAllFilesAccess()) {
            requestAllFilesAccess()
        } else {
            loadDirectory(currentDir)
        }

        // 注册 Shizuku 监听器并检测，延迟执行避免启动时同步调用导致崩溃
        // ShizukuProvider 在 onCreate 阶段查询 ContentProvider 可能引发 ANR/崩溃
        binding.root.post {
            initShizukuSafely()
        }
    }

    /**
     * 安全地初始化 Shizuku：所有调用都包在 try-catch 中
     * 通过 post 延迟到视图渲染后执行，避免阻塞启动
     */
    private fun initShizukuSafely() {
        try {
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        } catch (e: Throwable) {
        }
        try {
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        } catch (e: Throwable) {
        }
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Throwable) {
        }
        // 自动检测ADB权限管理应用
        try {
            checkAdbOnStartup()
        } catch (e: Throwable) {
            // 即使检测失败也不影响应用正常使用
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        // 从 Shizuku 返回后，重新检测授权状态并更新 Root 模式
        try {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            rootMode = prefs.getBoolean("root_enabled", false) && AdbUtils.isPrivilegedAccessAvailable()
        } catch (e: Throwable) {
        }
    }

    /**
     * 启动时自动检测ADB权限管理应用
     * 区分三种状态：
     *   1. 未安装 Shizuku：引导用户安装
     *   2. 已安装但服务未启动：引导用户去 Shizuku 启动服务（无线调试或USB）
     *   3. 服务已运行但本应用未授权：直接调用 Shizuku.requestPermission 弹出授权对话框
     *   4. 已授权：直接使用
     */
    private fun checkAdbOnStartup() {
        val installedAdbApps = AdbUtils.detectInstalledAdbApps(this)

        // 状态4：已授权或已有Root，直接提示
        if (AdbUtils.isPrivilegedAccessAvailable()) {
            val mode = AdbUtils.getPrivilegeMode()
            Toast.makeText(this, getString(R.string.adb_available_mode, mode), Toast.LENGTH_SHORT).show()
            return
        }

        // 状态1：未安装任何ADB应用
        if (installedAdbApps.isEmpty()) {
            // 静默忽略，不主动打扰用户；可由用户从设置或菜单触发
            return
        }

        // 状态2 vs 状态3：已安装 Shizuku
        val shizukuRunning = AdbUtils.isShizukuRunning()

        if (!shizukuRunning) {
            // 状态2：服务未启动，引导用户去 Shizuku 启动服务
            showShizukuStartGuide(installedAdbApps)
        } else {
            // 状态3：服务运行但未授权，直接申请权限
            requestShizukuPermissionWithGuide()
        }
    }

    /**
     * 引导用户去 Shizuku 启动服务（服务未运行时）
     */
    private fun showShizukuStartGuide(installedAdbApps: List<AdbUtils.AdbApp>) {
        val shizukuApp = installedAdbApps.firstOrNull { it.packageName == "moe.shizuku.privileged.api" }
            ?: installedAdbApps.first()
        AlertDialog.Builder(this)
            .setTitle(R.string.shizuku_start_guide_title)
            .setMessage(getString(R.string.shizuku_start_guide_msg, shizukuApp.name))
            .setPositiveButton(R.string.shizuku_open_app) { _, _ ->
                AdbUtils.openAdbApp(this, shizukuApp.packageName)
            }
            .setNegativeButton(R.string.shizuku_open_wireless_debug) { _, _ ->
                AdbUtils.openWirelessDebuggingSettings(this)
            }
            .setNeutralButton(R.string.adb_skip, null)
            .setCancelable(false)
            .show()
    }

    /**
     * 调用 Shizuku.requestPermission 申请授权（服务已运行时）
     */
    private fun requestShizukuPermissionWithGuide() {
        val requested = AdbUtils.requestShizukuPermission(SHIZUKU_REQUEST_CODE)
        if (!requested) {
            // 用户曾拒绝过（shouldShowRequestPermissionRationale），需要去 Shizuku 中手动允许
            AlertDialog.Builder(this)
                .setTitle(R.string.shizuku_auth_title)
                .setMessage(getString(R.string.shizuku_auth_rationale))
                .setPositiveButton(R.string.shizuku_open_app) { _, _ ->
                    AdbUtils.openAdbApp(this, "moe.shizuku.privileged.api")
                }
                .setNegativeButton(R.string.adb_skip, null)
                .show()
        }
    }

    /**
     * 尝试访问 /data 目录
     * 如果尚未授权 Shizuku，先发起权限请求；授权成功后通过回调继续访问
     */
    private fun tryAccessDataDir() {
        if (AdbUtils.isRootAvailable()) {
            rootMode = true
            rootCurrentPath = "/data"
            loadRootDirectory(rootCurrentPath)
        } else if (AdbUtils.isShizukuAuthorized()) {
            // Shizuku 已授权，通过 Shizuku 执行
            rootMode = true
            rootCurrentPath = "/data"
            val rootFiles = AdbUtils.listFilesWithPrivilege("/data")
            if (rootFiles.isEmpty()) {
                Toast.makeText(this, R.string.adb_access_failed, Toast.LENGTH_LONG).show()
                return
            }
            val items = rootFiles.filter { showHidden || !it.name.startsWith(".") }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .map { rf -> FileItem(File(rf.path)) }
            adapter.clearSelection()
            adapter.submit(items)
            binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            updateToolbarTitle()
            binding.pathText.text = "[Shizuku] /data"
        } else if (AdbUtils.isShizukuRunning()) {
            // 服务运行但未授权，申请权限，授权后回调本方法
            pendingPathAfterPermission = "/data"
            requestShizukuPermissionWithGuide()
        } else {
            // 既无 Root，Shizuku 也没运行
            val installedAdbApps = AdbUtils.detectInstalledAdbApps(this)
            if (installedAdbApps.isNotEmpty()) {
                showShizukuStartGuide(installedAdbApps)
            } else {
                Toast.makeText(this, R.string.adb_not_available, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun goUp() {
        if (zipBrowsing != null) {
            zipBrowsing = null
            zipEntries = emptyList()
            loadDirectory(currentDir)
            return
        }

        if (rootMode) {
            // Root模式返回上级
            val parent = File(rootCurrentPath).parentFile
            if (parent != null && parent.absolutePath != rootCurrentPath) {
                rootCurrentPath = parent.absolutePath
                loadRootDirectory(rootCurrentPath)
            } else {
                // 退出root模式
                rootMode = false
                rootCurrentPath = ""
                loadDirectory(currentDir)
            }
            return
        }

        if (dirStack.isNotEmpty()) {
            currentDir = dirStack.removeLast()
            loadDirectory(currentDir)
        } else {
            val parent = currentDir.parentFile
            if (parent != null && parent.canRead()) {
                currentDir = parent
                loadDirectory(currentDir)
            } else {
                finish()
            }
        }
    }

    private fun goHome() {
        if (rootMode) {
            rootMode = false
            rootCurrentPath = ""
        }
        zipBrowsing = null
        dirStack.clear()
        currentDir = FileUtils.getStorageRoot()
        loadDirectory(currentDir)
    }

    private fun setupLayoutManager() {
        if (gridMode) {
            binding.recycler.layoutManager = GridLayoutManager(this, 2)
        } else {
            binding.recycler.layoutManager = LinearLayoutManager(this)
        }
    }

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle(R.string.root_access)
                .setMessage(getString(R.string.data_access_msg))
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        manageStorageLauncher.launch(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        manageStorageLauncher.launch(intent)
                    }
                }
                .setNegativeButton("Cancel") { _, _ -> loadDirectory(currentDir) }
                .show()
        }
    }

    private fun handleClick(item: FileItem) {
        if (adapter.selectionMode) {
            adapter.toggleSelection(item)
            handleSelectionChanged()
            return
        }

        if (zipBrowsing != null) return

        if (item.isDirectory) {
            // 检查是否可读，不可读则尝试root
            if (item.file.canRead()) {
                dirStack.addLast(currentDir)
                currentDir = item.file
                loadDirectory(currentDir)
            } else if (rootMode || AdbUtils.isPrivilegedAccessAvailable()) {
                // Root模式浏览
                rootMode = true
                rootCurrentPath = item.file.absolutePath
                loadRootDirectory(rootCurrentPath)
            } else {
                Toast.makeText(this, "无法访问此目录，请在设置中启用Root权限", Toast.LENGTH_SHORT).show()
            }
        } else {
            val ext = item.name.substringAfterLast('.', "").lowercase()
            when {
                FileUtils.isTextFile(item.file) -> openTextViewer(item.file)
                FileUtils.isZipFile(item.file) -> browseZip(item.file)
                FileUtils.isImageFile(item.file) -> openImageViewer(item.file)
                FileUtils.isAudioFile(item.file) -> openMusicPlayer(item.file)
                ext == "apk" -> openApkTools(item.file)
                // 特殊/未知扩展名一律以文本文档方式打开（v1.6.1）
                // 已知二进制类型（图片/音频/视频/apk/zip）已在上方处理，其余均用文本查看器
                else -> openTextViewer(item.file)
            }
        }
    }

    private fun handleSelectionChanged() {
        updateToolbarTitle()
    }

    private fun loadDirectory(dir: File) {
        currentDir = dir
        rootMode = false
        val items = FileUtils.sort(FileUtils.listFiles(dir, showHidden), sortMode)
        adapter.clearSelection()
        adapter.submit(items)
        binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        updateToolbarTitle()
        binding.pathText.text = dir.absolutePath
    }

    private fun loadRootDirectory(path: String) {
        rootCurrentPath = path
        rootMode = true
        val rootFiles = AdbUtils.listFilesWithPrivilege(path)
        val items = rootFiles.filter { showHidden || !it.name.startsWith(".") }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .map { rf ->
                FileItem(File(rf.path))
            }
        adapter.clearSelection()
        adapter.submit(items)
        binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        updateToolbarTitle()
        val prefix = when {
            AdbUtils.isRootAvailable() -> "[Root]"
            AdbUtils.isShizukuAuthorized() -> "[Shizuku]"
            else -> "[ADB]"
        }
        binding.pathText.text = "$prefix $path"
    }

    private fun browseZip(zipFile: File) {
        val entries = FileUtils.listZipEntries(zipFile)
        if (entries.isEmpty()) {
            Toast.makeText(this, "无法读取压缩包或压缩包为空", Toast.LENGTH_SHORT).show()
            return
        }
        zipBrowsing = zipFile
        zipEntries = entries
        val items = entries.map { ei ->
            FileItem(File(zipFile.parentFile, ei.name))
        }
        adapter.clearSelection()
        adapter.submit(items)
        binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.pathText.text = "${zipFile.name} (${entries.size})"
        binding.toolbar.title = getString(R.string.browse_archive)
    }

    private fun openTextViewer(file: File) {
        val intent = Intent(this, TextViewerActivity::class.java)
        intent.putExtra("file_path", file.absolutePath)
        startActivity(intent)
    }

    private fun openImageViewer(file: File) {
        val intent = Intent(this, ImageViewerActivity::class.java)
        intent.putExtra("file_path", file.absolutePath)
        startActivity(intent)
    }

    /**
     * 打开音乐播放器
     * 优先使用音乐插件（com.example.musicplugin），若已安装则跳转到插件并传递文件路径
     * 否则使用内置音乐播放器
     */
    private fun openMusicPlayer(file: File) {
        val pluginPkg = "com.example.musicplugin"
        val installed = try {
            packageManager.getPackageInfo(pluginPkg, 0) != null
        } catch (e: Exception) {
            false
        }
        if (installed) {
            // 跳转到音乐插件，传递文件路径让插件直接播放
            try {
                val intent = Intent("com.example.filemanager.OPEN_MUSIC_PLUGIN").apply {
                    setPackage(pluginPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("file_path", file.absolutePath)
                    putExtra("dir", file.parent)
                }
                startActivity(intent)
                return
            } catch (e: Exception) {
                // 插件跳转失败，回退到内置播放器
            }
        }
        // 使用内置音乐播放器
        val intent = Intent(this, MusicPlayerActivity::class.java)
        intent.putExtra("file_path", file.absolutePath)
        intent.putExtra("dir", file.parent)
        startActivity(intent)
    }

    private fun openApkTools(file: File) {
        val intent = Intent(this, ApkToolsActivity::class.java)
        intent.putExtra("apk_path", file.absolutePath)
        startActivity(intent)
    }

    private fun updateToolbarTitle() {
        if (zipBrowsing != null) {
            binding.toolbar.title = getString(R.string.browse_archive)
            return
        }
        if (adapter.selectionMode) {
            binding.toolbar.title = getString(R.string.selected_items, adapter.selectedCount())
        } else {
            binding.toolbar.title = getString(R.string.app_name)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_terminal -> {
                val intent = Intent(this, TerminalActivity::class.java)
                intent.putExtra("cwd", currentDir.absolutePath)
                startActivity(intent)
                true
            }
            R.id.action_sort -> { showSortMenu(); true }
            R.id.action_hidden -> {
                showHidden = !showHidden
                item.isChecked = showHidden
                if (rootMode) loadRootDirectory(rootCurrentPath) else loadDirectory(currentDir)
                true
            }
            R.id.action_new_folder -> { showCreateDialog(isFolder = true); true }
            R.id.action_new_file -> { showCreateDialog(isFolder = false); true }
            R.id.action_paste -> { doPaste(); true }
            R.id.action_select_all -> {
                val all = if (rootMode) {
                    AdbUtils.listFilesWithPrivilege(rootCurrentPath)
                        .filter { showHidden || !it.name.startsWith(".") }
                        .map { FileItem(File(it.path)) }
                } else {
                    FileUtils.sort(FileUtils.listFiles(currentDir, showHidden), sortMode)
                }
                adapter.selectAll(all)
                updateToolbarTitle()
                true
            }
            R.id.action_view_mode -> {
                gridMode = !gridMode
                setupLayoutManager()
                adapter.submit(adapter.getItems())
                Toast.makeText(this, if (gridMode) getString(R.string.grid_view) else getString(R.string.list_view), Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_compress -> { showCompressDialog(); true }
            R.id.action_extract -> { showExtractDialog(); true }
            R.id.action_adb -> { showAdbPermissionDialog(); true }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSortMenu() {
        val popup = PopupMenu(this, findViewById(R.id.action_sort) ?: binding.toolbar)
        val labels = arrayOf(getString(R.string.sort_by_name), getString(R.string.sort_by_size), getString(R.string.sort_by_date), getString(R.string.sort_by_type))
        labels.forEachIndexed { idx, label ->
            popup.menu.add(0, idx, idx, if (sortMode.ordinal == idx) "✓ $label" else label)
        }
        popup.setOnMenuItemClickListener { mi ->
            sortMode = SortMode.entries[mi.itemId]
            if (rootMode) loadRootDirectory(rootCurrentPath) else loadDirectory(currentDir)
            true
        }
        popup.show()
    }

    private fun showCreateDialog(isFolder: Boolean) {
        val input = android.widget.EditText(this).apply {
            hint = if (isFolder) getString(R.string.new_folder) else getString(R.string.new_file)
        }
        AlertDialog.Builder(this)
            .setTitle(if (isFolder) getString(R.string.new_folder) else getString(R.string.new_file))
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val ok = if (isFolder) FileUtils.createFolder(currentDir, name)
                else FileUtils.createFile(currentDir, name)
                if (ok) {
                    Toast.makeText(this, "OK", Toast.LENGTH_SHORT).show()
                    loadDirectory(currentDir)
                } else {
                    Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showItemMenu(anchor: View, item: FileItem) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.open))
        popup.menu.add(0, 2, 0, getString(R.string.copy))
        popup.menu.add(0, 3, 0, getString(R.string.cut))
        popup.menu.add(0, 4, 0, getString(R.string.rename))
        popup.menu.add(0, 5, 0, getString(R.string.delete))
        popup.menu.add(0, 6, 0, getString(R.string.properties))
        if (!item.isDirectory && FileUtils.isZipFile(item.file)) {
            popup.menu.add(0, 7, 0, getString(R.string.extract_here))
            popup.menu.add(0, 8, 0, getString(R.string.extract_to))
        }
        if (!item.isDirectory) {
            popup.menu.add(0, 9, 0, getString(R.string.open_as_text))
            popup.menu.add(0, 10, 0, getString(R.string.open_as_image))
            popup.menu.add(0, 11, 0, getString(R.string.open_as_audio))
            popup.menu.add(0, 12, 0, getString(R.string.open_as_video))
            popup.menu.add(0, 13, 0, getString(R.string.open_with_other))
        }
        val ext = item.name.substringAfterLast('.', "").lowercase()
        if (!item.isDirectory && ext == "apk") {
            popup.menu.add(0, 14, 0, getString(R.string.apk_tools))
        }
        popup.menu.add(0, 15, 0, getString(R.string.compress_to_zip))

        popup.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> {
                    if (item.isDirectory) {
                        dirStack.addLast(currentDir)
                        currentDir = item.file
                        loadDirectory(currentDir)
                    } else when {
                        FileUtils.isTextFile(item.file) -> openTextViewer(item.file)
                        FileUtils.isZipFile(item.file) -> browseZip(item.file)
                        FileUtils.isImageFile(item.file) -> openImageViewer(item.file)
                        FileUtils.isAudioFile(item.file) -> openMusicPlayer(item.file)
                        ext == "apk" -> openApkTools(item.file)
                        // 特殊/未知扩展名一律以文本文档方式打开（v1.6.1）
                        else -> openTextViewer(item.file)
                    }
                }
                2 -> { clipboard = listOf(item); isCut = false; Toast.makeText(this, getString(R.string.copy), Toast.LENGTH_SHORT).show() }
                3 -> { clipboard = listOf(item); isCut = true; Toast.makeText(this, getString(R.string.cut), Toast.LENGTH_SHORT).show() }
                4 -> showRenameDialog(item)
                5 -> showDeleteDialog(item)
                6 -> showProperties(item)
                7 -> extractHere(item.file)
                8 -> showExtractToDialog(item.file)
                9 -> openTextViewer(item.file)
                10 -> openImageViewer(item.file)
                11 -> openMusicPlayer(item.file)
                12 -> openAsType(item.file, "video/*")
                13 -> FileUtils.openFile(this, item.file)
                14 -> openApkTools(item.file)
                15 -> compressSingle(item.file)
            }
            true
        }
        popup.show()
    }

    private fun openAsType(file: File, mimeType: String) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAdbPermissionDialog() {
        val installedApps = AdbUtils.detectInstalledAdbApps(this)

        // 显示当前 Shizuku 状态作为标题
        val statusText = buildString {
            append("Root: ")
            append(if (AdbUtils.isRootAvailable()) "✓" else "✗")
            append("\nShizuku服务: ")
            append(if (AdbUtils.isShizukuRunning()) "✓运行中" else "✗未运行")
            append("\n本应用已授权: ")
            append(if (AdbUtils.isShizukuAuthorized()) "✓" else "✗")
        }

        // 没有任何特权权限，也没有可用的ADB应用
        if (!AdbUtils.isPrivilegedAccessAvailable() && installedApps.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.adb_permission)
                .setMessage(getString(R.string.adb_no_app_msg))
                .setPositiveButton(R.string.adb_install_shizuku) { _, _ ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "无法打开应用商店", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val items = mutableListOf<String>()

        // 1. 申请 Shizuku 权限（仅当 Shizuku 运行但未授权时显示）
        if (AdbUtils.isShizukuRunning() && !AdbUtils.isShizukuAuthorized()) {
            items.add("🔑 " + getString(R.string.shizuku_request_permission))
        }

        // 2. 浏览系统目录
        for (dir in AdbUtils.SYSTEM_DIRS) {
            items.add("浏览 $dir")
        }

        // 3. 打开已安装的ADB应用
        for (app in installedApps) {
            items.add("打开 ${app.name}")
        }

        // 4. 打开无线调试（用于在 Shizuku 中启动服务）
        items.add(getString(R.string.shizuku_open_wireless_debug))

        // 5. 开发者选项
        items.add(getString(R.string.adb_developer_options))

        AlertDialog.Builder(this)
            .setTitle(R.string.adb_permission)
            .setMessage(statusText)
            .setItems(items.toTypedArray()) { _, which ->
                var idx = 0
                // 申请权限
                if (AdbUtils.isShizukuRunning() && !AdbUtils.isShizukuAuthorized()) {
                    if (which == idx) {
                        requestShizukuPermissionWithGuide()
                        return@setItems
                    }
                    idx++
                }

                // 浏览系统目录
                if (which < idx + AdbUtils.SYSTEM_DIRS.size) {
                    val dir = AdbUtils.SYSTEM_DIRS[which - idx]
                    browseSystemDir(dir)
                    return@setItems
                }
                idx += AdbUtils.SYSTEM_DIRS.size

                // 打开ADB应用
                if (which < idx + installedApps.size) {
                    val appIdx = which - idx
                    AdbUtils.openAdbApp(this, installedApps[appIdx].packageName)
                    return@setItems
                }
                idx += installedApps.size

                // 无线调试
                if (which == idx) {
                    AdbUtils.openWirelessDebuggingSettings(this)
                    return@setItems
                }
                idx++

                // 开发者选项
                if (which == idx) {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    /**
     * 浏览系统目录（通过 Root 或 Shizuku 权限）
     * 若 Shizuku 服务运行但未授权，先发起授权请求；授权成功后通过回调继续访问
     */
    private fun browseSystemDir(path: String) {
        if (AdbUtils.isRootAvailable() || AdbUtils.isShizukuAuthorized()) {
            rootMode = true
            rootCurrentPath = path
            val rootFiles = AdbUtils.listFilesWithPrivilege(path)
            if (rootFiles.isEmpty()) {
                Toast.makeText(this, R.string.adb_access_failed, Toast.LENGTH_LONG).show()
                return
            }
            val items = rootFiles.filter { showHidden || !it.name.startsWith(".") }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .map { rf -> FileItem(File(rf.path)) }
            adapter.clearSelection()
            adapter.submit(items)
            binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            updateToolbarTitle()
            val prefix = when {
                AdbUtils.isRootAvailable() -> "[Root]"
                AdbUtils.isShizukuAuthorized() -> "[Shizuku]"
                else -> "[ADB]"
            }
            binding.pathText.text = "$prefix $path"
        } else if (AdbUtils.isShizukuRunning()) {
            // 服务运行但未授权，先申请权限
            pendingPathAfterPermission = path
            requestShizukuPermissionWithGuide()
        } else {
            // 既无 Root，Shizuku 也没运行
            val installedAdbApps = AdbUtils.detectInstalledAdbApps(this)
            if (installedAdbApps.isNotEmpty()) {
                showShizukuStartGuide(installedAdbApps)
            } else {
                Toast.makeText(this, R.string.adb_not_available, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showRenameDialog(item: FileItem) {
        val input = android.widget.EditText(this).apply { setText(item.name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && FileUtils.rename(item.file, newName)) {
                    loadDirectory(currentDir)
                } else {
                    Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteDialog(item: FileItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage("${getString(R.string.delete)} ${item.name}?")
            .setPositiveButton(R.string.delete) { _, _ ->
                if (FileUtils.deleteRecursively(item.file)) {
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                    loadDirectory(currentDir)
                } else if (rootMode) {
                    if (AdbUtils.deleteWithPrivilege(item.file.absolutePath)) {
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                        loadRootDirectory(rootCurrentPath)
                    } else {
                        Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProperties(item: FileItem) {
        val msg = buildString {
            append("${getString(R.string.open)}: ${item.name}\n")
            append("Path: ${item.file.absolutePath}\n")
            append("Type: ${if (item.isDirectory) "Folder" else "File"}\n")
            append("Size: ${item.formattedSize()}\n")
            append("Modified: ${item.formattedDate()}\n")
            append("Readable: ${item.file.canRead()}\n")
            append("Writable: ${item.file.canWrite()}")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.properties)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun doPaste() {
        if (clipboard.isEmpty()) {
            Toast.makeText(this, "Clipboard empty", Toast.LENGTH_SHORT).show()
            return
        }
        var allOk = true
        clipboard.forEach { src ->
            val dest = File(currentDir, src.name)
            val ok = if (isCut) {
                val r = src.file.renameTo(dest)
                if (!r) {
                    val c = FileUtils.copyFile(src.file, dest)
                    if (c) FileUtils.deleteRecursively(src.file) else allOk = false
                }
                r || dest.exists()
            } else {
                FileUtils.copyFile(src.file, dest)
            }
            if (!ok) allOk = false
        }
        if (isCut) clipboard = emptyList()
        Toast.makeText(this, if (allOk) "Done" else "Partial fail", Toast.LENGTH_SHORT).show()
        loadDirectory(currentDir)
    }

    private fun showCompressDialog() {
        val selected = adapter.getSelected()
        val filesToCompress = if (selected.isNotEmpty()) {
            selected.map { it.file }
        } else {
            Toast.makeText(this, "Select files first", Toast.LENGTH_SHORT).show()
            return
        }

        val input = android.widget.EditText(this).apply {
            var baseName = ""
            if (filesToCompress.size == 1) baseName = filesToCompress[0].nameWithoutExtension
            setText("$baseName.zip")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.compress_to_zip)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val zipName = input.text.toString().trim()
                if (zipName.isEmpty()) return@setPositiveButton
                val finalName = if (zipName.endsWith(".zip", true)) zipName else "$zipName.zip"
                val zipOutput = File(currentDir, finalName)
                Toast.makeText(this, "Compressing...", Toast.LENGTH_SHORT).show()
                Thread {
                    val ok = FileUtils.compressToZip(filesToCompress, zipOutput)
                    runOnUiThread {
                        if (ok) {
                            Toast.makeText(this, "Done: $finalName", Toast.LENGTH_SHORT).show()
                            loadDirectory(currentDir)
                        } else {
                            Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun compressSingle(file: File) {
        val input = android.widget.EditText(this).apply { setText("${file.nameWithoutExtension}.zip") }
        AlertDialog.Builder(this)
            .setTitle(R.string.compress_to_zip)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val zipName = input.text.toString().trim()
                if (zipName.isEmpty()) return@setPositiveButton
                val finalName = if (zipName.endsWith(".zip", true)) zipName else "$zipName.zip"
                val zipOutput = File(currentDir, finalName)
                Thread {
                    val ok = FileUtils.compressToZip(listOf(file), zipOutput)
                    runOnUiThread {
                        if (ok) { loadDirectory(currentDir) } else { Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show() }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun extractHere(zipFile: File) {
        val destDir = File(currentDir, zipFile.nameWithoutExtension)
        Thread {
            val ok = FileUtils.extractZip(zipFile, destDir)
            runOnUiThread {
                if (ok) loadDirectory(currentDir) else Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun showExtractDialog() {
        val selected = adapter.getSelected()
        if (selected.isEmpty()) { Toast.makeText(this, "Select ZIP first", Toast.LENGTH_SHORT).show(); return }
        if (selected.size > 1) { Toast.makeText(this, "Only one ZIP", Toast.LENGTH_SHORT).show(); return }
        val file = selected[0].file
        if (!file.isFile || !FileUtils.isZipFile(file)) { Toast.makeText(this, "Not a ZIP", Toast.LENGTH_SHORT).show(); return }
        showExtractToDialog(file)
    }

    private fun showExtractToDialog(zipFile: File) {
        val input = android.widget.EditText(this).apply { setText(zipFile.nameWithoutExtension) }
        AlertDialog.Builder(this)
            .setTitle(R.string.extract_to)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val folderName = input.text.toString().trim()
                if (folderName.isEmpty()) return@setPositiveButton
                val destDir = File(currentDir, folderName)
                Thread {
                    val ok = FileUtils.extractZip(zipFile, destDir)
                    runOnUiThread {
                        if (ok) loadDirectory(currentDir) else Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
