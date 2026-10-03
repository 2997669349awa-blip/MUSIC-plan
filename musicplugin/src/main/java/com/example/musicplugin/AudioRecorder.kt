package com.example.musicplugin

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * v1.1.8：音频录制器（16kHz 单声道 16bit PCM）
 * 为 Shazam 指纹算法提供原始音频数据
 */
class AudioRecorder(private val outputFile: File) {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile
    private var isRecording = false

    private val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, FORMAT)

    fun start() {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL, FORMAT,
            minBufferSize
        )
        audioRecord?.startRecording()
        isRecording = true

        recordingThread = Thread {
            val baos = ByteArrayOutputStream()
            val buffer = ByteArray(minBufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    baos.write(buffer, 0, read)
                }
            }
            // 写入 WAV 文件（ShazamFingerprint 读取 PCM 数据）
            writeWav(baos.toByteArray())
        }
        recordingThread?.start()
    }

    fun stop() {
        isRecording = false
        recordingThread?.join(500)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun writeWav(pcmData: ByteArray) {
        try {
            FileOutputStream(outputFile).use { fos ->
                val totalAudioLen = pcmData.size.toLong()
                val totalDataLen = totalAudioLen + 36
                val byteRate = (SAMPLE_RATE * 1 * 16 / 8)

                fos.write("RIFF".toByteArray())
                fos.write(intToByteArray(totalDataLen.toInt()))
                fos.write("WAVE".toByteArray())
                fos.write("fmt ".toByteArray())
                fos.write(intToByteArray(16))
                fos.write(shortToByteArray(1))
                fos.write(shortToByteArray(1))
                fos.write(intToByteArray(SAMPLE_RATE))
                fos.write(intToByteArray(byteRate))
                fos.write(shortToByteArray((1 * 16 / 8).toShort()))
                fos.write(shortToByteArray(16))
                fos.write("data".toByteArray())
                fos.write(intToByteArray(totalAudioLen.toInt()))
                fos.write(pcmData)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun intToByteArray(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToByteArray(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )
}
