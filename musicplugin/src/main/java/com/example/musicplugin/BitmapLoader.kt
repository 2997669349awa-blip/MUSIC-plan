package com.example.musicplugin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors

/**
 * 简易图片加载器 v1.0.7
 *
 * - 内存缓存（LruCache）
 * - 后台线程下载
 * - 主线程回调
 *
 * 用于专辑封面、通知栏大图等。
 */
object BitmapLoader {

    private val executor = Executors.newFixedThreadPool(3)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // 最大占用 1/8 可用内存
    private val cacheSize = (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtMost(16 * 1024 * 1024)
    private val cache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(url: String, callback: (Bitmap?) -> Unit) {
        cache.get(url)?.let { callback(it); return }
        executor.execute {
            val bmp = download(url)
            if (bmp != null) cache.put(url, bmp)
            callback(bmp)
        }
    }

    private fun download(url: String): Bitmap? {
        return try {
            // v1.0.9：网易云封面 CDN (p*.music.126.net) 不需要 Referer，带了反而可能被拒
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                android.util.Log.w("BitmapLoader", "下载失败 code=${resp.code} url=$url")
                return null
            }
            val bytes = resp.body?.bytes() ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            android.util.Log.w("BitmapLoader", "下载异常: ${e.message} url=$url")
            null
        }
    }
}
