package com.example.musicplugin

import kotlin.math.cos
import kotlin.math.sin

/**
 * Cooley-Tukey FFT（基2时域抽取）— 使用 Double 精度计算
 * N 必须是 2 的幂次
 * 与 fft.js (Node.js) 的 Float64 计算保持一致
 */
class FFT(private val n: Int) {

    private val cosTable: DoubleArray
    private val sinTable: DoubleArray

    init {
        require(n > 0 && (n and (n - 1)) == 0) { "N must be power of 2" }
        cosTable = DoubleArray(n / 2)
        sinTable = DoubleArray(n / 2)
        for (i in 0 until n / 2) {
            val angle = -2.0 * Math.PI * i / n
            cosTable[i] = cos(angle)
            sinTable[i] = sin(angle)
        }
    }

    fun createComplexArray(): DoubleArray = DoubleArray(n * 2)

    /**
     * 实数 FFT：input 长度 = n，out 长度 = 2*n（交错复数）
     */
    fun realTransform(out: DoubleArray, input: DoubleArray) {
        val real = input.copyOf()
        val imag = DoubleArray(n)
        transform(real, imag)
        for (i in 0 until n) {
            out[i * 2] = real[i]
            out[i * 2 + 1] = imag[i]
        }
    }

    private fun transform(real: DoubleArray, imag: DoubleArray) {
        bitReverseCopy(real, imag)
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            for (i in 0 until n step len) {
                for (j in 0 until halfLen) {
                    val idx = j * n / len
                    val c = cosTable[idx]
                    val s = sinTable[idx]

                    val tr = real[i + j + halfLen] * c - imag[i + j + halfLen] * s
                    val ti = real[i + j + halfLen] * s + imag[i + j + halfLen] * c

                    val ur = real[i + j]
                    val ui = imag[i + j]

                    real[i + j] = ur + tr
                    imag[i + j] = ui + ti
                    real[i + j + halfLen] = ur - tr
                    imag[i + j + halfLen] = ui - ti
                }
            }
            len *= 2
        }
    }

    private fun bitReverseCopy(real: DoubleArray, imag: DoubleArray) {
        val bits = 32 - Integer.numberOfLeadingZeros(n - 1)
        for (i in 0 until n) {
            val j = Integer.reverse(i) ushr (32 - bits)
            if (j > i) {
                var t = real[i]; real[i] = real[j]; real[j] = t
                t = imag[i]; imag[i] = imag[j]; imag[j] = t
            }
        }
    }
}
