package com.example.musicplugin

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 内嵌网易云登录页面
 * 用户在此页面登录网易云，App 自动捕获 MUSIC_U Cookie
 */
class LoginWebActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private var cookieCaptured = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = FrameLayout(this).apply {
            setBackgroundColor(0xFF0F0F0F.toInt())
        }

        // 顶部状态栏
        statusText = TextView(this).apply {
            text = "登录网易云音乐后自动获取 Cookie\n登录成功后会自动返回"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 13f
            setPadding(32, 24, 32, 16)
        }
        layout.addView(statusText)

        // 进度条
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        val progressParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 20
        )
        progressParams.topMargin = 80
        layout.addView(progressBar, progressParams)

        // WebView
        webView = WebView(this).apply {
            val webParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            webParams.topMargin = 100
            layoutParams = webParams
        }
        layout.addView(webView)

        setContentView(layout)

        // 配置 WebView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
        }

        // 启用 Cookie
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                checkCookie()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        webView.loadUrl("https://music.163.com/#/login")
    }

    private fun checkCookie() {
        val cookieStr = CookieManager.getInstance().getCookie("https://music.163.com") ?: return
        val musicU = extractCookieValue(cookieStr, "MUSIC_U")
        if (musicU != null && musicU.isNotEmpty() && !cookieCaptured) {
            cookieCaptured = true
            MusicApi.saveCookie(this, musicU)
            statusText.text = "✓ 登录成功！Cookie 已自动获取\n正在返回..."
            webView.postDelayed({
                setResult(RESULT_OK)
                finish()
            }, 1500)
        }
    }

    private fun extractCookieValue(cookieStr: String, key: String): String? {
        val parts = cookieStr.split(";")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("$key=")) {
                return trimmed.substring("$key=".length)
            }
        }
        return null
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
