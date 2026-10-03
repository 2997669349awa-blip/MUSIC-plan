package com.example.musicplugin

/**
 * 歌词解析器
 * 解析 LRC 格式歌词：[mm:ss.xx]歌词文本
 */
class LyricParser {

    data class LyricLine(val time: Long, val text: String, val translation: String? = null)

    private val lines = mutableListOf<LyricLine>()

    /**
     * 从 LRC 文本解析
     */
    fun parse(lrcText: String?, translationText: String? = null): List<LyricLine> {
        lines.clear()
        if (lrcText.isNullOrBlank()) return emptyList()

        // 解析翻译歌词
        val transMap = mutableMapOf<Long, String>()
        if (!translationText.isNullOrBlank()) {
            val regex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})](.*)""")
            translationText.lines().forEach { line ->
                val m = regex.find(line) ?: return@forEach
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val ms = m.groupValues[3].toLong()
                val time = min * 60 * 1000 + sec * 1000 + ms
                val text = m.groupValues[4].trim()
                if (text.isNotEmpty()) transMap[time] = text
            }
        }

        // 解析主歌词
        val regex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})](.*)""")
        lrcText.lines().forEach { line ->
            val m = regex.find(line) ?: return@forEach
            val min = m.groupValues[1].toLong()
            val sec = m.groupValues[2].toLong()
            val ms = m.groupValues[3].toLong()
            val time = min * 60 * 1000 + sec * 1000 + ms
            val text = m.groupValues[4].trim()
            if (text.isNotEmpty()) {
                lines.add(LyricLine(time, text, transMap[time]))
            }
        }
        lines.sortBy { it.time }
        return lines.toList()
    }

    /**
     * 根据当前播放进度获取当前歌词索引
     */
    fun getIndexAtTime(timeMs: Long, lyricLines: List<LyricLine>): Int {
        if (lyricLines.isEmpty()) return -1
        var idx = 0
        for (i in lyricLines.indices) {
            if (lyricLines[i].time <= timeMs) idx = i
            else break
        }
        return idx
    }
}
