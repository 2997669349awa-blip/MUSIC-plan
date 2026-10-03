package com.example.musicplugin

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * v1.2.7：HTML 介绍页
 * 设置 → 功能 → HTML 介绍页 → 进入此 Activity
 * 用 WebView 加载 assets/intro.html（Cyber Soda 介绍页）
 * 外链（如 gofile.io 下载链接）由系统浏览器打开
 */
class HtmlIntroActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式状态栏，背景与 HTML 一致
        window.statusBarColor = Color.parseColor("#080808")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#080808"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ====== 顶部栏：返回 + 标题 ======
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xE6000000.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, statusBarHeight(), 8, 16)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
        }
        val btnBack = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setColorFilter(Color.WHITE)
            background = null
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(48.dp(), 48.dp())
        }
        val tvTitle = TextView(this).apply {
            text = "HTML 介绍页"
            setTextColor(Color.WHITE)
            textSize = 17f
            setSingleLine(true)
            setPadding(16, 0, 16, 0)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        topBar.addView(btnBack)
        topBar.addView(tvTitle)
        root.addView(topBar)

        // ====== WebView ======
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = statusBarHeight() + 64
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            // 背景透明，让加载前的底色与 HTML 一致
            setBackgroundColor(Color.parseColor("#080808"))
            webViewClient = IntroWebViewClient()
            webChromeClient = WebChromeClient()
            // 加载本地介绍页
            loadUrl("file:///android_asset/intro.html")
        }
        root.addView(webView)

        setContentView(root)
    }

    /**
     * 外链（http/https）交给系统浏览器打开，本地 file 链接在 WebView 内加载
     */
    private inner class IntroWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?, request: WebResourceRequest?
        ): Boolean {
            val url = request?.url ?: return false
            if (url.scheme == "http" || url.scheme == "https") {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, url)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    } else {
                        // 没有浏览器时回退到 WebView 内打开
                        view?.loadUrl(url.toString())
                    }
                } catch (_: Exception) {
                    view?.loadUrl(url.toString())
                }
                return true
            }
            return false
        }
    }

    private fun statusBarHeight(): Int {
        val res = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (res > 0) resources.getDimensionPixelSize(res) else 0
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
