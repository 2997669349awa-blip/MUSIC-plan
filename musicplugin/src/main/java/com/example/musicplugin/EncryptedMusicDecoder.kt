package com.example.musicplugin

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * v1.2.1：加密音乐格式解密器
 *
 * 当前支持：
 * - NCM（网易云音乐）：AES-128-ECB 解出 RC4 密钥 → 自定义流密码（keybox 循环异或）
 *   结构通过解码后音频魔法头自动探测，兼容有无封面图两种布局。
 *
 * 其他加密格式（kgm/kgma/vpr/qmc* /tkm/kgg 等）：
 * - 已在 MusicScanner 中登记，会出现在本地列表；
 * - 解密暂未实现（需要平台专属掩码表），播放器将尝试直接播放，无法播放时会提示错误。
 */
object EncryptedMusicDecoder {

    /** AES-128-ECB 密钥，用于解出真正的 RC4 密钥 */
    private val CORE_KEY = "hzHRAmso5kInbaxW".toByteArray()

    /** 需要解密才能播放的加密格式后缀 */
    private val ENCRYPTED_EXTS = setOf(
        "ncm", "kgm", "kgma", "vpr", "qmc", "qmc0", "qmc3", "qmcflac", "qmcogg", "tkm", "kgg"
    )

    fun isEncrypted(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in ENCRYPTED_EXTS
    }

    /**
     * 将加密音乐解密到临时文件，返回临时文件；解密失败返回 null。
     * 在后台线程调用。
     */
    fun decodeToTempFile(inputPath: String, cacheDir: File): File? {
        val ext = inputPath.substringAfterLast('.', "").lowercase()
        val input = File(inputPath)
        if (!input.exists()) return null
        return try {
            when (ext) {
                "ncm" -> decodeNcm(input, cacheDir)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun aesEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    /**
     * 网易云 NCM 解密
     * 结构：magic(8) + keyLen(4 LE) + encKey + metaLen(4 LE) + meta + [crc(4) + [coverSize(4)+cover]? + audio]
     */
    private fun decodeNcm(input: File, cacheDir: File): File? {
        val raw = input.readBytes()
        var p = 0

        fun readIntLe(): Int {
            if (p + 4 > raw.size) throw IllegalStateException("ncm: truncated key/meta len")
            val v = ByteBuffer.wrap(raw, p, 4).order(ByteOrder.LITTLE_ENDIAN).int
            p += 4
            return v
        }

        fun readBytes(n: Int): ByteArray {
            if (n < 0 || p + n > raw.size) throw IllegalStateException("ncm: truncated body")
            val b = raw.copyOfRange(p, p + n)
            p += n
            return b
        }

        // 1. magic
        val magic = readBytes(8)
        if (!magic.contentEquals("CTENFDAM".toByteArray())) return null

        // 2. 加密的 RC4 密钥
        val keyLen = readIntLe()
        if (keyLen <= 0 || keyLen > raw.size) return null
        val encKey = readBytes(keyLen)
        for (i in encKey.indices) encKey[i] = (encKey[i].toInt() xor 0x64).toByte()
        val decKey = aesEcbDecrypt(CORE_KEY, encKey)
        if (decKey.size < 18) return null
        // 去掉前 17 字节 "neteasecloudmusic" 前缀
        val rc4Key = decKey.copyOfRange(17, decKey.size)

        // 3. 元数据（跳过，格式从音频头探测）
        val metaLen = readIntLe()
        if (metaLen < 0 || metaLen > raw.size) return null
        readBytes(metaLen)

        // 4. 构建 keybox（RC4 KSA）
        val box = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (box[i] + j + (rc4Key[i % rc4Key.size].toInt() and 0xff)) and 0xff
            val t = box[i]; box[i] = box[j]; box[j] = t
        }

        // 5. 剩余部分：可能含 CRC + 封面 + 音频，通过解密后魔法头自动定位音频起点
        val rest = raw.copyOfRange(p, raw.size)
        val audioStart = findAudioStart(rest, box)
        if (audioStart < 0 || audioStart >= rest.size) return null
        val audio = rest.copyOfRange(audioStart, rest.size)

        // 6. 流密码解密：第 N 字节 ^= box[(N + 1) & 0xff]
        for (i in audio.indices) {
            audio[i] = (audio[i].toInt() xor box[(i + 1) and 0xff]).toByte()
        }

        // 7. 探测输出格式
        val outExt = detectFormat(audio)
        val outFile = File(cacheDir, "ncm_${System.currentTimeMillis()}_${input.nameWithoutExtension}.$outExt")
        outFile.writeBytes(audio)
        return outFile
    }

    /**
     * 尝试若干候选偏移，解密前 4 字节看是否为已知音频魔法头，命中即为音频起点。
     */
    private fun findAudioStart(rest: ByteArray, box: IntArray): Int {
        val candidates = mutableListOf(4, 9, 8, 13)
        if (rest.size >= 8) {
            val coverSize = ByteBuffer.wrap(rest, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (coverSize > 0 && coverSize < 5_000_000) {
                candidates.add(8 + coverSize)
                candidates.add(9 + coverSize)
                candidates.add(13 + coverSize)
            }
        }
        for (s in candidates) {
            if (s < 0 || s + 4 > rest.size) continue
            val d = ByteArray(4)
            for (k in 0 until 4) {
                d[k] = (rest[s + k].toInt() xor box[(k + 1) and 0xff]).toByte()
            }
            if (matchesMagic(d)) return s
        }
        // 兜底：仅 CRC(4) 后即为音频
        return 4
    }

    private fun matchesMagic(d: ByteArray): Boolean {
        if (d.size < 4) return false
        // fLaC
        if (d[0] == 0x66.toByte() && d[1] == 0x4C.toByte() && d[2] == 0x61.toByte() && d[3] == 0x43.toByte()) return true
        // ID3 (mp3)
        if (d[0] == 0x49.toByte() && d[1] == 0x44.toByte() && d[2] == 0x33.toByte()) return true
        // OggS
        if (d[0] == 0x4F.toByte() && d[1] == 0x67.toByte() && d[2] == 0x67.toByte() && d[3] == 0x53.toByte()) return true
        // ftyp (m4a)
        if (d[0] == 0x66.toByte() && d[1] == 0x74.toByte() && d[2] == 0x79.toByte() && d[3] == 0x70.toByte()) return true
        // RIFF (wav)
        if (d[0] == 0x52.toByte() && d[1] == 0x49.toByte() && d[2] == 0x46.toByte() && d[3] == 0x46.toByte()) return true
        // MP3 帧同步
        if (d[0] == 0xFF.toByte() && (d[1] == 0xFB.toByte() || d[1] == 0xF3.toByte() ||
                d[1] == 0xF2.toByte() || d[1] == 0xFA.toByte())
        ) return true
        return false
    }

    private fun detectFormat(data: ByteArray): String {
        if (data.size >= 4) {
            if (data[0] == 0x66.toByte() && data[1] == 0x4C.toByte() && data[2] == 0x61.toByte() && data[3] == 0x43.toByte()) return "flac"
            if (data[0] == 0x4F.toByte() && data[1] == 0x67.toByte() && data[2] == 0x67.toByte() && data[3] == 0x53.toByte()) return "ogg"
            if (data[0] == 0x66.toByte() && data[1] == 0x74.toByte() && data[2] == 0x79.toByte() && data[3] == 0x70.toByte()) return "m4a"
            if (data[0] == 0x52.toByte() && data[1] == 0x49.toByte() && data[2] == 0x46.toByte() && data[3] == 0x46.toByte()) return "wav"
            if (data[0] == 0x49.toByte() && data[1] == 0x44.toByte() && data[2] == 0x33.toByte()) return "mp3"
        }
        if (data.size >= 2 && data[0] == 0xFF.toByte() && (data[1].toInt() and 0xE0) == 0xE0) return "mp3"
        return "mp3"
    }
}
