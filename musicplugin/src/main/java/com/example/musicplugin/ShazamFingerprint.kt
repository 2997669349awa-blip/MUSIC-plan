package com.example.musicplugin

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * v1.1.8：Shazam 音频指纹算法（Kotlin 移植）
 *
 * 基于 ST-Shazam (github.com/sheikhtamimlover/Shazam) 的逆向工程实现。
 * 包含：FFT → 峰值扩散 → 峰值识别 → 二进制编码 → Base64 URI
 *
 * 关键：使用 Double 精度计算（与 fft.js 的 Float64 一致）
 */
object ShazamFingerprint {

    // ====== Hanning 窗（2048点）======
    // 与 hanning.js 完全一致：0.5*(1-cos(2π*(i+1)/N))，N=2048，Double 精度
    private val HANNING_MATRIX = DoubleArray(2048) { i ->
        0.5 * (1.0 - cos(2.0 * Math.PI * (i + 1) / 2048))
    }

    // ====== 调试：最近一次指纹的峰值数 ======
    @Volatile
    private var lastPeakCount: Int = 0

    fun getLastPeakCount(): Int = lastPeakCount

    // ====== 辅助函数 ======
    private fun pyMod(a: Int, b: Int): Int {
        val r = a % b
        return if (r >= 0) r else b + r
    }

    // ====== 环形缓冲区 ======
    private class RingBufferFloat(private val size: Int, defaultValue: Double) {
        val list = DoubleArray(size) { defaultValue }
        var position = 0

        fun splice(batch: DoubleArray) {
            for (i in batch.indices) {
                list[(position + i) % size] = batch[i]
            }
            position = (position + batch.size) % size
        }
    }

    private class RingBufferArray(private val size: Int, private val arraySize: Int) {
        val list = Array(size) { DoubleArray(arraySize) }
        var position = 0

        operator fun get(index: Int): DoubleArray {
            return list[pyMod(position + index, size)]
        }
    }

    // ====== 频率段枚举 ======
    private enum class FrequencyBand(val value: Int) {
        BAND_0_250(-1),
        BAND_250_520(0),
        BAND_520_1450(1),
        BAND_1450_3500(2),
        BAND_3500_5500(3)
    }

    private enum class SampleRate(val value: Int) {
        RATE_8000(1),
        RATE_11025(2),
        RATE_16000(3),
        RATE_32000(4),
        RATE_44100(5),
        RATE_48000(6)
    }

    // ====== 频率峰值 ======
    private data class FrequencyPeak(
        val fftPassNumber: Int,
        val peakMagnitude: Int,
        val correctedPeakFrequencyBin: Int,
        val sampleRateHz: Int
    )

    // ====== 解码消息（签名）======
    private class DecodedMessage {
        var sampleRateHz: Int = 0
        var numberSamples: Int = 0
        val frequencyBandToSoundPeaks = mutableMapOf<FrequencyBand, MutableList<FrequencyPeak>>()

        fun encodeToUri(): String {
            val bin = encodeToBinary()
            return "data:audio/vnd.shazam.sig;base64," +
                android.util.Base64.encodeToString(bin, android.util.Base64.NO_WRAP)
        }

        private fun encodeToBinary(): ByteArray {
            val sampleRateId = when (sampleRateHz) {
                8000 -> SampleRate.RATE_8000
                11025 -> SampleRate.RATE_11025
                16000 -> SampleRate.RATE_16000
                32000 -> SampleRate.RATE_32000
                44100 -> SampleRate.RATE_44100
                48000 -> SampleRate.RATE_48000
                else -> SampleRate.RATE_16000
            }.value

            // 1. 先构建 contents（峰值数据）
            val contents = mutableListOf<Byte>()
            val sortedBands = frequencyBandToSoundPeaks.entries.sortedBy { it.key.value }

            for ((band, peaks) in sortedBands) {
                val peaksBuf = mutableListOf<Byte>()
                var fftPassNumber = 0

                for (peak in peaks) {
                    if (peak.fftPassNumber < fftPassNumber) continue

                    if (peak.fftPassNumber - fftPassNumber >= 0xFF) {
                        peaksBuf.add(0xFF.toByte())
                        peaksBuf.addAll(writeInt32(peak.fftPassNumber))
                        fftPassNumber = peak.fftPassNumber
                    }

                    peaksBuf.add((peak.fftPassNumber - fftPassNumber).toByte())
                    peaksBuf.addAll(writeInt16(peak.peakMagnitude - 1))
                    peaksBuf.addAll(writeInt16(peak.correctedPeakFrequencyBin - 1))
                    fftPassNumber = peak.fftPassNumber
                }

                contents.addAll(writeInt32(0x60030040 + band.value))
                contents.addAll(writeInt32(peaksBuf.size))
                contents.addAll(peaksBuf)

                val paddingCount = 4 - (peaksBuf.size % 4)
                if (paddingCount < 4) {
                    repeat(paddingCount) { contents.add(0) }
                }
            }

            // 2. 计算 sizeMinusHeader
            val sizeMinusHeader = contents.size + 8

            // 3. 用正确的 sizeMinusHeader 构建 header（关键修复！）
            //    之前 sizeMinusHeader=0 占位 → CRC32 基于错误数据计算 → API 拒绝
            val headerBuf = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
            headerBuf.putInt(0xCAFE2580.toInt()) // magic1
            headerBuf.putInt(0) // crc32 (占位)
            headerBuf.putInt(sizeMinusHeader) // sizeMinusHeader（正确值！）
            headerBuf.putInt(0x94119C00.toInt()) // magic2
            headerBuf.putInt(0)
            headerBuf.putInt(0)
            headerBuf.putInt(0)
            headerBuf.putInt(sampleRateId shl 27) // shiftedSampleRateId
            headerBuf.putInt(0)
            headerBuf.putInt(0)
            headerBuf.putInt((numberSamples + sampleRateHz * 0.24).roundToInt()) // numberSamplesPlusDividedSampleRate
            headerBuf.putInt((15 shl 19) + 0x40000) // fixedValue

            // 4. 构建 buf（header 已包含正确的 sizeMinusHeader）
            val buf = mutableListOf<Byte>()
            buf.addAll(headerBuf.array().toList())
            buf.addAll(writeInt32(0x40000000))
            buf.addAll(writeInt32(sizeMinusHeader))
            buf.addAll(contents)

            // 5. 计算 CRC32（从第8字节到末尾，此时 sizeMinusHeader 已是正确值）
            val crcData = buf.subList(8, buf.size).toByteArray()
            val crc = crc32(crcData)

            // 6. 只回填 CRC32（sizeMinusHeader 已在 header 中正确设置，无需再写）
            val finalBuf = ByteBuffer.allocate(buf.size).order(ByteOrder.LITTLE_ENDIAN)
            finalBuf.put(buf.toByteArray())
            finalBuf.putInt(4, crc)

            val result = ByteArray(buf.size)
            finalBuf.position(0)
            finalBuf.get(result)
            return result
        }
    }

    // ====== 签名生成器 ======
    private class SignatureGenerator {
        private lateinit var ringBufferOfSamples: RingBufferFloat
        private lateinit var fftOutputs: RingBufferArray
        private lateinit var spreadFFTsOutput: RingBufferArray
        private lateinit var nextSignature: DecodedMessage
        private var numSpreadFftsDone = 0

        init {
            initFields()
        }

        private fun initFields() {
            ringBufferOfSamples = RingBufferFloat(2048, 0.0)
            fftOutputs = RingBufferArray(256, 1025)
            spreadFFTsOutput = RingBufferArray(256, 1025)
            nextSignature = DecodedMessage()
            nextSignature.sampleRateHz = 16000
            nextSignature.numberSamples = 0
            nextSignature.frequencyBandToSoundPeaks.clear()
            numSpreadFftsDone = 0
        }

        fun getSignature(s16leMonoSamples: ShortArray): DecodedMessage {
            val maxSamples = 12 * 16000
            val samples = if (s16leMonoSamples.size > maxSamples) {
                val middle = s16leMonoSamples.size / 2
                s16leMonoSamples.copyOfRange(middle - 6 * 16000, middle + 6 * 16000)
            } else {
                s16leMonoSamples.copyOfRange(0, s16leMonoSamples.size)
            }

            nextSignature.numberSamples += samples.size

            for (i in samples.indices step 128) {
                val end = minOf(i + 128, samples.size)
                val batch = DoubleArray(end - i) { j -> samples[i + j].toDouble() }
                doFFT(batch)
                doPeakSpreading()
                numSpreadFftsDone++
                if (numSpreadFftsDone >= 46) {
                    doPeakRecognition()
                }
            }

            val result = nextSignature
            initFields()
            return result
        }

        private fun doFFT(batchOf128: DoubleArray) {
            ringBufferOfSamples.splice(batchOf128)

            // 构造 excerpt：从 position 开始环形读取 2048 个样本，乘以 Hanning 窗（Double 精度）
            val excerpt = DoubleArray(2048) { i ->
                val idx = (ringBufferOfSamples.position + i) % 2048
                ringBufferOfSamples.list[idx] * HANNING_MATRIX[i]
            }

            val fft = FFT(2048)
            val out = fft.createComplexArray()
            fft.realTransform(out, excerpt)

            // 取前 2050 个值（1025 个复数），转为 Double 精度的能量值
            val results = fftOutputs.list[pyMod(fftOutputs.position, fftOutputs.list.size)]
            fftOutputs.position = (fftOutputs.position + 1) % fftOutputs.list.size

            for (i in 0 until 2050 step 2) {
                val re = out[i]
                val im = out[i + 1]
                val e = ((re * re) + (im * im)) / (1 shl 17)
                results[i / 2] = max(1e-10, e)
            }
        }

        private fun doPeakSpreading() {
            val originLastFFT = fftOutputs[-1]
            val spreadLastFFT = spreadFFTsOutput[0]
            originLastFFT.copyInto(spreadLastFFT)

            for (position in 0..1022) {
                var maxVal = spreadLastFFT[position]
                for (k in position until minOf(position + 3, 1025)) {
                    if (spreadLastFFT[k] > maxVal) maxVal = spreadLastFFT[k]
                }
                spreadLastFFT[position] = maxVal
            }

            for (position in 0..1024) {
                for (formerFftNum in listOf(-1, -3, -6)) {
                    val formerFftOutput = spreadFFTsOutput[formerFftNum]
                    formerFftOutput[position] = max(formerFftOutput[position], spreadLastFFT[position])
                }
            }

            spreadFFTsOutput.position = (spreadFFTsOutput.position + 1) % spreadFFTsOutput.list.size
        }

        private fun doPeakRecognition() {
            val fftMinus46 = fftOutputs[-46]
            val fftMinus49 = spreadFFTsOutput[-49]

            for (binPosition in 10..1014) {
                if (fftMinus46[binPosition] >= 1.0 / 64.0 && fftMinus46[binPosition] >= fftMinus49[binPosition - 1]) {
                    var maxNeighborInFftMinus49 = 0.0
                    val neighborOffsets = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
                    for (offset in neighborOffsets) {
                        val candidate = fftMinus49[binPosition + offset]
                        if (candidate > maxNeighborInFftMinus49) maxNeighborInFftMinus49 = candidate
                    }

                    if (fftMinus46[binPosition] > maxNeighborInFftMinus49) {
                        var maxNeighborInOtherAdjacentFFTs = maxNeighborInFftMinus49
                        val otherOffsets = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)
                        for (offset in otherOffsets) {
                            val candidate = spreadFFTsOutput[offset][binPosition - 1]
                            if (candidate > maxNeighborInOtherAdjacentFFTs) maxNeighborInOtherAdjacentFFTs = candidate
                        }

                        if (fftMinus46[binPosition] > maxNeighborInOtherAdjacentFFTs) {
                            val fftNumber = numSpreadFftsDone - 46

                            // 保留 Double 精度用于 variation 计算（与 JS 一致）
                            val peakMagnitude = ln(maxOf(1.0 / 64.0, fftMinus46[binPosition])) * 1477.3 + 6144
                            val peakMagnitudeBefore = ln(maxOf(1.0 / 64.0, fftMinus46[binPosition - 1])) * 1477.3 + 6144
                            val peakMagnitudeAfter = ln(maxOf(1.0 / 64.0, fftMinus46[binPosition + 1])) * 1477.3 + 6144

                            val peakVariation1 = peakMagnitude * 2.0 - peakMagnitudeBefore - peakMagnitudeAfter
                            val peakVariation2 = (peakMagnitudeAfter - peakMagnitudeBefore) * 32.0 / peakVariation1

                            // JS: ((binPosition * 64 + peakVariation2) & 0xFFFF) >>> 0
                            // 先加 float，再截断为 int，再 mask
                            val correctedPeakFrequencyBin = ((binPosition * 64 + peakVariation2).toInt()) and 0xFFFF

                            val frequencyHz = correctedPeakFrequencyBin * (16000.0 / 2.0 / 1024.0 / 64.0)
                            val band = when {
                                frequencyHz < 250 -> FrequencyBand.BAND_0_250
                                frequencyHz < 520 -> FrequencyBand.BAND_250_520
                                frequencyHz < 1450 -> FrequencyBand.BAND_520_1450
                                frequencyHz < 3500 -> FrequencyBand.BAND_1450_3500
                                frequencyHz <= 5500 -> FrequencyBand.BAND_3500_5500
                                else -> FrequencyBand.BAND_0_250
                            }

                            if (band == FrequencyBand.BAND_0_250) continue

                            // JS: Math.round(peakMagnitude) & 0xFFFF
                            nextSignature.frequencyBandToSoundPeaks.getOrPut(band) { mutableListOf() }.add(
                                FrequencyPeak(
                                    fftPassNumber = fftNumber,
                                    peakMagnitude = peakMagnitude.roundToInt() and 0xFFFF,
                                    correctedPeakFrequencyBin = correctedPeakFrequencyBin,
                                    sampleRateHz = 16000
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    // ====== 二进制写入辅助函数 ======
    private fun writeInt32(value: Int): List<Byte> {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(value)
        return buf.array().toList()
    }

    private fun writeInt16(value: Int): List<Byte> {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(value.toShort())
        return buf.array().toList()
    }

    // ====== CRC32 ======
    private fun crc32(data: ByteArray): Int {
        val crcTable = IntArray(256) { n ->
            var c = n
            repeat(8) {
                c = if ((c and 1) != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1)
            }
            c
        }
        var crc = 0.inv()
        for (b in data) {
            crc = (crc ushr 8) xor crcTable[(crc xor (b.toInt() and 0xFF)) and 0xFF]
        }
        return crc.inv()
    }

    // ====== 公共 API：从 WAV 文件生成指纹 URI ======
    fun generateSignature(wavFile: File): String? {
        return try {
            val samples = readWavPcm16(wavFile)
            if (samples == null || samples.isEmpty()) return null
            val generator = SignatureGenerator()
            val signature = generator.getSignature(samples)
            lastPeakCount = signature.frequencyBandToSoundPeaks.values.sumOf { it.size }
            signature.encodeToUri()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 读取 WAV 文件的 PCM16 样本数据
     */
    private fun readWavPcm16(wavFile: File): ShortArray? {
        val bytes = wavFile.readBytes()
        if (bytes.size < 44) return null

        // 检查 RIFF/WAVE 头
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()) return null

        // 从 fmt 块获取采样率和声道数
        val sampleRate = ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val channels = ByteBuffer.wrap(bytes, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val bitsPerSample = ByteBuffer.wrap(bytes, 34, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

        // 找到 data 块
        var offset = 36
        while (offset < bytes.size - 8) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (chunkId == "data") {
                offset += 8
                break
            }
            offset += 8 + chunkSize
        }

        if (offset >= bytes.size) return null

        val dataSize = bytes.size - offset
        val numSamples = dataSize / (bitsPerSample / 8)

        val buffer = ByteBuffer.wrap(bytes, offset, dataSize).order(ByteOrder.LITTLE_ENDIAN)

        return if (channels == 1) {
            // 单声道
            ShortArray(numSamples) { buffer.short }
        } else {
            // 多声道：取平均值转单声道
            val frameCount = numSamples / channels
            ShortArray(frameCount) {
                var sum = 0
                repeat(channels) { sum += buffer.short.toInt() }
                (sum / channels).toShort()
            }
        }
    }
}
