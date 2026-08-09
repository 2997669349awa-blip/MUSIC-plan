package com.example.musicplugin

import android.content.Context
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.1.8：Shazam 听歌识曲 API（完全免费，无需注册）
 *
 * 流程：
 * 1. 生成音频指纹（ShazamFingerprint.generateSignature）
 * 2. 自动获取/复用 Bearer Token（缓存 6 小时）
 * 3. POST 到 amp.shazam.com/match/v2/... 匹配歌曲
 * 4. 解析返回的 track 信息（歌名/歌手）
 *
 * Token 获取（2026-05 更新）：
 * - 从 GitHub ST-Handlers 仓库获取 apple_action_signature + x_request_timestamp
 * - 用 GET 请求 Apple Token Service 获取 Bearer Token
 */
object ShazamApi {
    private const val TAG = "ShazamApi"
    private const val KEY_JSON_URL = "https://raw.githubusercontent.com/sheikhtamimlover/ST-Handlers/refs/heads/main/key.json"
    private const val TOKEN_SERVICE_URL = "https://sf-api-token-service.itunes.apple.com/apiToken"
    private const val MATCH_BASE_URL = "https://amp.shazam.com/match/v2/en-US/US/iphone"
    private const val TOKEN_PREF_KEY = "shazam_bearer_token"
    private const val TOKEN_EXPIRE_KEY = "shazam_token_expire"
    private const val TOKEN_CACHE_MS = 6 * 60 * 60 * 1000L // 6 小时

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class RecognizeResult(
        val success: Boolean,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val errorMsg: String? = null
    )

    private const val OVERALL_TIMEOUT_SECONDS = 15L

    fun recognize(context: Context, audioFile: File, callback: (RecognizeResult) -> Unit) {
        Thread {
            val called = AtomicBoolean(false)
            val latch = CountDownLatch(1)

            fun deliver(result: RecognizeResult) {
                if (called.compareAndSet(false, true)) {
                    callback(result)
                    latch.countDown()
                }
            }

            // 超时守护：15s 后强制返回失败
            Thread {
                try {
                    if (!latch.await(OVERALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        Log.w(TAG, "识别超时（${OVERALL_TIMEOUT_SECONDS}s）")
                        deliver(RecognizeResult(false, errorMsg = "识别超时，请重试"))
                    }
                } catch (_: InterruptedException) {}
            }.start()

            try {
                // 1. 生成指纹
                val signatureUri = ShazamFingerprint.generateSignature(audioFile)
                if (signatureUri == null) {
                    deliver(RecognizeResult(false, errorMsg = "指纹生成失败"))
                    return@Thread
                }
                val peakCount = ShazamFingerprint.getLastPeakCount()
                Log.i(TAG, "指纹 URI 长度: ${signatureUri.length}, 峰值数: $peakCount")

                // 2. 获取 Bearer token（带缓存）
                val token = getBearerToken(context)
                if (token == null) {
                    deliver(RecognizeResult(false, errorMsg = "获取认证失败，请检查网络"))
                    return@Thread
                }

                // 3. 发送匹配请求
                val deviceId = UUID.randomUUID().toString().uppercase()
                val sessionId = UUID.randomUUID().toString().uppercase()
                val urlBuilder = "$MATCH_BASE_URL/$deviceId/$sessionId".toHttpUrl().newBuilder()
                    .addQueryParameter("recognitionType", "progressive-with-rolling")
                    .addQueryParameter("sampling", "true")
                    .addQueryParameter("matchv2t", "true")
                    .addQueryParameter("hidelb", "true")
                    .addQueryParameter("video", "v3")

                val jsonBody = JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("timezone", "Asia/Shanghai")
                    put("signatures", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("uri", signatureUri)
                            put("audioSource", "MIC")
                        })
                    })
                }

                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .header("Accept", "*/*")
                    .header("X-Shazam-Platform", "IPHONE")
                    .header("X-Shazam-Appversion", "26.0.0")
                    .header("X-Shazam-Auth-Retry", "0")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("User-Agent", "Shazam/5817 CFNetwork/3860.200.71 Darwin/25.1.0")
                    .build()

                Log.i(TAG, "发送匹配请求...")
                val resp = client.newCall(request).execute()
                val body = resp.body?.string() ?: run {
                    deliver(RecognizeResult(false, errorMsg = "无响应"))
                    return@Thread
                }

                Log.i(TAG, "Shazam 响应码: ${resp.code}")
                Log.i(TAG, "Shazam 响应: ${body.take(800)}")

                if (resp.code == 401) {
                    clearTokenCache(context)
                    deliver(RecognizeResult(false, errorMsg = "认证过期，请重试"))
                    return@Thread
                }

                if (!resp.isSuccessful) {
                    deliver(RecognizeResult(false, errorMsg = "服务错误 (${resp.code})"))
                    return@Thread
                }

                val json = JSONObject(body)

                val topKeys = json.keys().asSequence().joinToString(", ")
                Log.i(TAG, "响应顶层keys: $topKeys")

                val results = json.optJSONObject("results") ?: run {
                    deliver(RecognizeResult(false, errorMsg = "未识别到歌曲(无results)\n峰值:$peakCount 响应:${body.take(200)}"))
                    return@Thread
                }
                val matches = results.optJSONArray("matches")
                if (matches == null || matches.length() == 0) {
                    deliver(RecognizeResult(false, errorMsg = "未识别到歌曲(无matches)\n峰值:$peakCount 响应:${body.take(200)}"))
                    return@Thread
                }

                val matchId = matches.optJSONObject(0)?.optString("id", "") ?: ""
                if (matchId.isEmpty()) {
                    deliver(RecognizeResult(false, errorMsg = "未识别到歌曲(无matchId)\n峰值:$peakCount 响应:${body.take(200)}"))
                    return@Thread
                }

                val resources = json.optJSONObject("resources")
                val shazamSongs = resources?.optJSONObject("shazam-songs")
                val song = shazamSongs?.optJSONObject(matchId)

                if (song == null) {
                    deliver(RecognizeResult(false, errorMsg = "未识别到歌曲(无song)\n峰值:$peakCount matchId:$matchId 响应:${body.take(200)}"))
                    return@Thread
                }

                val attrs = song.optJSONObject("attributes")
                val title = song.optString("title", "")
                    .ifEmpty { attrs?.optString("name", "") ?: "" }
                    .ifEmpty { attrs?.optString("title", "") ?: "" }

                val artist = song.optString("artist", "")
                    .ifEmpty { attrs?.optString("subtitle", "") ?: "" }
                    .ifEmpty { attrs?.optString("artist", "") ?: "" }

                if (title.isEmpty()) {
                    deliver(RecognizeResult(false, errorMsg = "未识别到歌曲(无title)\n峰值:$peakCount 响应:${body.take(500)}"))
                    return@Thread
                }

                deliver(RecognizeResult(true, title, artist))
            } catch (e: Exception) {
                Log.e(TAG, "识别异常: ${e.message}")
                deliver(RecognizeResult(false, errorMsg = "识别异常: ${e.message}"))
            }
        }.start()
    }

    private fun getBearerToken(context: Context): String? {
        val prefs = context.getSharedPreferences("music_plugin_prefs", Context.MODE_PRIVATE)
        val cachedToken = prefs.getString(TOKEN_PREF_KEY, "") ?: ""
        val expireTime = prefs.getLong(TOKEN_EXPIRE_KEY, 0)

        if (cachedToken.isNotBlank() && System.currentTimeMillis() < expireTime) {
            Log.d(TAG, "使用缓存的 token")
            return cachedToken
        }

        val newToken = fetchBearerToken()
        if (newToken != null) {
            prefs.edit()
                .putString(TOKEN_PREF_KEY, newToken)
                .putLong(TOKEN_EXPIRE_KEY, System.currentTimeMillis() + TOKEN_CACHE_MS)
                .apply()
        }
        return newToken
    }

    private fun clearTokenCache(context: Context) {
        context.getSharedPreferences("music_plugin_prefs", Context.MODE_PRIVATE)
            .edit()
            .remove(TOKEN_PREF_KEY)
            .remove(TOKEN_EXPIRE_KEY)
            .apply()
    }

    /**
     * 获取 Bearer Token（2026-05 新方案）
     * 1. 从 GitHub ST-Handlers 仓库获取 apple_action_signature + x_request_timestamp
     * 2. 用 GET 请求 Apple Token Service 获取 Bearer Token
     */
    private fun fetchBearerToken(): String? {
        var appleSig: String? = null
        var timestamp: String? = null

        // Step 1: 从 GitHub 获取签名和时间戳
        try {
            val ghRequest = Request.Builder()
                .url(KEY_JSON_URL)
                .get()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                .build()
            val ghResp = client.newCall(ghRequest).execute()
            val ghBody = ghResp.body?.string()
            if (ghBody != null) {
                val json = JSONObject(ghBody)
                appleSig = json.optString("apple_action_signature", null)
                timestamp = json.optString("x_request_timestamp", null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "GitHub key.json fetch failed: ${e.message}")
        }

        if (appleSig.isNullOrBlank() || timestamp.isNullOrBlank()) {
            Log.e(TAG, "无法获取 apple_action_signature / x_request_timestamp")
            return null
        }

        Log.d(TAG, "获取到签名: ${appleSig.take(30)}..., 时间戳: $timestamp")

        // Step 2: 用签名请求 Apple Token Service
        return try {
            val urlBuilder = TOKEN_SERVICE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("clientClass", "apple")
                .addQueryParameter("clientId", "com.shazam.android")
                .addQueryParameter("inid", "AC3B3EB2-E6A6-4BF6-AA47-14C54F1E79C8")

            val request = Request.Builder()
                .url(urlBuilder.build())
                .get()
                .header("User-Agent", "Shazam/16.39.0 Android/12 model/Tcl5033D build/1603900 AMS/1")
                .header("x-apple-actionsignature", appleSig)
                .header("x-request-timestamp", timestamp)
                .header("x-apple-tz", "21600")
                .header("x-apple-store-front", "143441-1,31")
                .header("x-apple-client-application", "com.shazam.android")
                .header("Accept-Encoding", "gzip")
                .build()

            val resp = client.newCall(request).execute()
            val respBody = resp.body?.string() ?: return null
            Log.d(TAG, "Token service 响应: ${respBody.take(100)}")
            val json = JSONObject(respBody)
            json.optString("token", null)
        } catch (e: Exception) {
            Log.e(TAG, "Token service failed: ${e.message}")
            null
        }
    }
}
