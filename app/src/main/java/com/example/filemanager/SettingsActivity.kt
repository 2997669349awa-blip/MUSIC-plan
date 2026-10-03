package com.example.filemanager

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.filemanager.databinding.ActivitySettingsBinding
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLanguage(getSavedLanguage())
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.settings_title)

        // 版本号
        binding.tvVersion.text = "v${packageManager.getPackageInfo(packageName, 0).versionName}"

        // 当前语言
        updateLanguageDisplay()

        binding.btnLang.setOnClickListener {
            showLanguageDialog()
        }

        // Root检测
        checkRootStatus()

        binding.btnCheckRoot.setOnClickListener {
            checkRootStatus()
        }

        binding.swRoot.setOnCheckedChangeListener { _, isChecked ->
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("root_enabled", isChecked).apply()
            if (isChecked) {
                Toast.makeText(this, R.string.root_enabled, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAdb.setOnClickListener {
            showAdbInfo()
        }

        // 启动三角洲行动
        binding.btnLaunchDeltaForce.setOnClickListener {
            launchDeltaForce()
        }

        // 启动任意应用（输入包名）
        binding.btnLaunchByPkg.setOnClickListener {
            val pkg = binding.etPackageName.text.toString().trim()
            if (pkg.isEmpty()) {
                Toast.makeText(this, R.string.launch_by_pkg_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            launchAppByPackage(pkg)
        }

        // 更新公告
        binding.btnChangelog.setOnClickListener {
            showChangelog()
        }

        // 检测已安装的ADB应用并显示状态（延迟执行避免启动时 ShizukuProvider 查询阻塞）
        binding.root.post {
            try {
                updateAdbStatus()
            } catch (e: Throwable) {
            }
        }
    }

    /**
     * 启动三角洲行动
     * 包名：com.tencent.tmgp.dfm
     * 采用多级回退策略确保能启动：
     *   1. getLaunchIntentForPackage（标准方式）
     *   2. 查询 MAIN/LAUNCHER intent 活动
     *   3. 直接构造显式 ComponentName 启动主 Activity
     *   4. 以上都失败则提示用户从桌面手动启动
     */
    private fun launchDeltaForce() {
        val packageName = "com.tencent.tmgp.dfm"

        // 先确认是否安装
        val installed = try {
            packageManager.getPackageInfo(packageName, 0) != null
        } catch (e: Exception) {
            false
        }

        if (!installed) {
            showDeltaForceNotInstalledDialog()
            return
        }

        // 方式1: 标准 getLaunchIntentForPackage
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Toast.makeText(this, R.string.launch_delta_force_starting, Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: Exception) {
        }

        // 方式2: 查询带 LAUNCHER 类别的 MAIN activity
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
            val activities = packageManager.queryIntentActivities(mainIntent, 0)
            if (activities.isNotEmpty()) {
                val resolveInfo = activities[0]
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setClassName(packageName, resolveInfo.activityInfo.name)
                }
                startActivity(intent)
                Toast.makeText(this, R.string.launch_delta_force_starting, Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: Exception) {
        }

        // 方式3: 尝试常见的腾讯游戏主 Activity 名
        val possibleMainActivities = listOf(
            "$packageName.MainActivity",
            "$packageName.MainUnityActivity",
            "$packageName.UnityPlayerActivity",
            "$packageName.SplashActivity",
            "$packageName.EntryActivity",
            "$packageName.AppActivity"
        )
        for (mainActivity in possibleMainActivities) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setClassName(packageName, mainActivity)
                }
                startActivity(intent)
                Toast.makeText(this, R.string.launch_delta_force_starting, Toast.LENGTH_SHORT).show()
                return
            } catch (e: Exception) {
                // 该 Activity 不存在，继续尝试下一个
            }
        }

        // 所有方式都失败，提示用户手动启动
        showDeltaForceLaunchFailedDialog()
    }

    /**
     * 三角洲行动已安装但无法通过代码启动时提示用户
     */
    private fun showDeltaForceLaunchFailedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.launch_delta_force)
            .setMessage(getString(R.string.delta_force_launch_failed))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * 三角洲行动未安装时的引导对话框
     */
    private fun showDeltaForceNotInstalledDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.launch_delta_force)
            .setMessage(getString(R.string.delta_force_not_installed))
            .setPositiveButton(R.string.delta_force_install) { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("market://details?id=com.tencent.tmgp.dfm"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e: Exception) {
                    // 应用商店不可用，打开网页
                    try {
                        val intent = Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.tencent.tmgp.dfm"))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (e2: Exception) {
                        Toast.makeText(this, R.string.delta_force_open_store_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 启动任意应用（按包名）
     * 复用三角洲行动的多级回退启动策略
     */
    private fun launchAppByPackage(packageName: String) {
        // 简单的包名合法性校验
        if (!packageName.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))) {
            Toast.makeText(this, R.string.launch_by_pkg_invalid, Toast.LENGTH_LONG).show()
            return
        }

        // 确认是否安装
        val installed = try {
            packageManager.getPackageInfo(packageName, 0) != null
        } catch (e: Exception) {
            false
        }
        if (!installed) {
            AlertDialog.Builder(this)
                .setTitle(R.string.launch_by_pkg)
                .setMessage(getString(R.string.launch_by_pkg_not_installed, packageName))
                .setPositiveButton(R.string.delta_force_install) { _, _ ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("market://details?id=$packageName"))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        } catch (e2: Exception) {
                            Toast.makeText(this, R.string.delta_force_open_store_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        // 方式1: 标准 getLaunchIntentForPackage
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Toast.makeText(this, getString(R.string.launch_by_pkg_starting, packageName), Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: Exception) {
        }

        // 方式2: 查询 MAIN/LAUNCHER
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
            val activities = packageManager.queryIntentActivities(mainIntent, 0)
            if (activities.isNotEmpty()) {
                val resolveInfo = activities[0]
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setClassName(packageName, resolveInfo.activityInfo.name)
                }
                startActivity(intent)
                Toast.makeText(this, getString(R.string.launch_by_pkg_starting, packageName), Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: Exception) {
        }

        // 方式3: 常见主 Activity 名
        val possibleMainActivities = listOf(
            "$packageName.MainActivity",
            "$packageName.MainUnityActivity",
            "$packageName.UnityPlayerActivity",
            "$packageName.SplashActivity",
            "$packageName.EntryActivity",
            "$packageName.AppActivity"
        )
        for (mainActivity in possibleMainActivities) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setClassName(packageName, mainActivity)
                }
                startActivity(intent)
                Toast.makeText(this, getString(R.string.launch_by_pkg_starting, packageName), Toast.LENGTH_SHORT).show()
                return
            } catch (e: Exception) {
            }
        }

        // 失败提示
        AlertDialog.Builder(this)
            .setTitle(R.string.launch_by_pkg)
            .setMessage(getString(R.string.launch_by_pkg_failed, packageName))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * 显示更新公告（1.0 ~ 1.6.5 全部更新）
     */
    private fun showChangelog() {
        val changelog = """
v1.6.5
• 优化：移除文件列表项右侧的三点按钮（点击无功能），界面更简洁
• 优化：文件管理器应用图标更新为文件夹样式

v1.6.4
• 新增：终端集成 Shizuku/ADB 权限，启动时自动申请，命令以 ADB 身份执行
• 新增：终端大量内建 Linux 命令（ls/cat/cp/mv/rm/mkdir/touch/stat/find/grep/wc/head/tail/echo/date/df/free/whoami/id/uname/which 等 busybox 风格）
• 新增：终端命令历史（上下方向键）、特权模式提示符、shizuku 命令查看/申请权限
• 新增：独立音乐插件 APK（com.example.musicplugin），主应用检测到插件已安装后，音乐播放功能自动跳转到插件
• 音乐插件：仿主流音乐 App UI，本地音乐扫描、搜索、播放、音质切换（标准/较高/无损）、播放模式（列表循环/单曲循环/随机）

v1.6.3
• 新增：内置终端模拟器（主页工具栏标题旁的 CMD 图标进入）
• 新增：支持 ls/cd/cat/echo/mkdir/rm 等常用命令，维护工作目录
• 新增：命令历史（上/下方向键切换）、清屏、help 帮助
• 新增：已 Root 设备自动以 su 执行，可访问系统目录

v1.6.2
• 新增：输入任意应用包名即可启动应用
• 新增：更新公告页（1.0~1.6.2 全部更新内容）
• 优化：修复 Android 11+ 包可见性导致无法启动其他应用的问题（添加 QUERY_ALL_PACKAGES 权限和 <queries> 声明）

v1.6.1
• 新增：设置页启动三角洲行动（com.tencent.tmgp.dfm）
• 新增：查看根目录及修改文件（需 Root 或 Shizuku）
• 新增：未知/特殊扩展名一律以文本文档方式打开

v1.6.0
• 新增：真正集成 Shizuku 官方 API（rikka.shizuku:api/shared/aidl）
• 新增：注册 ShizukuProvider，支持 Shizuku 服务状态检测与本应用授权检测
• 新增：启动时智能引导（未安装/服务未启动/未授权/已授权 四态处理）
• 新增：通过 Shizuku 的 IShizukuService 执行特权命令访问 /data
• 新增：设置页显示「已安装/服务运行/本应用授权」三态状态
• 修复：闪退问题（补全 Shizuku provider 依赖，延迟初始化）

v1.5.1
• 修复：debug 与 release 使用同一固定签名（release.keystore），解决升级签名冲突
• 修复：Shizuku 包名更正为 moe.shizuku.privileged.api
• 修复：设置页 ADB 卡片描述错误显示为 Root 内容的问题

v1.5.0
• 新增：内置音乐播放器（不依赖系统，支持播放列表、上下首、循环、随机）
• 新增：自动检测 Shizuku 等应用并申请 ADB 权限
• 新增：授权后可访问 /data、/system 等系统目录

v1.4.0
• 新增：设置页面，支持中英文切换
• 新增：主页顶部返回上级按钮、主页按钮
• 新增：Root 访问 /data 等系统目录
• 新增：内置图片查看器（不再跳转系统相册）

v1.3.0
• 新增：文件自动编码检测
• 新增：长按文件弹出"打开方式"菜单（以文本/图片/音频/视频打开、用其他应用打开）
• 新增：MT 管理器风格双排网格视图
• 新增：ADB 权限申请入口
• 新增：APK 反编译、签名、去除签名校验

v1.1.0
• 基础文件浏览、复制/剪切/粘贴/删除/重命名
• 文本查看器、ZIP 压缩/解压
• 多种排序方式

v1.0.0
• 初始版本：基础文件管理功能
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle(R.string.changelog)
            .setMessage(changelog)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun updateAdbStatus() {
        val installedApps = AdbUtils.detectInstalledAdbApps(this)
        val tvAdbStatus = binding.tvAdbStatus

        // 综合显示 Shizuku 安装/服务/授权三态
        val statusText = buildString {
            // 安装状态
            if (installedApps.isNotEmpty()) {
                val appNames = installedApps.joinToString(", ") { it.name }
                append(getString(R.string.adb_detected_installed, appNames))
            } else {
                append(getString(R.string.adb_not_detected))
            }
            append("\n")
            // Shizuku 服务运行状态
            append("Shizuku服务: ")
            append(if (AdbUtils.isShizukuRunning()) "运行中" else "未运行")
            append("\n")
            // 本应用授权状态
            append("本应用授权: ")
            append(if (AdbUtils.isShizukuAuthorized()) "已授权" else "未授权")
        }

        tvAdbStatus.text = statusText

        // 颜色：已授权绿色；已安装但未授权橙色；未安装灰色
        val color = when {
            AdbUtils.isShizukuAuthorized() -> 0xFF4CAF50.toInt()   // 绿
            AdbUtils.isShizukuRunning() || installedApps.isNotEmpty() -> 0xFFFF9800.toInt() // 橙
            else -> 0xFF888888.toInt()                              // 灰
        }
        tvAdbStatus.setTextColor(color)
    }

    private fun getSavedLanguage(): String {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        return prefs.getString("language", "zh") ?: "zh"
    }

    private fun updateLanguageDisplay() {
        val lang = getSavedLanguage()
        binding.tvCurrentLang.text = if (lang == "en") getString(R.string.english) else getString(R.string.chinese)
    }

    private fun showLanguageDialog() {
        val current = getSavedLanguage()
        val items = arrayOf(getString(R.string.chinese), getString(R.string.english))
        val checked = if (current == "en") 1 else 0

        AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(items, checked) { dialog, which ->
                val lang = if (which == 0) "zh" else "en"
                getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit().putString("language", lang).apply()
                applyLanguage(lang)
                dialog.dismiss()
                // 重启Activity以应用语言
                recreate()
            }
            .show()
    }

    private fun applyLanguage(lang: String) {
        val locale = if (lang == "en") Locale.ENGLISH else Locale.CHINESE
        Locale.setDefault(locale)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun checkRootStatus() {
        binding.tvRootStatus.setText(R.string.root_checking)
        binding.swRoot.isEnabled = false

        Thread {
            val hasRoot = RootUtils.isRootAvailable()
            runOnUiThread {
                if (hasRoot) {
                    binding.tvRootStatus.setText(R.string.root_enabled)
                    binding.tvRootStatus.setTextColor(0xFF4CAF50.toInt())
                    binding.swRoot.isEnabled = true
                    val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                    binding.swRoot.isChecked = prefs.getBoolean("root_enabled", false)
                } else {
                    binding.tvRootStatus.setText(R.string.root_disabled)
                    binding.tvRootStatus.setTextColor(0xFFF44336.toInt())
                    binding.swRoot.isEnabled = false
                    binding.swRoot.isChecked = false
                }
            }
        }.start()
    }

    private fun showAdbInfo() {
        // 检测已安装的ADB应用
        val installedApps = AdbUtils.detectInstalledAdbApps(this)

        val items = mutableListOf<String>()
        // 1. 申请 Shizuku 权限（仅当 Shizuku 运行但未授权时显示）
        val showRequestItem = AdbUtils.isShizukuRunning() && !AdbUtils.isShizukuAuthorized()
        if (showRequestItem) {
            items.add("🔑 " + getString(R.string.shizuku_request_permission))
        }
        // 2. 如果有已安装的ADB应用，添加打开选项
        for (app in installedApps) {
            items.add(getString(R.string.adb_open_app) + ": " + app.name)
        }
        // 3. 打开无线调试
        items.add(getString(R.string.shizuku_open_wireless_debug))
        // 4. ADB说明
        items.add(getString(R.string.adb_info_title))
        // 5. 开发者选项
        items.add(getString(R.string.adb_developer_options))
        // 6. 如果没有安装ADB应用，添加安装引导
        if (installedApps.isEmpty()) {
            items.add(getString(R.string.adb_install_shizuku))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.adb_permission)
            .setItems(items.toTypedArray()) { _, which ->
                var idx = 0
                // 申请权限
                if (showRequestItem) {
                    if (which == idx) {
                        requestShizukuPermissionFromSettings()
                        return@setItems
                    }
                    idx++
                }
                // 打开已安装的ADB应用
                if (which < idx + installedApps.size) {
                    val app = installedApps[which - idx]
                    val opened = AdbUtils.openAdbApp(this, app.packageName)
                    if (!opened) {
                        Toast.makeText(this, R.string.adb_app_launch_failed, Toast.LENGTH_SHORT).show()
                    }
                    return@setItems
                }
                idx += installedApps.size

                // 无线调试
                if (which == idx) {
                    AdbUtils.openWirelessDebuggingSettings(this)
                    return@setItems
                }
                idx++

                // ADB说明
                if (which == idx) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.adb_info_title)
                        .setMessage(getString(R.string.adb_info_content))
                        .setPositiveButton("OK", null)
                        .show()
                    return@setItems
                }
                idx++

                // 开发者选项
                if (which == idx) {
                    try {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "无法打开开发者选项", Toast.LENGTH_SHORT).show()
                    }
                    return@setItems
                }
                idx++

                // 安装Shizuku
                if (which == idx) {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"))
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "无法打开应用商店", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private val SHIZUKU_REQUEST_CODE_SETTINGS = 2001

    private val shizukuPermissionListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE_SETTINGS) {
            runOnUiThread {
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, R.string.shizuku_granted, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, R.string.shizuku_denied, Toast.LENGTH_LONG).show()
                }
                // 重新检测状态
                updateAdbStatus()
            }
        }
    }

    /**
     * 在设置页申请 Shizuku 权限
     */
    private fun requestShizukuPermissionFromSettings() {
        val requested = AdbUtils.requestShizukuPermission(SHIZUKU_REQUEST_CODE_SETTINGS)
        if (!requested) {
            // 用户曾拒绝过，需要去 Shizuku 中手动允许
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

    override fun onResume() {
        super.onResume()
        // 从 Shizuku 返回后重新检测状态
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Throwable) {
        }
        try {
            updateAdbStatus()
        } catch (e: Throwable) {
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Throwable) {
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
