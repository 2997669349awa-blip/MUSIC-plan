package com.example.musicplugin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 收藏管理（SharedPreferences + JSON 持久化）
 */
object Favorites {
    private const val PREF_NAME = "music_plugin"
    private const val KEY = "favorites"

    private val songs = mutableListOf<Song>()

    fun init(context: Context) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = sp.getString(KEY, "[]") ?: "[]"
        songs.clear()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                songs.add(Song(
                    id = o.optLong("id"),
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    album = o.optString("album"),
                    path = o.optString("path"),
                    duration = o.optLong("duration"),
                    size = o.optLong("size"),
                    bitrate = o.optLong("bitrate")
                ))
            }
        } catch (e: Exception) {
            // 解析失败：忽略
        }
    }

    fun save(context: Context) {
        val arr = JSONArray()
        for (s in songs) {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("title", s.title)
            o.put("artist", s.artist)
            o.put("album", s.album)
            o.put("path", s.path)
            o.put("duration", s.duration)
            o.put("size", s.size)
            o.put("bitrate", s.bitrate)
            arr.put(o)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun contains(id: Long): Boolean = songs.any { it.id == id }

    /**
     * 切换收藏状态
     * @return true 表示已收藏，false 表示已取消
     */
    fun toggle(song: Song, context: Context): Boolean {
        val idx = songs.indexOfFirst { it.id == song.id }
        return if (idx >= 0) {
            songs.removeAt(idx)
            save(context)
            false
        } else {
            songs.add(song)
            save(context)
            true
        }
    }

    fun getAll(): List<Song> = songs.toList()

    fun size(): Int = songs.size
}
