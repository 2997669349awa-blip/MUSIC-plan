package com.example.filemanager

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Minimal ASN.1 DER encoder.
 */
object DerEncoder {

    fun writeSequence(content: ByteArray): ByteArray = writeTLV(0x30, content)

    fun writeSet(content: ByteArray): ByteArray = writeTLV(0x31, content)

    fun writeInteger(value: BigInteger): ByteArray {
        // BigInteger.toByteArray() returns the minimal two's-complement
        // representation, which is exactly what DER INTEGER requires
        // (a leading 0x00 is included automatically for positive values
        // whose high bit is set).
        return writeTLV(0x02, value.toByteArray())
    }

    fun writeInteger(value: Long): ByteArray = writeInteger(BigInteger.valueOf(value))

    fun writeOID(oidBytes: ByteArray): ByteArray = writeTLV(0x06, oidBytes)

    fun writeUTCTime(timeString: String): ByteArray =
        writeTLV(0x17, timeString.toByteArray(Charsets.US_ASCII))

    fun writeBitString(data: ByteArray): ByteArray {
        val content = ByteArray(data.size + 1)
        content[0] = 0 // 0 unused bits
        System.arraycopy(data, 0, content, 1, data.size)
        return writeTLV(0x03, content)
    }

    fun writeOctetString(data: ByteArray): ByteArray = writeTLV(0x04, data)

    fun writeExplicit(tag: Int, content: ByteArray): ByteArray =
        writeTLV(0xA0 or tag, content)

    fun writeImplicit(tag: Int, content: ByteArray): ByteArray =
        writeTLV(0xA0 or tag, content)

    fun writePrintableString(s: String): ByteArray =
        writeTLV(0x13, s.toByteArray(Charsets.US_ASCII))

    fun writeUTF8String(s: String): ByteArray =
        writeTLV(0x0C, s.toByteArray(Charsets.UTF_8))

    fun writeNull(): ByteArray = byteArrayOf(0x05, 0x00)

    /**
     * Encodes an OID given as a dotted-decimal string (e.g. "1.2.840.113549.1.1.5")
     * into its DER content bytes (base-128 with continuation bits).
     */
    fun encodeOID(oidString: String): ByteArray {
        val parts = oidString.split(".").map { it.toLong() }
        require(parts.size >= 2) { "OID must have at least 2 components" }
        val out = ByteArrayOutputStream()
        out.write(encodeSubIdentifier(parts[0] * 40 + parts[1]))
        for (i in 2 until parts.size) {
            out.write(encodeSubIdentifier(parts[i]))
        }
        return out.toByteArray()
    }

    private fun encodeSubIdentifier(value: Long): ByteArray {
        if (value == 0L) return byteArrayOf(0)
        val tmp = mutableListOf<Byte>()
        var x = value
        while (x > 0) {
            tmp.add(0, (x and 0x7F).toByte())
            x = x shr 7
        }
        // Set the high (continuation) bit on every byte except the last.
        for (i in 0 until tmp.size - 1) {
            tmp[i] = (tmp[i].toInt() or 0x80).toByte()
        }
        return tmp.toByteArray()
    }

    private fun encodeLength(length: Int): ByteArray {
        return if (length < 0x80) {
            byteArrayOf(length.toByte())
        } else {
            val bytes = mutableListOf<Byte>()
            var tmp = length
            while (tmp > 0) {
                bytes.add(0, (tmp and 0xFF).toByte())
                tmp = tmp shr 8
            }
            val out = ByteArray(bytes.size + 1)
            out[0] = (0x80 or bytes.size).toByte()
            for (i in bytes.indices) {
                out[i + 1] = bytes[i]
            }
            out
        }
    }

    private fun writeTLV(tag: Int, content: ByteArray): ByteArray {
        val len = encodeLength(content.size)
        val out = ByteArray(1 + len.size + content.size)
        out[0] = tag.toByte()
        System.arraycopy(len, 0, out, 1, len.size)
        System.arraycopy(content, 0, out, 1 + len.size, content.size)
        return out
    }
}
