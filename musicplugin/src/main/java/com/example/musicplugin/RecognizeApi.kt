package com.example.musicplugin

import android.content.Context
import java.io.File

/**
 * v1.1.8：听歌识曲（Shazam 方案，完全免费，无需注册）
 *
 * 识别流程：
 * 1. 录制 10 秒音频（WAV 16kHz 16bit mono）
 * 2. 生成 Shazam 音频指纹
 * 3. 自动获取 Shazam Bearer Token → 调用 match API
 * 4. 拿到歌名+歌手后，回查当前音乐源获取可播放歌曲
 */
object RecognizeApi {

    data class RecognizeResult(
        val success: Boolean,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val errorMsg: String? = null
    )

    /**
     * 识别音频文件
     * @param context 用于获取缓存的 token
     * @param audioFile 录音文件（wav）
     * @param callback 主线程回调
     */
    fun recognize(context: Context, audioFile: File, callback: (RecognizeResult) -> Unit) {
        ShazamApi.recognize(context, audioFile) { result ->
            callback(RecognizeResult(
                success = result.success,
                title = result.title,
                artist = result.artist,
                album = result.album,
                errorMsg = result.errorMsg
            ))
        }
    }
}
