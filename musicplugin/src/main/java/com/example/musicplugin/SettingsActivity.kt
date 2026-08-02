package com.example.musicplugin

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 设置页面 v1.1.4
 *
 * 仿网易云登录页风格（深色背景 + 灰白文字）
 * 包含：
 * - 公告（v1.0.0 - v1.1.4 全部版本更新记录，点击弹窗）
 * - 功能入口（搜索PV / 网易云登录 / 文件管理器）
 * - 音乐源切换（网易云/酷狗/酷我/汽水/QQ）
 * - 关于（应用信息 + 制作人 QQ）
 * - 网易云登录入口
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(0xFF0F0F0F.toInt())
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }
        scrollView.addView(container)
        setContentView(scrollView)

        // 标题
        container.addView(buildTitle("设置"))

        // ====== 公告（v1.1.2：整合成按钮，点击弹窗展示所有版本）======
        container.addView(buildSectionTitle("公告"))
        container.addView(buildDivider())
        container.addView(buildButtonItem("查看全部更新公告") {
            showAnnouncementsDialog()
        })

        // ====== 功能入口 ======
        container.addView(buildSectionTitle("功能"))
        container.addView(buildDivider())
        // v1.1.4：搜索PV 板块入口
        container.addView(buildButtonItem("搜索PV") {
            startActivity(Intent(this, PvSearchActivity::class.java))
        })
        // v1.1.7：听歌识曲悬浮球
        container.addView(buildButtonItem("听歌识曲悬浮球") {
            toggleFloatingBall()
        })
        container.addView(buildButtonItem("听歌识曲（手动）") {
            startActivity(Intent(this, RecognizeActivity::class.java))
        })
        container.addView(buildButtonItem("网易云登录") {
            startActivity(Intent(this, LoginWebActivity::class.java))
        })
        // v1.2.7：HTML 介绍页入口
        container.addView(buildButtonItem("HTML 介绍页") {
            startActivity(Intent(this, HtmlIntroActivity::class.java))
        })
        container.addView(buildButtonItem("打开文件管理器") {
            openFileManager()
        })

        // ====== v1.1.1：音乐源切换 ======
        container.addView(buildSectionTitle("音乐源"))
        container.addView(buildDivider())
        container.addView(buildSourceSwitchCard())

        // ====== 关于 ======
        container.addView(buildSectionTitle("关于"))
        container.addView(buildDivider())
        container.addView(buildAboutCard())

        // 底部留白
        container.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                200
            )
        })
    }

    private fun buildTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 24f
        setPadding(0, 0, 0, 32)
        gravity = Gravity.CENTER
    }

    private fun buildSectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFF1ED760.toInt())
        textSize = 16f
        setPadding(0, 48, 0, 16)
    }

    private fun buildDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { setMargins(0, 0, 0, 24) }
        setBackgroundColor(0xFF333333.toInt())
    }

    private fun buildAnnouncementCard(title: String, content: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            setBackgroundColor(0xFF1A1A1A.toInt())
        }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 24) }

        card.addView(TextView(this).apply {
            text = title
            setTextColor(0xFF1ED760.toInt())
            textSize = 15f
            setPadding(0, 0, 0, 12)
        })
        card.addView(TextView(this).apply {
            this.text = content
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 13f
            setLineSpacing(6f, 1f)
        })
        return card
    }

    private fun buildButtonItem(text: String, onClick: () -> Unit): LinearLayout {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 28, 32, 28)
            setBackgroundColor(0xFF1A1A1A.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        item.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 16) }

        item.addView(TextView(this).apply {
            setText(text)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        item.addView(TextView(this).apply {
            setText(">")
            setTextColor(0xFF888888.toInt())
            textSize = 18f
        })
        return item
    }

    /**
     * v1.1.2：公告弹窗
     * 点击「查看全部更新公告」按钮后弹出，展示所有版本更新记录
     */
    private fun showAnnouncementsDialog() {
        val sb = StringBuilder()
        sb.append("【v1.2.7 更新】\n")
        sb.append("• PV播放器改用TextureView替代SurfaceView，彻底修复画面比例变形/拉伸\n")
        sb.append("• 修复PV退出后台再回来画面丢失的问题（SurfaceTexture持久化）\n")
        sb.append("• 设置中新增「HTML介绍页」入口，点击即可查看高级介绍页\n\n")
        sb.append("【v1.2.6 更新】\n")
        sb.append("• PV播放器彻底重构：改用MediaPlayer+SurfaceView，后台返回不再重新加载\n")
        sb.append("• UI全面升级：赛博汽水深色主题，圆角卡片+玻璃态层次\n")
        sb.append("• 状态栏沉浸，搜索栏圆角化，Tab指示器药丸样式\n")
        sb.append("• 封面圆角化，底部栏半透明玻璃态\n")
        sb.append("• 全新高级HTML介绍页上线\n\n")
        sb.append("【v1.2.5 更新】\n")
        sb.append("• 修复PV播放时退到后台再回来视频重新加载的问题（暂停后恢复，不再重新缓冲）\n\n")
        sb.append("【v1.2.4 更新】\n")
        sb.append("• PV播放器全面升级：全屏沉浸（隐藏状态栏+导航栏）\n")
        sb.append("• 播放PV时自动暂停背景音乐，退出PV自动恢复（不再双重奏）\n")
        sb.append("• PV播放器加进度条+时间显示，可拖动跳转\n")
        sb.append("• PV播放器加快进/快退按钮（±10秒）\n")
        sb.append("• PV记忆播放位置，重新进入从上次位置继续\n")
        sb.append("• MV按钮统一用应用内PV播放器，不再跳系统播放器\n\n")
        sb.append("【v1.2.3 更新】\n")
        sb.append("• 歌词滚动优化：刷新间隔 300ms→80ms，歌词不再滞后于音乐\n")
        sb.append("• 双击返回键退出，防止误触（第一次提示，2秒内再按才退出）\n")
        sb.append("• 恢复底部栏 MV/PV 按钮\n")
        sb.append("• MV搜索支持中文搜外文（输入中文名能搜到对应外文MV）\n")
        sb.append("• 歌词匹配优化：搜索结果按歌名/歌手精确匹配，减少搜错歌导致无歌词\n")
        sb.append("• 修复切歌失败时仍弹\"无法获取播放链接\"的误提示（正在播放则静默）\n\n")
        sb.append("【v1.2.2 更新】\n")
        sb.append("• 代码加固：启用 R8 混淆 + 资源压缩，防反编译\n")
        sb.append("• 签名校验：启动时检测 APK 签名，非原版签名弹窗提示并退出\n")
        sb.append("• 移除调试日志，防止信息泄露\n\n")
        sb.append("【v1.2.1 更新】\n")
        sb.append("• 移除酷狗登录，恢复为仅网易云登录\n")
        sb.append("• 应用图标优化：音符更圆润精致，背景加深色渐变层次\n")
        sb.append("• 通知栏紧凑视图改为 上一首/播放暂停/下一首，关闭按钮仅在展开视图显示\n")
        sb.append("• 本地播放支持网易云 ncm 加密格式（自动解密播放）\n")
        sb.append("• 本地列表登记酷狗 kgm/kgma/vpr、QQ qmc 系列等专属后缀\n\n")
        sb.append("【v1.2.0 更新】\n")
        sb.append("• 悬浮球初始状态改为应用图标\n")
        sb.append("• 识别失败显示 ✗ 叉号\n")
        sb.append("• 识别中保留音浪柱随音跳动动画\n")
        sb.append("• 录音时长提升至12秒，提高识别准确率\n")
        sb.append("• 新增酷狗音乐登录（酷狗概念版/酷狗音乐），登录后默认切为酷狗源\n\n")
        sb.append("【v1.1.9 更新】\n")
        sb.append("• 播放器增加可拖动进度条，自由跳转到想播的位置\n")
        sb.append("• 通知栏同步显示播放进度\n")
        sb.append("• 底部栏重新排列：投屏移到顶行，进度条居中\n")
        sb.append("• 搜索时歌词自动收回\n")
        sb.append("• 悬浮球酷狗风格重新设计：绿色音符→旋转消失→音浪柱录音→加载圈→对勾展开结果\n\n")
        sb.append("【v1.1.8修复版6 更新】\n")
        sb.append("• 修复悬浮球点击播放后卡在结果界面（跳转播放后自动重置回待识别状态）\n\n")
        sb.append("【v1.1.8修复版5 更新】\n")
        sb.append("• 修复歌曲标题提取（Shazam API v2 标题在 attributes.name，不在 title 字段）\n")
        sb.append("• 修复歌手提取（在 attributes.subtitle）\n\n")
        sb.append("【v1.1.8修复版4 更新】\n")
        sb.append("• 修复指纹二进制编码 CRC32 校验错误（根本原因：sizeMinusHeader 占位为0时算CRC，再回填正确值，导致CRC与数据不匹配，Shazam API 静默拒绝所有指纹）\n")
        sb.append("• 修复后将 sizeMinusHeader 先设为正确值再计算 CRC32，与 JS 参考实现完全一致\n\n")
        sb.append("【v1.1.8修复版3 更新】\n")
        sb.append("• 修复 Shazam API 响应解析（之前解析格式完全错误导致永远识别失败）\n\n")
        sb.append("【v1.1.8修复版2 更新】\n")
        sb.append("• 修复 Hanning 窗公式偏移（与 Shazam 原版完全对齐）\n")
        sb.append("• 全链路改用 Double 精度计算（Hanning窗/环形缓冲区/FFT输入）\n\n")
        sb.append("【v1.1.8修复版1 更新】\n")
        sb.append("• 修复听歌识曲指纹算法精度问题（FFT改用Double精度计算）\n")
        sb.append("• 修复Token获取失败（更新为Shazam最新认证方案）\n\n")
        sb.append("【v1.1.8 更新】\n")
        sb.append("• 听歌识曲改用 Shazam 引擎，完全免费，无需注册 Token\n")
        sb.append("• 悬浮球直接完成听歌识曲（录音→识别→显示结果→点击播放）\n")
        sb.append("• 悬浮球状态提示：绿色=待识别，红色=录音中，橙色=识别中\n")
        sb.append("• 长按悬浮球可关闭\n")
        sb.append("• 修复识曲后点击播放不生效的问题\n")
        sb.append("• 应用图标缩小居中\n\n")
        sb.append("【v1.1.7 更新】\n")
        sb.append("• 新增听歌识曲：设置→功能→听歌识曲悬浮球\n")
        sb.append("• 系统全局悬浮球，任何应用下点击即可识曲\n")
        sb.append("• 录音10秒→识别→回查曲库→一键播放\n")
        sb.append("• 应用图标改为纯黑（去掉绿色圈和拼图块）\n")
        sb.append("• 移除桌面独立图标，仅通过文件管理器入口\n\n")
        sb.append("【v1.1.6 更新】\n")
        sb.append("• 移除底部栏右下角 MV 按钮（PV 搜索已在设置里）\n")
        sb.append("• 恢复歌曲行歌手/音质副标题显示\n\n")
        sb.append("【v1.1.5 更新】\n")
        sb.append("• 本地音乐进入不再自动扫描，先询问是否扫描\n")
        sb.append("• 歌曲行简化：只显示歌名，隐藏歌手/音质副标题\n")
        sb.append("• 空提示可点击触发扫描\n\n")
        sb.append("【v1.1.4 更新】\n")
        sb.append("• 新增「搜索PV」板块（设置→功能→搜索PV）\n")
        sb.append("• 全网匹配 MV/PV，内置播放器自动播放\n")
        sb.append("• 修复网易云MV搜索接口失效、视频层不显示等问题\n\n")
        sb.append("【v1.1.3 更新】\n")
        sb.append("• 新增酷我音乐源（免费播放，无需登录）\n")
        sb.append("• 酷我搜索+播放URL完整支持\n")
        sb.append("• 播放用 antiserver.kuwo.cn 返回真实 MP3\n\n")
        sb.append("【v1.1.2 更新】\n")
        sb.append("• 真正多源切换：搜索/播放/歌词按所选源分发\n")
        sb.append("• 酷狗/QQ 源播放拿不到自动退回网易云\n")
        sb.append("• 公告整合成按钮，点击弹窗展示\n\n")
        sb.append("【v1.1.1 更新】\n")
        sb.append("• 多音乐源切换（网易云/酷狗/汽水/QQ）\n")
        sb.append("• 封面对得上才展示（歌名校验）\n")
        sb.append("• 自动兜底（酷狗→QQ→网易云）\n\n")
        sb.append("【v1.1.0 更新】\n")
        sb.append("• 接入酷狗/QQ 音乐封面\n")
        sb.append("• 歌词滚动用 scrollTo 瞬间定位\n\n")
        sb.append("【v1.0.10 更新】\n")
        sb.append("• BitmapLoader 去掉 Referer\n")
        sb.append("• 封面三级兜底\n")
        sb.append("• 歌词只在行变化时更新\n\n")
        sb.append("【v1.0.7 更新】\n")
        sb.append("• 更名「MUSIC plan」\n")
        sb.append("• 播放界面网易云风格（封面+歌词滚动）\n")
        sb.append("• 通知栏仿网易云排版（封面大图）\n\n")
        sb.append("【v1.0.6 更新】\n")
        sb.append("• 修复投屏 HTTP 500\n")
        sb.append("• MediaPlayer 移入 Service（酷狗/网易云方案）\n\n")
        sb.append("【v1.0.5 更新】\n")
        sb.append("• 通知栏新增「关闭」按钮\n\n")
        sb.append("【v1.0.4 更新】\n")
        sb.append("• 投屏支持云视听小电视\n")
        sb.append("• 设置页面改版（网易云风格）\n\n")
        sb.append("【v1.0.3 更新】\n")
        sb.append("• 收藏功能\n")
        sb.append("• 歌词翻译\n\n")
        sb.append("【v1.0.2 更新】\n")
        sb.append("• 在线搜索（网易云 API）\n")
        sb.append("• 网易云登录\n")
        sb.append("• MV/PV 播放\n\n")
        sb.append("【v1.0.1 更新】\n")
        sb.append("• 本地音乐扫描播放\n")
        sb.append("• 歌词同步\n")
        sb.append("• 通知栏控制\n\n")
        sb.append("【v1.0.0 初版】\n")
        sb.append("MUSIC plan 首发版本\n")
        sb.append("在线搜索/本地播放/歌词/MediaSession/PV/网易云登录\n\n")
        sb.append("制作人：QQ 2997669349")

        // 用 ScrollView 包 TextView，支持长内容滚动
        val scrollView = android.widget.ScrollView(this)
        val textView = TextView(this).apply {
            text = sb.toString()
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 13f
            setPadding(48, 32, 48, 32)
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("更新公告")
            .setView(scrollView)
            .setPositiveButton("关闭", null)
            .show()
    }

    /**
     * v1.1.1：音乐源切换卡片
     * 四个源可选：网易云/酷狗/汽水/QQ，当前源高亮
     * 切换后立即保存到 SharedPreferences 并应用到 MusicApi
     */
    private fun buildSourceSwitchCard(): LinearLayout {
        val sources = MusicApi.MusicSource.values()
        val current = MusicApi.getSource()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1F1F1F.toInt())
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }

            addView(TextView(this@SettingsActivity).apply {
                text = "选择封面/歌词数据源"
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 12f
                setPadding(0, 0, 0, 12)
            })

            // 每个源一行
            for (source in sources) {
                val row = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(12, 16, 12, 16)
                    isClickable = true
                    isFocusable = true
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener {
                        MusicApi.saveSource(this@SettingsActivity, source)
                        // 刷新卡片
                        recreate()
                    }
                }
                val nameTv = TextView(this@SettingsActivity).apply {
                    text = source.displayName
                    setTextColor(if (source == current) 0xFF4CAF50.toInt() else 0xFFDDDDDD.toInt())
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                val tipTv = TextView(this@SettingsActivity).apply {
                    text = if (source == current) "当前" else "点击切换"
                    setTextColor(0xFF888888.toInt())
                    textSize = 12f
                }
                row.addView(nameTv)
                row.addView(tipTv)
                addView(row)
                addView(View(this@SettingsActivity).apply {
                    setBackgroundColor(0xFF2A2A2A.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                })
            }

            addView(TextView(this@SettingsActivity).apply {
                text = "说明：酷我源可免费播放（无需登录），其他源播放退回网易云。封面按所选源获取，无图自动兜底。"
                setTextColor(0xFF777777.toInt())
                textSize = 11f
                setPadding(0, 12, 0, 0)
            })
        }
    }

    private fun buildAboutCard(): LinearLayout {
        val pkgInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = pkgInfo.versionName
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pkgInfo.longVersionCode else pkgInfo.versionCode.toLong()
        val isTv = packageManager.hasSystemFeature("android.software.leanback")

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(0xFF1A1A1A.toInt())

            addView(TextView(this@SettingsActivity).apply {
                text = "MUSIC plan"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 18f
                setPadding(0, 0, 0, 12)
            })
            addView(buildInfoLine("版本", "v$versionName ($versionCode)"))
            addView(buildInfoLine("平台", if (isTv) "Android TV" else "Android 手机"))
            addView(buildInfoLine("音乐来源", "网易云音乐 API"))
            addView(buildInfoLine("制作人 QQ", "2997669349"))

            // 联系作者按钮
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 32, 0, 0)
                addView(buildActionButton("联系作者") { openQQ("2997669349") })
                addView(View(this@SettingsActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(16, 0)
                })
                addView(buildActionButton("复制QQ号") {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("QQ", "2997669349"))
                    Toast.makeText(this@SettingsActivity, "已复制 QQ 号", Toast.LENGTH_SHORT).show()
                })
            })
        }
    }

    private fun buildInfoLine(label: String, value: String): TextView = TextView(this).apply {
        text = "$label：$value"
        setTextColor(0xFFAAAAAA.toInt())
        textSize = 13f
        setLineSpacing(8f, 1f)
        setPadding(0, 6, 0, 6)
    }

    private fun buildActionButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFF1ED760.toInt())
        textSize = 14f
        setPadding(32, 16, 32, 16)
        setBackgroundColor(0xFF2A2A2A.toInt())
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /**
     * v1.1.7：听歌识曲悬浮球开关
     * 开 → 检查悬浮窗权限 → 启动 RecognizeFloatingService
     * 关 → 停止服务
     */
    private fun toggleFloatingBall() {
        val intent = Intent(this, RecognizeFloatingService::class.java)
        val isRunning = RecognizeFloatingService::class.java.let {
            val mgr = getSystemService(android.app.ActivityManager::class.java)
            @Suppress("DEPRECATION")
            mgr.getRunningServices(Int.MAX_VALUE).any { s -> s.service.className == it.name }
        }
        if (isRunning) {
            stopService(intent)
            android.widget.Toast.makeText(this, "悬浮球已关闭", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // 检查悬浮窗权限
        if (!RecognizeFloatingService.canDrawOverlays(this)) {
            android.widget.Toast.makeText(this, "请授予悬浮窗权限", android.widget.Toast.LENGTH_LONG).show()
            val permIntent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(permIntent)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        android.widget.Toast.makeText(this, "悬浮球已开启，点击它开始识曲", android.widget.Toast.LENGTH_LONG).show()
    }

    /**
     * 跳转 QQ 添加好友
     */
    private fun openQQ(qq: String) {
        val schemes = listOf(
            "mqqwpa://im/card?card_type=person&uin=$qq&source=qrcode",
            "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$qq&card_type=person&source=qrcode"
        )
        for (scheme in schemes) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(scheme))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    return
                }
            } catch (e: Exception) {
                // 继续尝试下一个
            }
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://qm.qq.com/cgi-bin/qm/qr?k=&uin=$qq"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "未安装 QQ", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 打开文件管理器
     */
    private fun openFileManager() {
        val pkg = "com.example.filemanager"
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Toast.makeText(this, "已打开文件管理器", Toast.LENGTH_SHORT).show()
            } else {
                // 文件管理器未安装，跳转应用市场
                Toast.makeText(this, "未安装文件管理器，请先安装 FileManager APK", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
