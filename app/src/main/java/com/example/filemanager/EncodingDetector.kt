package com.example.filemanager

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.Charset

/**
 * 文件编码自动检测器
 * 支持 UTF-8、GBK、GB2312、BIG5、UTF-16、ISO-8859-1 等
 */
object EncodingDetector {

    private val BOM_UTF_8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val BOM_UTF_16_BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private val BOM_UTF_16_LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

    /**
     * 检测文件编码
     */
    fun detect(file: File): String {
        if (!file.exists() || !file.canRead()) return "UTF-8"

        try {
            FileInputStream(file).use { fis ->
                val sampleSize = minOf(file.length(), 8192).toInt()
                val buffer = ByteArray(sampleSize)
                val read = fis.read(buffer)
                if (read <= 0) return "UTF-8"

                val actualBuffer = if (read < buffer.size) buffer.copyOf(read) else buffer

                // 1. 先检查 BOM
                val bomResult = detectBOM(actualBuffer)
                if (bomResult != null) return bomResult

                // 2. 检查是否为 UTF-8
                if (isValidUtf8(actualBuffer)) return "UTF-8"

                // 3. 判断是 UTF-16 还是 GBK/GB2312
                // 如果有大量 NULL 字节，可能是 UTF-16
                val nullCount = actualBuffer.count { it == 0.toByte() }
                if (nullCount > actualBuffer.size * 0.3) {
                    return "UTF-16"
                }

                // 4. 用 GBK 尝试解码
                if (isValidGbk(actualBuffer)) return "GBK"

                // 5. 尝试 BIG5
                if (isValidBig5(actualBuffer)) return "BIG5"

                // 6. 默认 ISO-8859-1
                return "ISO-8859-1"
            }
        } catch (e: Exception) {
            return "UTF-8"
        }
    }

    private fun detectBOM(buffer: ByteArray): String? {
        if (buffer.size >= 3 && buffer[0] == BOM_UTF_8[0] && buffer[1] == BOM_UTF_8[1] && buffer[2] == BOM_UTF_8[2]) {
            return "UTF-8"
        }
        if (buffer.size >= 2 && buffer[0] == BOM_UTF_16_BE[0] && buffer[1] == BOM_UTF_16_BE[1]) {
            return "UTF-16BE"
        }
        if (buffer.size >= 2 && buffer[0] == BOM_UTF_16_LE[0] && buffer[1] == BOM_UTF_16_LE[1]) {
            return "UTF-16LE"
        }
        return null
    }

    /**
     * 验证是否为合法 UTF-8
     */
    private fun isValidUtf8(buffer: ByteArray): Boolean {
        var i = 0
        var multiByteCount = 0
        while (i < buffer.size) {
            val b = buffer[i].toInt() and 0xFF
            if (b < 0x80) {
                // ASCII
                i++
            } else if (b in 0xC0..0xDF) {
                // 2字节
                if (i + 1 >= buffer.size) return false
                if ((buffer[i + 1].toInt() and 0xC0) != 0x80) return false
                multiByteCount++
                i += 2
            } else if (b in 0xE0..0xEF) {
                // 3字节
                if (i + 2 >= buffer.size) return false
                if ((buffer[i + 1].toInt() and 0xC0) != 0x80) return false
                if ((buffer[i + 2].toInt() and 0xC0) != 0x80) return false
                multiByteCount++
                i += 3
            } else if (b in 0xF0..0xF7) {
                // 4字节
                if (i + 3 >= buffer.size) return false
                if ((buffer[i + 1].toInt() and 0xC0) != 0x80) return false
                if ((buffer[i + 2].toInt() and 0xC0) != 0x80) return false
                if ((buffer[i + 3].toInt() and 0xC0) != 0x80) return false
                multiByteCount++
                i += 4
            } else {
                return false
            }
        }
        // 如果没有多字节字符，也可能是纯ASCII(UTF-8的子集)
        return true
    }

    /**
     * 验证是否为合法 GBK
     */
    private fun isValidGbk(buffer: ByteArray): Boolean {
        try {
            val decoded = String(buffer, Charset.forName("GBK"))
            // 检查是否有替换字符，说明解码失败
            val replacement = "\uFFFD"
            val replacementCount = decoded.count { it.toString() == replacement }
            // 如果替换字符比例很低，认为是GBK
            return replacementCount < decoded.length * 0.01
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 验证是否为合法 BIG5
     */
    private fun isValidBig5(buffer: ByteArray): Boolean {
        try {
            val decoded = String(buffer, Charset.forName("BIG5"))
            val replacement = "\uFFFD"
            val replacementCount = decoded.count { it.toString() == replacement }
            return replacementCount < decoded.length * 0.01
        } catch (e: Exception) {
            return false
        }
    }
}
