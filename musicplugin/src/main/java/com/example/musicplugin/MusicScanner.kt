package com.example.musicplugin

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 本地音乐扫描工具
 * 使用 MediaStore.Audio.Media 查询系统媒体库，并补充从存储根目录递归扫描
 */
object MusicScanner {

    // v1.2.1：在原有格式基础上增加网易云/酷狗/QQ 等平台专属加密后缀
    // 加密格式由 EncryptedMusicDecoder 解密后播放（ncm 已支持，其余暂透传）
    private val AUDIO_EXTS = setOf(
        "mp3", "flac", "ape", "wav", "ogg", "m4a", "aac", "wma", "opus",
        // 网易云
        "ncm",
        // 酷狗
        "kgm", "kgma", "vpr", "kgg",
        // QQ 音乐
        "qmc", "qmc0", "qmc3", "qmcflac", "qmcogg", "tkm"
    )

    /**
     * 通过 MediaStore 查询音乐
     */
    fun scanViaMediaStore(context: Context): List<Song> {
        val result = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null, sortOrder
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                while (c.moveToNext()) {
                    val path = c.getString(dataCol) ?: continue
                    val size = c.getLong(sizeCol)
                    val dur = c.getLong(durCol)
                    val bitrate = if (dur > 0) size * 8 * 1000 / dur else 0L
                    result.add(Song(
                        id = c.getLong(idCol),
                        title = c.getString(titleCol) ?: File(path).nameWithoutExtension,
                        artist = c.getString(artistCol) ?: "<未知艺术家>",
                        album = c.getString(albumCol) ?: "<未知专辑>",
                        path = path,
                        duration = dur,
                        size = size,
                        bitrate = bitrate
                    ))
                }
            }
        } catch (e: Exception) {
            // 忽略
        } finally {
            cursor?.close()
        }
        return result
    }

    /**
     * 递归扫描存储目录中的音频文件（作为 MediaStore 的补充）
     */
    fun scanFileSystem(maxDepth: Int = 5): List<Song> {
        val result = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        val roots = listOfNotNull(
            Environment.getExternalStorageDirectory(),
            File("/sdcard"),
            File("/storage/emulated/0")
        ).distinctBy { it.absolutePath }

        for (root in roots) {
            scanDir(root, 0, maxDepth, result, seen)
        }
        return result
    }

    private fun scanDir(dir: File, depth: Int, maxDepth: Int, out: MutableList<Song>, seen: MutableSet<String>) {
        if (depth > maxDepth) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                // 跳过系统目录
                if (f.name.startsWith(".") || f.name == "Android" || f.name == "data") continue
                scanDir(f, depth + 1, maxDepth, out, seen)
            } else {
                val ext = f.extension.lowercase()
                if (ext !in AUDIO_EXTS) continue
                val absPath = f.absolutePath
                if (absPath in seen) continue
                seen.add(absPath)
                val size = f.length()
                // 粗略估算时长：用文件大小反推（mp3 按 128kbps）
                val estDuration = when (ext) {
                    "flac", "ape", "wav" -> size * 8 * 1000 / 1_411_200  // 16bit/44.1k 双声道
                    else -> size * 8 * 1000 / 192_000
                }
                val bitrate = when (ext) {
                    "flac", "ape", "wav" -> 1_411_200L
                    "ogg", "opus" -> 192_000L
                    else -> 320_000L.coerceAtMost((size * 8 * 1000 / estDuration.coerceAtLeast(1)))
                }
                out.add(Song(
                    id = absPath.hashCode().toLong(),
                    title = f.nameWithoutExtension,
                    artist = "<未知艺术家>",
                    album = "<未知专辑>",
                    path = absPath,
                    duration = estDuration,
                    size = size,
                    bitrate = bitrate
                ))
            }
        }
    }

    /**
     * 合并 MediaStore 与文件系统扫描结果
     */
    fun scanAll(context: Context): List<Song> {
        val merged = mutableListOf<Song>()
        val seenPaths = mutableSetOf<String>()
        // 先 MediaStore
        for (s in scanViaMediaStore(context)) {
            if (s.path !in seenPaths) {
                merged.add(s); seenPaths.add(s.path)
            }
        }
        // 再文件系统
        for (s in scanFileSystem()) {
            if (s.path !in seenPaths) {
                merged.add(s); seenPaths.add(s.path)
            }
        }
        return merged.sortedBy { it.title.lowercase() }
    }
}
