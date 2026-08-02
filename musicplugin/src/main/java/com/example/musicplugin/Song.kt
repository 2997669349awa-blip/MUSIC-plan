package com.example.musicplugin

/**
 * 音乐文件信息
 */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val path: String,
    val duration: Long,    // 毫秒
    val size: Long,        // 字节
    val bitrate: Long      // 比特率 bps（粗略估算）
)

/**
 * 音质等级
 */
enum class Quality(val label: String, val minBitrate: Long, val minSizeMB: Long) {
    STANDARD("标准", 0, 0),
    HIGH("较高", 192_000, 5),       // >=192kbps 或 >=5MB
    LOSSLESS("无损", 320_000, 20)   // >=320kbps 或 >=20MB（FLAC/APE/WAV）
}

/**
 * 播放模式
 */
enum class PlayMode { LOOP, SINGLE, SHUFFLE }
