package com.example.musicplugin

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * 在线音乐 API（多源：网易云 / 酷狗 / 汽水 / QQ）
 *
 * v1.1.1：支持在设置里切换音乐源
 * - 播放和搜索默认走网易云（需 MUSIC_U Cookie）
 * - 封面按当前选择的源取，对得上才展示
 * - 各源无公开 API 时退回其他源兜底
 *
 * 获取 MUSIC_U Cookie：浏览器登录 music.163.com → F12 → Application → Cookies → 复制 MUSIC_U 值
 */
object MusicApi {
    private const val TAG = "MusicApi"
    private const val BASE = "https://music.163.com/api"

    /**
     * v1.1.1：音乐源枚举
     * - NETEASE：网易云音乐（默认，播放+搜索+歌词）
     * - KUGOU：酷狗音乐（封面最全，术曲/V家曲都有）
     * - KUWO：酷我音乐（v1.1.3 新增，免费播放URL无需登录，公开antiserver接口）
     * - QISHUI：汽水音乐（抖音，无公开 API，封面退回酷狗/QQ兜底）
     * - QQ：QQ 音乐（封面兜底）
     */
    enum class MusicSource(val displayName: String) {
        NETEASE("网易云音乐"),
        KUGOU("酷狗音乐"),
        KUWO("酷我音乐"),
        QISHUI("汽水音乐"),
        QQ("QQ音乐")
    }

    private var musicCookie: String = ""
    private var kugouCookie: String = ""
    // v1.1.1：当前音乐源（用于封面获取），默认网易云
    private var currentSource: MusicSource = MusicSource.NETEASE

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * v1.1.1：设置当前音乐源
     */
    fun setSource(source: MusicSource) {
        currentSource = source
        Log.i(TAG, "音乐源切换为: ${source.displayName}")
    }

    /**
     * v1.1.1：获取当前音乐源
     */
    fun getSource(): MusicSource = currentSource

    data class OnlineSong(
        val id: Long,
        val name: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val songUrl: String?,
        val mvId: Long?,
        val picUrl: String?,
        // v1.1.2：多源支持
        val source: MusicSource = MusicSource.NETEASE,
        val hash: String? = null  // 酷狗用
    )

    /**
     * v1.1.4：在线 PV/MV 数据（跨源搜索结果）
     * - 网易云：id 是 mvid，调 getMvUrl 取播放URL
     * - 酷狗：mvHash 是 mvhash，调 getKugouMvUrl 取播放URL
     * - 酷我：id 是 mvid，调 getKuwoMvUrl 取播放URL
     */
    data class OnlineMv(
        val id: Long,
        val name: String,
        val artist: String,
        val cover: String?,
        val duration: Long,
        val source: MusicSource,
        val mvHash: String? = null
    )

    /**
     * 初始化：从 SharedPreferences 读取 Cookie 和音乐源
     */
    fun init(context: Context) {
        val sp = context.getSharedPreferences("music_plugin", Context.MODE_PRIVATE)
        musicCookie = sp.getString("music_cookie", "") ?: ""
        kugouCookie = sp.getString("kugou_cookie", "") ?: ""
        // v1.1.1：读取音乐源
        val sourceName = sp.getString("music_source", MusicSource.NETEASE.name) ?: MusicSource.NETEASE.name
        currentSource = runCatching { MusicSource.valueOf(sourceName) }.getOrDefault(MusicSource.NETEASE)
    }

    /**
     * v1.1.1：保存音乐源到 SharedPreferences
     */
    fun saveSource(context: Context, source: MusicSource) {
        currentSource = source
        context.getSharedPreferences("music_plugin", Context.MODE_PRIVATE)
            .edit().putString("music_source", source.name).apply()
    }

    /**
     * 保存 Cookie
     */
    fun saveCookie(context: Context, cookie: String) {
        musicCookie = cookie.trim()
        context.getSharedPreferences("music_plugin", Context.MODE_PRIVATE)
            .edit().putString("music_cookie", musicCookie).apply()
    }

    /**
     * 获取 Cookie
     */
    fun getCookie(): String = musicCookie

    /**
     * 是否已配置 Cookie
     */
    fun hasCookie(): Boolean = musicCookie.isNotEmpty()

    /**
     * v1.1.9：保存酷狗 Cookie
     */
    fun saveKugouCookie(context: Context, cookie: String) {
        kugouCookie = cookie.trim()
        context.getSharedPreferences("music_plugin", Context.MODE_PRIVATE)
            .edit().putString("kugou_cookie", kugouCookie).apply()
    }

    /**
     * v1.1.9：获取酷狗 Cookie
     */
    fun getKugouCookie(): String = kugouCookie

    /**
     * v1.1.9：是否已配置酷狗 Cookie
     */
    fun hasKugouCookie(): Boolean = kugouCookie.isNotEmpty()

    /**
     * 搜索歌曲（v1.1.2：按当前音乐源分发）
     */
    fun search(keyword: String, callback: (List<OnlineSong>?, String?) -> Unit) {
        when (currentSource) {
            MusicSource.NETEASE -> searchNetease(keyword, callback)
            MusicSource.KUGOU -> searchKugou(keyword, callback)
            MusicSource.KUWO -> searchKuwo(keyword, callback)
            MusicSource.QQ -> searchQQ(keyword, callback)
            MusicSource.QISHUI -> searchKugou(keyword, callback) // 汽水无API，用酷狗搜索
        }
    }

    /**
     * 网易云搜索
     */
    private fun searchNetease(keyword: String, callback: (List<OnlineSong>?, String?) -> Unit) {
        thread {
            try {
                val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
                val url = "$BASE/search/get?s=$encoded&type=1&limit=30&offset=0"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                    .header("Referer", "https://music.163.com")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: run {
                    callback(null, "搜索失败：无响应")
                    return@thread
                }
                val json = JSONObject(body)
                if (json.optInt("code", -1) != 200) {
                    callback(null, "搜索失败：${json.optString("msg", "服务器错误")}")
                    return@thread
                }
                val songs = json.optJSONObject("result")?.optJSONArray("songs")
                if (songs == null || songs.length() == 0) {
                    callback(emptyList(), null)
                    return@thread
                }
                val list = mutableListOf<OnlineSong>()
                for (i in 0 until songs.length()) {
                    val s = songs.getJSONObject(i)
                    val artists = s.optJSONArray("artists")
                    val artistName = if (artists != null && artists.length() > 0)
                        artists.getJSONObject(0).optString("name", "<未知>") else "<未知>"
                    val albumName = s.optJSONObject("album")?.optString("name", "") ?: ""
                    val picUrl = s.optJSONObject("album")?.optString("picUrl", null)
                    val mvId = if (s.optInt("mvid", 0) > 0) s.optLong("mvid") else null
                    list.add(OnlineSong(
                        id = s.optLong("id"),
                        name = s.optString("name", "未知歌曲"),
                        artist = artistName,
                        album = albumName,
                        duration = s.optLong("duration", 0),
                        songUrl = null,
                        mvId = mvId,
                        picUrl = picUrl,
                        source = MusicSource.NETEASE
                    ))
                }
                callback(list, null)
            } catch (e: Exception) {
                Log.e(TAG, "搜索失败: ${e.message}")
                callback(null, "搜索失败: ${e.message}")
            }
        }
    }

    /**
     * v1.1.2：酷狗搜索
     * mobilecdn.kugou.com/api/v3/search/song
     * 返回 hash（用于播放）+ album_id（用于封面）
     */
    private fun searchKugou(keyword: String, callback: (List<OnlineSong>?, String?) -> Unit) {
        thread {
            try {
                val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
                val url = "http://mobilecdn.kugou.com/api/v3/search/song?keyword=$encoded&page=1&pagesize=30&format=json"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: run {
                    callback(null, "搜索失败：无响应")
                    return@thread
                }
                val json = JSONObject(body)
                if (json.optInt("status", -1) != 1) {
                    callback(null, "搜索失败：${json.optString("error", "服务器错误")}")
                    return@thread
                }
                val info = json.optJSONObject("data")?.optJSONArray("info")
                if (info == null || info.length() == 0) {
                    callback(emptyList(), null)
                    return@thread
                }
                val list = mutableListOf<OnlineSong>()
                for (i in 0 until info.length()) {
                    val s = info.getJSONObject(i)
                    list.add(OnlineSong(
                        id = s.optLong("album_id", 0),
                        name = s.optString("songname", "未知歌曲"),
                        artist = s.optString("singername", "<未知>"),
                        album = s.optString("album_name", ""),
                        duration = s.optLong("duration", 0) * 1000,
                        songUrl = null,
                        mvId = if (s.optString("mvhash", "").isNotEmpty()) 1L else null,
                        picUrl = null,
                        source = MusicSource.KUGOU,
                        hash = s.optString("hash", "")
                    ))
                }
                callback(list, null)
            } catch (e: Exception) {
                Log.e(TAG, "酷狗搜索失败: ${e.message}")
                callback(null, "搜索失败: ${e.message}")
            }
        }
    }

    /**
     * v1.1.2：QQ 音乐搜索
     * c.y.qq.com/soso/fcgi-bin/client_search_cp
     * 返回 songmid（用于播放）+ albummid（用于封面）
     */
    private fun searchQQ(keyword: String, callback: (List<OnlineSong>?, String?) -> Unit) {
        thread {
            try {
                val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
                val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$encoded&format=json&n=30&p=1&cr=1&g_tk=5381"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://y.qq.com/")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: run {
                    callback(null, "搜索失败：无响应")
                    return@thread
                }
                val json = JSONObject(body)
                val list = json.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
                if (list == null || list.length() == 0) {
                    callback(emptyList(), null)
                    return@thread
                }
                val result = mutableListOf<OnlineSong>()
                for (i in 0 until list.length()) {
                    val s = list.getJSONObject(i)
                    val singers = s.optJSONArray("singer")
                    val artistName = if (singers != null && singers.length() > 0)
                        singers.getJSONObject(0).optString("name", "<未知>") else "<未知>"
                    val albumMid = s.optString("albummid", "")
                    val picUrl = if (albumMid.isNotEmpty())
                        "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg" else null
                    result.add(OnlineSong(
                        id = s.optLong("songid", 0),
                        name = s.optString("songname", "未知歌曲"),
                        artist = artistName,
                        album = s.optString("albumname", ""),
                        duration = s.optLong("interval", 0) * 1000,
                        songUrl = null,
                        mvId = if (s.optString("vid", "").isNotEmpty()) 1L else null,
                        picUrl = picUrl,
                        source = MusicSource.QQ,
                        hash = s.optString("songmid", "")  // QQ 用 songmid 存 hash 字段
                    ))
                }
                callback(result, null)
            } catch (e: Exception) {
                Log.e(TAG, "QQ搜索失败: ${e.message}")
                callback(null, "搜索失败: ${e.message}")
            }
        }
    }

    /**
     * v1.1.3：酷我音乐搜索
     * search.kuwo.cn/r.s 旧版接口，免费无需登录
     * 返回 rid（DC_TARGETID，用于播放）+ albumpic（搜索接口本身就有封面）
     *
     * 播放URL用 antiserver.kuwo.cn/anti.s 接口，免费返回 MP3 直链
     */
    private fun searchKuwo(keyword: String, callback: (List<OnlineSong>?, String?) -> Unit) {
        thread {
            try {
                val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
                val url = "http://search.kuwo.cn/r.s?all=$encoded&ft=music&itemset=web_2013&client=kt&pn=0&rn=30&rformat=json&encoding=utf8"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val response = client.newCall(request).execute()
                val raw = response.body?.string() ?: run {
                    callback(null, "搜索失败：无响应")
                    return@thread
                }
                // 酷我旧接口返回单引号 JSON，转成标准双引号
                val jsonStr = Regex("'(\\w+)':").replace(raw, "\"$1\":")
                val json = JSONObject(jsonStr)
                val abslist = json.optJSONArray("abslist")
                if (abslist == null || abslist.length() == 0) {
                    callback(emptyList(), null)
                    return@thread
                }
                val list = mutableListOf<OnlineSong>()
                for (i in 0 until abslist.length()) {
                    val s = abslist.getJSONObject(i)
                    val rid = s.optString("DC_TARGETID", "")
                    val name = s.optString("SONGNAME", "未知歌曲").replace("&amp;", "&")
                    val artist = s.optString("ARTIST", "<未知>").replace("&amp;", "&")
                    val album = s.optString("ALBUM", "").replace("&amp;", "&")
                    // 酷我封面：搜索接口 albumpic 字段可能为空，用拼接兜底
                    val picUrl = s.optString("albumpic", "").ifEmpty {
                        s.optString("web_albumpic_short", "").ifEmpty { null }
                    }
                    list.add(OnlineSong(
                        id = rid.toLongOrNull() ?: 0L,
                        name = name,
                        artist = artist,
                        album = album,
                        duration = s.optString("DURATION", "0").toLongOrNull() ?: 0L,
                        songUrl = null,
                        mvId = null,
                        picUrl = picUrl,
                        source = MusicSource.KUWO,
                        hash = rid  // 酷我用 hash 字段存 rid
                    ))
                }
                callback(list, null)
            } catch (e: Exception) {
                Log.e(TAG, "酷我搜索失败: ${e.message}")
                callback(null, "搜索失败: ${e.message}")
            }
        }
    }

    /**
     * 获取歌曲播放 URL（v1.1.2：按歌曲来源分发）
     * - 网易云：走 getSongUrl(songId)
     * - 酷狗：playInfo 接口取 url，拿不到退回网易云按名搜
     * - QQ：无公开播放 API，直接退回网易云按名搜
     * 退回网易云时用歌名+歌手搜出 id 再取 url，保证能播放
     */
    fun getSongUrlBySong(song: OnlineSong, callback: (String?) -> Unit) {
        when (song.source) {
            MusicSource.NETEASE -> getSongUrl(song.id, callback)
            MusicSource.KUGOU -> getKugouPlayUrl(song, callback)
            MusicSource.KUWO -> getKuwoPlayUrl(song, callback)
            MusicSource.QQ -> fallbackToNetease(song.name, song.artist, callback)
            MusicSource.QISHUI -> fallbackToNetease(song.name, song.artist, callback)
        }
    }

    /**
     * v1.1.3：酷我播放 URL
     * antiserver.kuwo.cn/anti.s 公开接口，免费无需登录，返回 MP3 直链
     * 实测：晴天 周杰伦 rid=493526477 → 返回真实 mp3 (2.6MB)
     */
    private fun getKuwoPlayUrl(song: OnlineSong, callback: (String?) -> Unit) {
        thread {
            try {
                val rid = song.hash
                if (rid.isNullOrEmpty()) {
                    fallbackToNetease(song.name, song.artist, callback)
                    return@thread
                }
                // 优先 128k MP3（兼容性最好），失败尝试 192k
                val url = "http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=$rid&br=128kmp3"
                val resp = client.newCall(
                    Request.Builder().url(url)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                ).execute()
                val playUrl = resp.body?.string()?.trim() ?: ""
                if (playUrl.startsWith("http") && !playUrl.contains("failed", ignoreCase = true)) {
                    callback(playUrl)
                } else {
                    // 128k 失败，尝试默认音质
                    val url2 = "http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=$rid"
                    val resp2 = client.newCall(
                        Request.Builder().url(url2)
                            .header("User-Agent", "Mozilla/5.0")
                            .build()
                    ).execute()
                    val playUrl2 = resp2.body?.string()?.trim() ?: ""
                    if (playUrl2.startsWith("http") && !playUrl2.contains("failed", ignoreCase = true)) {
                        callback(playUrl2)
                    } else {
                        // 酷我拿不到，退回网易云
                        fallbackToNetease(song.name, song.artist, callback)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "酷我播放URL失败: ${e.message}")
                fallbackToNetease(song.name, song.artist, callback)
            }
        }
    }

    /**
     * v1.1.2：酷狗播放 URL
     * 公开 playInfo 接口对 VIP 歌曲返回空 url，拿不到则退回网易云
     * v1.1.9：有酷狗 Cookie 时带上，可获取 VIP 歌曲播放 URL
     */
    private fun getKugouPlayUrl(song: OnlineSong, callback: (String?) -> Unit) {
        thread {
            try {
                val hash = song.hash
                if (hash.isNullOrEmpty()) {
                    fallbackToNetease(song.name, song.artist, callback)
                    return@thread
                }
                val url = "http://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=$hash"
                val requestBuilder = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0")
                // v1.1.9：有酷狗 Cookie 时带上
                if (kugouCookie.isNotEmpty()) {
                    requestBuilder.header("Cookie", kugouCookie)
                }
                val resp = client.newCall(requestBuilder.build()).execute()
                val body = resp.body?.string() ?: run {
                    fallbackToNetease(song.name, song.artist, callback)
                    return@thread
                }
                val json = JSONObject(body)
                val playUrl = json.optString("url", "")
                if (playUrl.isNotEmpty() && playUrl.startsWith("http")) {
                    callback(playUrl)
                } else {
                    // 酷狗拿不到（VIP），退回网易云
                    fallbackToNetease(song.name, song.artist, callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "酷狗播放URL失败: ${e.message}")
                fallbackToNetease(song.name, song.artist, callback)
            }
        }
    }

    /**
     * v1.1.2：退回网易云按歌名搜索取播放 URL
     * 用于酷狗/QQ 源拿不到播放 URL 的情况
     */
    private fun fallbackToNetease(name: String, artist: String, callback: (String?) -> Unit) {
        thread {
            try {
                val keyword = if (artist.isBlank() || artist == "<未知>") name else "$name $artist"
                val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
                val url = "$BASE/search/get?s=$encoded&type=1&limit=1"
                val resp = client.newCall(
                    Request.Builder().url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                        .header("Referer", "https://music.163.com")
                        .build()
                ).execute()
                val body = resp.body?.string() ?: run {
                    callback(null); return@thread
                }
                val json = JSONObject(body)
                val songs = json.optJSONObject("result")?.optJSONArray("songs")
                if (songs == null || songs.length() == 0) {
                    callback(null); return@thread
                }
                val neteaseId = songs.getJSONObject(0).optLong("id")
                getSongUrl(neteaseId, callback)
            } catch (e: Exception) {
                Log.e(TAG, "退回网易云失败: ${e.message}")
                callback(null)
            }
        }
    }

    /**
     * 获取歌曲播放 URL（网易云）
     * - 免费(fee=0)歌曲不需要 Cookie
     * - VIP 歌曲需要 MUSIC_U Cookie
     * 先无 Cookie 尝试，失败再用 Cookie
     */
    fun getSongUrl(songId: Long, callback: (String?) -> Unit) {
        thread {
            try {
                // v1 接口，POST 请求
                val url = "$BASE/song/enhance/player/url/v1"
                val formBody = okhttp3.FormBody.Builder()
                    .add("ids", "[$songId]")
                    .add("level", "standard")
                    .add("encodeType", "flac")
                    .build()

                // 先无 Cookie 尝试（免费歌曲可直接获取）
                val requestNoCookie = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                    .header("Referer", "https://music.163.com")
                    .post(formBody)
                    .build()
                val response = client.newCall(requestNoCookie).execute()
                val body = response.body?.string()
                if (body != null) {
                    val json = JSONObject(body)
                    if (json.optInt("code", -1) == 200) {
                        val data = json.optJSONArray("data")
                        if (data != null && data.length() > 0) {
                            val d = data.getJSONObject(0)
                            val playUrl = d.optString("url", null)
                            if (!playUrl.isNullOrEmpty()) {
                                callback(playUrl)
                                return@thread
                            }
                        }
                    }
                }

                // 无 Cookie 失败，用 Cookie 重试（VIP 歌曲）
                if (musicCookie.isEmpty()) {
                    Log.w(TAG, "免费获取失败且无Cookie")
                    callback(null)
                    return@thread
                }
                val formBody2 = okhttp3.FormBody.Builder()
                    .add("ids", "[$songId]")
                    .add("level", "standard")
                    .add("encodeType", "flac")
                    .build()
                val requestWithCookie = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                    .header("Referer", "https://music.163.com")
                    .header("Cookie", "MUSIC_U=$musicCookie")
                    .post(formBody2)
                    .build()
                val response2 = client.newCall(requestWithCookie).execute()
                val body2 = response2.body?.string() ?: run {
                    callback(null)
                    return@thread
                }
                val json2 = JSONObject(body2)
                if (json2.optInt("code", -1) != 200) {
                    Log.w(TAG, "Cookie获取URL失败 code=${json2.optInt("code")}")
                    callback(null)
                    return@thread
                }
                val data2 = json2.optJSONArray("data")
                if (data2 == null || data2.length() == 0) {
                    callback(null)
                    return@thread
                }
                val d2 = data2.getJSONObject(0)
                val playUrl2 = d2.optString("url", null)
                if (!playUrl2.isNullOrEmpty()) {
                    callback(playUrl2)
                } else {
                    Log.w(TAG, "Cookie获取仍无URL fee=${d2.optInt("fee")}")
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取URL失败: ${e.message}")
                callback(null)
            }
        }
    }

    /**
     * 获取歌词（不需要 Cookie）
     * v1.2.7：tv=-1 → tv=1，确保翻译歌词（tlyric）也能取到
     */
    fun getLyrics(songId: Long, callback: (String?, String?) -> Unit) {
        thread {
            try {
                val url = "$BASE/song/lyric?id=$songId&lv=1&kv=1&tv=1"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                    .header("Referer", "https://music.163.com")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: run {
                    callback(null, null)
                    return@thread
                }
                val json = JSONObject(body)
                val lrc = json.optJSONObject("lrc")?.optString("lyric", null)
                val tLrc = json.optJSONObject("tlyric")?.optString("lyric", null)
                callback(lrc, tLrc)
            } catch (e: Exception) {
                Log.e(TAG, "获取歌词失败: ${e.message}")
                callback(null, null)
            }
        }
    }

    /**
     * 通过歌曲名搜索歌词（本地歌曲用）
     * v1.2.7：重写匹配逻辑
     * - 修复 artists 字段解析（之前 optString 拿到的是空串，永远匹配不上歌手）
     * - 清洗歌名（去掉 (Live)/-remix/(伴奏) 等后缀）再做匹配
     * - 多候选逐个尝试取歌词，第一个非空就返回（之前只取一首，无歌词就放弃）
     */
    fun getLyricsByTitle(title: String, artist: String, callback: (String?, String?) -> Unit) {
        thread {
            try {
                val cleanTitle = cleanSongTitle(title)
                val keyword = if (artist != "<未知艺术家>" && artist.isNotEmpty()) "$cleanTitle $artist" else cleanTitle
                val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
                val url = "$BASE/search/get?s=$encoded&type=1&limit=15"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                    .header("Referer", "https://music.163.com")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: run {
                    callback(null, null)
                    return@thread
                }
                val json = JSONObject(body)
                val songs = json.optJSONObject("result")?.optJSONArray("songs")
                if (songs == null || songs.length() == 0) {
                    // v1.2.7：用纯歌名（去掉歌手）再搜一次兜底
                    if (keyword != cleanTitle) {
                        searchLyricByKeyword(cleanTitle, callback)
                    } else {
                        callback(null, null)
                    }
                    return@thread
                }

                // v1.2.7：对候选歌按匹配度打分排序，再逐个尝试取歌词
                data class Candidate(val idx: Int, val score: Int)
                val candidates = mutableListOf<Candidate>()
                for (i in 0 until songs.length()) {
                    val s = songs.getJSONObject(i)
                    val n = s.optString("name", "")
                    val ar = parseArtists(s)
                    val score = matchScore(cleanTitle, title, n, artist, ar)
                    candidates.add(Candidate(i, score))
                }
                // 按分数降序，分数 > 0 的优先；分数为 0 的（完全不匹配）也保留作最后兜底
                candidates.sortByDescending { it.score }

                // v1.2.8：逐个尝试，第一个能拿到实际歌词的就返回
                // 修复：之前只检查 isNullOrBlank，但有些歌曲返回的 LRC 只有 [by:xxx] [ti:xxx] 元数据
                // LyricParser 解析后得到 0 行，导致歌词空白
                val timeTagRegex = Regex("""\[\d{2}:\d{2}\.\d{2,3}]""")
                fun tryNext(startIdx: Int) {
                    if (startIdx >= candidates.size) {
                        callback(null, null)
                        return
                    }
                    val songId = songs.getJSONObject(candidates[startIdx].idx).optLong("id")
                    getLyrics(songId) { lrc, tlrc ->
                        if (!lrc.isNullOrBlank() && timeTagRegex.containsMatchIn(lrc)) {
                            callback(lrc, tlrc)
                        } else {
                            // 这首没实际歌词，试下一首
                            tryNext(startIdx + 1)
                        }
                    }
                }
                tryNext(0)
            } catch (e: Exception) {
                Log.e(TAG, "通过标题获取歌词失败: ${e.message}")
                callback(null, null)
            }
        }
    }

    /**
     * v1.2.7：纯关键词搜歌词兜底（getLyricsByTitle 子流程）
     */
    private fun searchLyricByKeyword(keyword: String, callback: (String?, String?) -> Unit) {
        try {
            val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
            val url = "$BASE/search/get?s=$encoded&type=1&limit=10"
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                .header("Referer", "https://music.163.com")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: run {
                callback(null, null); return
            }
            val json = JSONObject(body)
            val songs = json.optJSONObject("result")?.optJSONArray("songs")
            if (songs == null || songs.length() == 0) {
                callback(null, null); return
            }
            // v1.2.8：逐个尝试，跳过只有元数据的假歌词
            val timeTagRegex = Regex("""\[\d{2}:\d{2}\.\d{2,3}]""")
            fun tryNext(idx: Int) {
                if (idx >= songs.length()) {
                    callback(null, null); return
                }
                val songId = songs.getJSONObject(idx).optLong("id")
                getLyrics(songId) { lrc, tlrc ->
                    if (!lrc.isNullOrBlank() && timeTagRegex.containsMatchIn(lrc)) {
                        callback(lrc, tlrc)
                    } else {
                        tryNext(idx + 1)
                    }
                }
            }
            tryNext(0)
        } catch (e: Exception) {
            callback(null, null)
        }
    }

    /**
     * v1.2.7：清洗歌名，去掉常见后缀，提升搜索/匹配命中率
     * - (Live) / (Remix) / (伴奏) / (Cover) / - Live / [MV] 等
     */
    private fun cleanSongTitle(title: String): String {
        var t = title.trim()
        // 去掉括号及其内容：(xxx) 【xxx】 [xxx]（xxx）
        t = t.replace(Regex("""[\(（\[【].*?[\)）\]】]"""), "").trim()
        // 去掉 -xxx / —xxx 后缀（如 "- Live"、"- Remix"）
        t = t.replace(Regex("""[\-–—]\s*(Live|Remix|Remastered|Cover|Instrumental|伴奏|混音版|翻唱|纯音乐).*$""", RegexOption.IGNORE_CASE), "").trim()
        return t
    }

    /**
     * v1.2.7：正确解析网易云搜索结果里的 artists 字段（JSONArray）
     */
    private fun parseArtists(songJson: JSONObject): String {
        // 网易云 /search/get 返回的 artists 是 JSONArray
        val arr = songJson.optJSONArray("artists")
        if (arr != null && arr.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val name = arr.optJSONObject(i)?.optString("name", "") ?: ""
                if (name.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append("/")
                    sb.append(name)
                }
            }
            return sb.toString()
        }
        // 兜底：artist 字段（部分接口返回单数形式）
        return songJson.optJSONObject("artist")?.optString("name", "") ?: ""
    }

    /**
     * v1.2.7：歌名+歌手匹配打分
     * 分数越高越匹配。0 表示完全不匹配。
     */
    private fun matchScore(cleanTitle: String, rawTitle: String, songName: String, queryArtist: String, songArtist: String): Int {
        val n = songName.lowercase().trim()
        val ct = cleanTitle.lowercase().trim()
        val rt = rawTitle.lowercase().trim()
        var score = 0
        // 歌名完全相等：最强
        if (n == ct || n == rt) score += 100
        else if (n.isNotEmpty() && ct.isNotEmpty() && (n.contains(ct) || ct.contains(n))) score += 60
        else if (n.isNotEmpty() && rt.isNotEmpty() && (n.contains(rt) || rt.contains(n))) score += 40
        else score += 0 // 不匹配
        // 歌手匹配
        val qa = queryArtist.lowercase().trim()
        val sa = songArtist.lowercase().trim()
        if (qa.isNotEmpty() && sa.isNotEmpty()) {
            if (sa == qa) score += 30
            else if (sa.contains(qa) || qa.contains(sa)) score += 20
        }
        return score
    }

    /**
     * 获取歌曲详情（封面图等）
     * v1.0.8：当 search 返回的 picUrl 为空时，用此接口获取
     * v1.0.10：网易云无版权的曲子（如术曲/V家曲）al.picUrl 会为空，由调用方再走 QQ 音乐兜底
     */
    fun getSongDetail(songId: Long, callback: (String?) -> Unit) {
        thread {
            try {
                val url = "$BASE/song/detail?ids=$songId"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                    .header("Referer", "https://music.163.com")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: run {
                    callback(null); return@thread
                }
                val json = JSONObject(body)
                val songs = json.optJSONArray("songs")
                if (songs == null || songs.length() == 0) {
                    callback(null); return@thread
                }
                val picUrl = songs.getJSONObject(0)
                    .optJSONObject("al")?.optString("picUrl", null)
                    ?: songs.getJSONObject(0)
                        .optJSONObject("album")?.optString("picUrl", null)
                callback(if (picUrl.isNullOrEmpty()) null else picUrl)
            } catch (e: Exception) {
                Log.e(TAG, "获取歌曲详情失败: ${e.message}")
                callback(null)
            }
        }
    }

    /**
     * v1.0.10：QQ 音乐封面兜底
     * 网易云无版权/缺封面时（术曲、V家曲等），用歌名+歌手去 QQ 音乐搜，
     * 取第一个结果的专辑 mid 拼成封面 URL。
     *
     * QQ 音乐封面 CDN：https://y.gtimg.cn/music/photo_new/T002R300x300M000{albummid}.jpg
     */
    fun getCoverFromQQMusic(name: String, artist: String, callback: (String?) -> Unit) {
        thread {
            try {
                val keyword = if (artist.isBlank()) name else "$name $artist"
                val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
                val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$encoded&format=json&n=1&p=1&cr=1&g_tk=5381"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://y.qq.com/")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: run {
                    callback(null); return@thread
                }
                val json = JSONObject(body)
                val list = json.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
                if (list == null || list.length() == 0) {
                    callback(null); return@thread
                }
                val albumMid = list.getJSONObject(0).optString("albummid", "")
                if (albumMid.isEmpty()) {
                    callback(null); return@thread
                }
                val coverUrl = "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg"
                callback(coverUrl)
            } catch (e: Exception) {
                Log.e(TAG, "QQ音乐封面获取失败: ${e.message}")
                callback(null)
            }
        }
    }

    /**
     * v1.1.1：酷狗音乐封面
     * 用歌名+歌手搜索，取 album_id 再查专辑封面（imge.kugou.com）
     * 酷狗封面最全，术曲/V家曲基本都有
     */
    fun getCoverFromKugou(name: String, artist: String, callback: (String?) -> Unit) {
        thread {
            try {
                val keyword = if (artist.isBlank()) name else "$name $artist"
                val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
                // 1. 搜索取 album_id
                val searchUrl = "http://mobilecdn.kugou.com/api/v3/search/song?keyword=$encoded&page=1&pagesize=1&format=json"
                val searchReq = Request.Builder().url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val searchResp = client.newCall(searchReq).execute()
                val searchBody = searchResp.body?.string() ?: run {
                    callback(null); return@thread
                }
                val searchJson = JSONObject(searchBody)
                val info = searchJson.optJSONObject("data")?.optJSONArray("info")
                if (info == null || info.length() == 0) {
                    callback(null); return@thread
                }
                val first = info.getJSONObject(0)
                // 校验歌名对得上（避免取到无关封面）
                val songName = first.optString("songname", "")
                if (!nameMatch(songName, name)) {
                    callback(null); return@thread
                }
                val albumId = first.optString("album_id", "")
                if (albumId.isEmpty() || albumId == "0") {
                    callback(null); return@thread
                }
                // 2. 查专辑封面
                val albumUrl = "http://mobilecdn.kugou.com/api/v3/album/info?albumid=$albumId&format=json"
                val albumReq = Request.Builder().url(albumUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val albumResp = client.newCall(albumReq).execute()
                val albumBody = albumResp.body?.string() ?: run {
                    callback(null); return@thread
                }
                val albumJson = JSONObject(albumBody)
                val imgurl = albumJson.optJSONObject("data")?.optString("imgurl", "") ?: ""
                if (imgurl.isEmpty()) {
                    callback(null); return@thread
                }
                // 替换 {size} 为 480
                val coverUrl = imgurl.replace("{size}", "480")
                callback(coverUrl)
            } catch (e: Exception) {
                Log.e(TAG, "酷狗封面获取失败: ${e.message}")
                callback(null)
            }
        }
    }

    /**
     * v1.1.1：按当前音乐源获取封面
     * - 对得上歌名才返回，避免展示错误封面
     * - 当前源取不到时，自动按 酷狗→QQ→网易云 顺序兜底
     * @param neteasePicUrl 网易云已知的 picUrl（若有，避免重复请求）
     */
    fun getCover(name: String, artist: String, neteasePicUrl: String? = null, callback: (String?) -> Unit) {
        thread {
            // 兜底顺序按当前源定，当前源优先
            val order = when (currentSource) {
                MusicSource.KUGOU -> listOf(MusicSource.KUGOU, MusicSource.QQ, MusicSource.NETEASE)
                MusicSource.KUWO -> listOf(MusicSource.KUWO, MusicSource.KUGOU, MusicSource.QQ, MusicSource.NETEASE) // 酷我封面退回酷狗
                MusicSource.QQ -> listOf(MusicSource.QQ, MusicSource.KUGOU, MusicSource.NETEASE)
                MusicSource.QISHUI -> listOf(MusicSource.KUGOU, MusicSource.QQ, MusicSource.NETEASE) // 汽水无API，用酷狗兜底
                MusicSource.NETEASE -> listOf(MusicSource.NETEASE, MusicSource.KUGOU, MusicSource.QQ)
            }

            for (source in order) {
                val result = getCoverSync(source, name, artist, neteasePicUrl)
                if (!result.isNullOrEmpty()) {
                    callback(result)
                    return@thread
                }
            }
            callback(null)
        }
    }

    /**
     * v1.1.1：同步获取某源的封面（阻塞，供 getCover 顺序调用）
     * 校验歌名对得上才返回
     */
    private fun getCoverSync(source: MusicSource, name: String, artist: String, neteasePicUrl: String?): String? {
        return try {
            when (source) {
                MusicSource.NETEASE -> {
                    if (!neteasePicUrl.isNullOrEmpty()) neteasePicUrl
                    else {
                        // 用 song/detail 兜底（需要 id，这里没有，直接返回 null 走下一源）
                        null
                    }
                }
                MusicSource.KUGOU -> getKugouCoverSync(name, artist)
                MusicSource.KUWO -> getKuwoCoverSync(name, artist)
                MusicSource.QQ -> getQQCoverSync(name, artist)
                MusicSource.QISHUI -> getKugouCoverSync(name, artist) // 汽水无API，用酷狗
            }
        } catch (e: Exception) {
            null
        }
    }

    // v1.1.3：酷我封面同步版
    // 酷我搜索接口的 albumpic 多为空，用 songinfo 接口或退回酷狗
    private fun getKuwoCoverSync(name: String, artist: String): String? {
        // 先用搜索接口查 rid
        val keyword = if (artist.isBlank() || artist == "<未知>") name else "$name $artist"
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
        val searchUrl = "http://search.kuwo.cn/r.s?all=$encoded&ft=music&itemset=web_2013&client=kt&pn=0&rn=1&rformat=json&encoding=utf8"
        val searchResp = client.newCall(
            Request.Builder().url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
        ).execute()
        val raw = searchResp.body?.string() ?: return null
        val jsonStr = Regex("'(\\w+)':").replace(raw, "\"$1\":")
        val abslist = JSONObject(jsonStr).optJSONArray("abslist") ?: return null
        if (abslist.length() == 0) return null
        val s = abslist.getJSONObject(0)
        // 校验歌名对得上
        val songName = s.optString("SONGNAME", "").replace("&amp;", "&")
        if (!nameMatch(songName, name)) return null
        // albumpic 或 web_albumpic_short
        val pic = s.optString("albumpic", "").ifEmpty {
            s.optString("web_albumpic_short", "")
        }
        return if (pic.isNotEmpty()) pic else null
    }

    // 酷狗封面同步版
    private fun getKugouCoverSync(name: String, artist: String): String? {
        val keyword = if (artist.isBlank()) name else "$name $artist"
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
        val searchUrl = "http://mobilecdn.kugou.com/api/v3/search/song?keyword=$encoded&page=1&pagesize=1&format=json"
        val searchResp = client.newCall(
            Request.Builder().url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
        ).execute()
        val searchBody = searchResp.body?.string() ?: return null
        val info = JSONObject(searchBody).optJSONObject("data")?.optJSONArray("info") ?: return null
        if (info.length() == 0) return null
        val first = info.getJSONObject(0)
        val songName = first.optString("songname", "")
        if (!nameMatch(songName, name)) return null
        val albumId = first.optString("album_id", "")
        if (albumId.isEmpty() || albumId == "0") return null
        val albumUrl = "http://mobilecdn.kugou.com/api/v3/album/info?albumid=$albumId&format=json"
        val albumResp = client.newCall(
            Request.Builder().url(albumUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
        ).execute()
        val albumBody = albumResp.body?.string() ?: return null
        val imgurl = JSONObject(albumBody).optJSONObject("data")?.optString("imgurl", "") ?: ""
        return if (imgurl.isNotEmpty()) imgurl.replace("{size}", "480") else null
    }

    // QQ 音乐封面同步版
    private fun getQQCoverSync(name: String, artist: String): String? {
        val keyword = if (artist.isBlank()) name else "$name $artist"
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$encoded&format=json&n=1&p=1&cr=1&g_tk=5381"
        val resp = client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://y.qq.com/")
                .build()
        ).execute()
        val body = resp.body?.string() ?: return null
        val list = JSONObject(body).optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: return null
        if (list.length() == 0) return null
        val songName = list.getJSONObject(0).optString("songname", "")
        if (!nameMatch(songName, name)) return null
        val albumMid = list.getJSONObject(0).optString("albummid", "")
        return if (albumMid.isNotEmpty())
            "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg"
        else null
    }

    /**
     * v1.1.1：歌名匹配校验
     * 去空格、忽略大小写、去括号内容后比较包含关系
     * 用于确保封面确实对应当前歌曲，避免展示错误封面
     */
    private fun nameMatch(apiName: String, queryName: String): Boolean {
        val normalize: (String) -> String = {
            it.lowercase()
                .replace(" ", "")
                .replace(Regex("\\(.*?\\)"), "")
                .replace(Regex("（.*?）"), "")
                .replace(Regex("\\[.*?\\]"), "")
                .trim()
        }
        val a = normalize(apiName)
        val b = normalize(queryName)
        return a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))
    }

    /**
     * 获取 MV 播放 URL
     */
    fun getMvUrl(mvId: Long, callback: (String?) -> Unit) {
        thread {
            try {
                val url = "$BASE/mv/detail?id=$mvId"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                    .header("Referer", "https://music.163.com")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: run {
                    callback(null)
                    return@thread
                }
                val json = JSONObject(body)
                if (json.optInt("code", -1) != 200) {
                    callback(null)
                    return@thread
                }
                val data = json.optJSONObject("data")
                val brs = data?.optJSONObject("brs") ?: run {
                    callback(null)
                    return@thread
                }
                // 优先 720p → 480p → 240p
                val mvUrl = brs.optString("720", null)
                    ?: brs.optString("480", null)
                    ?: brs.optString("240", null)
                    ?: brs.optString("1080", null)
                callback(mvUrl)
            } catch (e: Exception) {
                Log.e(TAG, "获取MV失败: ${e.message}")
                callback(null)
            }
        }
    }

    // ======================= v1.1.4：PV 全网搜索 =======================

    /**
     * v1.1.4：PV 全网搜索
     * v1.1.4 最终方案：只用网易云 MV 搜索（type=1004）
     * 原因：酷狗/酷我 MV 播放接口均已失效，列表里显示却不能播放会导致困惑
     * 网易云 MV 库覆盖全网主流曲库，且 getMvUrl 能直接拿到 MP4 直链
     *
     * v1.2.3：支持中文搜外文 MV
     * 先用原关键词搜 MV；若结果为空，用关键词搜歌曲库拿到外文原名，再用原名搜 MV
     * 例如输入"你已经足够好了"能搜到"That's good enough already"的 MV
     */
    fun searchPvAllSources(keyword: String, callback: (List<OnlineMv>?, String?) -> Unit) {
        searchNeteaseMv(keyword) { list, err ->
            if (!list.isNullOrEmpty()) {
                callback(list, null)
                return@searchNeteaseMv
            }
            // 原关键词无 MV 结果，用关键词搜歌曲库拿外文原名再搜 MV
            searchMvBySongTitle(keyword, callback)
        }
    }

    /**
     * v1.2.3：用关键词搜网易云歌曲库，拿到匹配歌曲的原名（可能是外文），
     * 再用这些原名搜 MV。取前 3 首歌名去重搜索，合并 MV 结果。
     */
    private fun searchMvBySongTitle(keyword: String, callback: (List<OnlineMv>?, String?) -> Unit) {
        thread {
            try {
                val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
                val url = "$BASE/search/get?s=$encoded&type=1&limit=5&offset=0"
                val resp = client.newCall(
                    Request.Builder().url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                        .header("Referer", "https://music.163.com")
                        .build()
                ).execute()
                val body = resp.body?.string() ?: run {
                    callback(emptyList(), null); return@thread
                }
                val json = JSONObject(body)
                val songs = json.optJSONObject("result")?.optJSONArray("songs")
                if (songs == null || songs.length() == 0) {
                    callback(emptyList(), null); return@thread
                }
                // 收集前 3 首歌名（去重），这些可能是外文原名
                val names = mutableListOf<String>()
                for (i in 0 until minOf(songs.length(), 3)) {
                    val n = songs.getJSONObject(i).optString("name", "")
                    if (n.isNotEmpty() && n !in names) names.add(n)
                }
                if (names.isEmpty()) {
                    callback(emptyList(), null); return@thread
                }
                // 用每个歌名搜 MV，合并结果（去重 by id）
                val merged = mutableListOf<OnlineMv>()
                val seenIds = mutableSetOf<Long>()
                var pending = names.size
                if (pending == 0) { callback(emptyList(), null); return@thread }
                for (name in names) {
                    searchNeteaseMv(name) { mvs, _ ->
                        synchronized(merged) {
                            mvs?.forEach { mv ->
                                if (mv.id != 0L && seenIds.add(mv.id)) merged.add(mv)
                            }
                            pending--
                            if (pending == 0) {
                                callback(merged, null)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "中文转外文MV搜索失败: ${e.message}")
                callback(emptyList(), null)
            }
        }
    }

    /**
     * 网易云 MV 搜索
     * v1.1.4 修复：旧接口 /api/mv/search 已下线（返回 404 接口未找到）
     * 改用 /api/search/get?s=xxx&type=1004（type=1004 为 MV 类型）
     * 返回 result.mvs[]，字段：id, name, artistName, cover, duration
     */
    private fun searchNeteaseMv(keyword: String, callback: (List<OnlineMv>?, String?) -> Unit) {
        thread {
            try {
                val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
                val url = "$BASE/search/get?s=$encoded&type=1004&limit=30&offset=0"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                    .header("Referer", "https://music.163.com")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: run {
                    callback(null, "网易云MV搜索无响应")
                    return@thread
                }
                val json = JSONObject(body)
                if (json.optInt("code", -1) != 200) {
                    callback(null, "网易云MV搜索失败：${json.optString("message", "code=${json.optInt("code")}")}")
                    return@thread
                }
                val mvs = json.optJSONObject("result")?.optJSONArray("mvs")
                if (mvs == null || mvs.length() == 0) {
                    callback(emptyList(), null)
                    return@thread
                }
                val list = mutableListOf<OnlineMv>()
                for (i in 0 until mvs.length()) {
                    val m = mvs.getJSONObject(i)
                    list.add(OnlineMv(
                        id = m.optLong("id", 0),
                        name = m.optString("name", "未知MV"),
                        artist = m.optString("artistName", "<未知>"),
                        cover = m.optString("cover", null),
                        duration = m.optLong("duration", 0),
                        source = MusicSource.NETEASE
                    ))
                }
                callback(list, null)
            } catch (e: Exception) {
                Log.e(TAG, "网易云MV搜索失败: ${e.message}")
                callback(null, "网易云MV搜索失败: ${e.message}")
            }
        }
    }

    /**
     * 酷狗 MV 搜索
     * http://msearchcdn.kugou.com/api/v3/search/mv?keyword=xxx&pagesize=30
     * v1.1.4 修复：字段名修正
     * 返回 data.info[]，字段：hash(MV hash), filename(歌名), singername, imgurl(封面), duration(秒)
     */
    private fun searchKugouMv(keyword: String, callback: (List<OnlineMv>?, String?) -> Unit) {
        thread {
            try {
                val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
                val url = "http://msearchcdn.kugou.com/api/v3/search/mv?keyword=$encoded&page=1&pagesize=30&format=json"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: run {
                    callback(null, "酷狗MV搜索无响应")
                    return@thread
                }
                val json = JSONObject(body)
                if (json.optInt("status", -1) != 1) {
                    callback(null, "酷狗MV搜索失败")
                    return@thread
                }
                val info = json.optJSONObject("data")?.optJSONArray("info")
                if (info == null || info.length() == 0) {
                    callback(emptyList(), null)
                    return@thread
                }
                val list = mutableListOf<OnlineMv>()
                for (i in 0 until info.length()) {
                    val m = info.getJSONObject(i)
                    // v1.1.4：字段名是 hash / filename / imgurl（不是 mvhash / songname / img）
                    val mvHash = m.optString("hash", "")
                    if (mvHash.isEmpty()) continue
                    list.add(OnlineMv(
                        id = 0L,
                        name = m.optString("filename", "未知MV"),
                        artist = m.optString("singername", "<未知>"),
                        cover = m.optString("imgurl", null),
                        duration = m.optLong("duration", 0) * 1000,
                        source = MusicSource.KUGOU,
                        mvHash = mvHash
                    ))
                }
                callback(list, null)
            } catch (e: Exception) {
                Log.e(TAG, "酷狗MV搜索失败: ${e.message}")
                callback(null, "酷狗MV搜索失败: ${e.message}")
            }
        }
    }

    /**
     * 酷我 MV 搜索
     * http://search.kuwo.cn/r.s?all=xxx&ft=mv&itemset=web_2013&client=kt&pn=0&rn=30
     * 返回 abslist[]，字段：DC_TARGETID(mv id), NAME, ARTIST, pic
     */
    private fun searchKuwoMv(keyword: String, callback: (List<OnlineMv>?, String?) -> Unit) {
        thread {
            try {
                val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
                val url = "http://search.kuwo.cn/r.s?all=$encoded&ft=mv&itemset=web_2013&client=kt&pn=0&rn=30&rformat=json&encoding=utf8"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val response = client.newCall(request).execute()
                val raw = response.body?.string() ?: run {
                    callback(null, "酷我MV搜索无响应")
                    return@thread
                }
                // 酷我旧接口返回单引号 JSON，转成标准双引号
                val jsonStr = Regex("'(\\w+)':").replace(raw, "\"$1\":")
                val json = JSONObject(jsonStr)
                val abslist = json.optJSONArray("abslist")
                if (abslist == null || abslist.length() == 0) {
                    callback(emptyList(), null)
                    return@thread
                }
                val list = mutableListOf<OnlineMv>()
                for (i in 0 until abslist.length()) {
                    val m = abslist.getJSONObject(i)
                    val mvId = m.optString("DC_TARGETID", "").toLongOrNull() ?: continue
                    list.add(OnlineMv(
                        id = mvId,
                        name = m.optString("NAME", "未知MV").replace("&amp;", "&"),
                        artist = m.optString("ARTIST", "<未知>").replace("&amp;", "&"),
                        cover = m.optString("pic", null),
                        duration = 0L,
                        source = MusicSource.KUWO
                    ))
                }
                callback(list, null)
            } catch (e: Exception) {
                Log.e(TAG, "酷我MV搜索失败: ${e.message}")
                callback(null, "酷我MV搜索失败: ${e.message}")
            }
        }
    }

    /**
     * v1.1.4：获取 MV 播放 URL（按来源分发）
     * - 网易云：getMvUrl(mvId) 已有
     * - 酷狗：getKugouMvUrl(mvHash)
     * - 酷我：getKuwoMvUrl(mvId)
     */
    fun getMvPlayUrl(mv: OnlineMv, callback: (String?) -> Unit) {
        when (mv.source) {
            MusicSource.NETEASE -> {
                if (mv.id <= 0) {
                    callback(null); return
                }
                getMvUrl(mv.id, callback)
            }
            MusicSource.KUGOU -> {
                if (mv.mvHash.isNullOrEmpty()) {
                    callback(null); return
                }
                getKugouMvUrl(mv.mvHash!!, callback)
            }
            MusicSource.KUWO -> {
                if (mv.id <= 0) {
                    callback(null); return
                }
                getKuwoMvUrl(mv.id, callback)
            }
            else -> callback(null)
        }
    }

    /**
     * 酷狗 MV 播放 URL
     * v1.1.4：getVideoInfo.php 接口已下线（返回 "No Action Found!"）
     * 酷狗 MV 播放需 VIP，无公开免费接口。这里直接返回 null，
     * 上层 fallbackOtherSources 会用网易云同名 MV 兜底播放。
     */
    private fun getKugouMvUrl(mvHash: String, callback: (String?) -> Unit) {
        Log.w(TAG, "酷狗MV播放接口已失效(getVideoInfo下线)，返回null走网易云兜底, hash=$mvHash")
        callback(null)
    }

    /**
     * 酷我 MV 播放 URL
     * http://antiserver.kuwo.cn/anti.s?type=convert_url_with_format&format=mp4&response=url&rid=mp4_{id}&br=2000mp4
     * 直接返回 MP4 直链
     */
    private fun getKuwoMvUrl(mvId: Long, callback: (String?) -> Unit) {
        thread {
            try {
                val url = "http://antiserver.kuwo.cn/anti.s?type=convert_url_with_format&format=mp4&response=url&rid=mp4_$mvId&br=2000mp4"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()?.trim() ?: run {
                    callback(null); return@thread
                }
                // 返回的是纯 URL 文本（response=url），可能带换行
                val mvUrl = body.takeIf { it.startsWith("http") }
                callback(mvUrl)
            } catch (e: Exception) {
                Log.e(TAG, "酷我MV URL获取失败: ${e.message}")
                callback(null)
            }
        }
    }
}
