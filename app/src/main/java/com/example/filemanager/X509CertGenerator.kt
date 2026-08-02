package com.example.filemanager

import java.math.BigInteger
import java.security.KeyPair
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Generates a self-signed X.509 v3 certificate using only java.security.*
 * (no sun.security / BouncyCastle). Built on top of [DerEncoder].
 */
object X509CertGenerator {

    private const val SHA256_WITH_RSA_OID = "1.2.840.113549.1.1.11"
    private const val RSA_ENCRYPTION_OID = "1.2.840.113549.1.1.1"

    fun generate(keyPair: KeyPair, dn: String, notBefore: Long, notAfter: Long): X509Certificate {
        // signature: AlgorithmIdentifier { OID sha256WithRSA }
        val signatureAlg = DerEncoder.writeSequence(
            DerEncoder.writeOID(DerEncoder.encodeOID(SHA256_WITH_RSA_OID))
        )

        // issuer / subject (self-signed)
        val name = buildName(dn)

        // validity: SEQUENCE { UTCTime(notBefore), UTCTime(notAfter) }
        val validity = DerEncoder.writeSequence(
            DerEncoder.writeUTCTime(toUTCTime(notBefore)) +
                DerEncoder.writeUTCTime(toUTCTime(notAfter))
        )

        // subjectPublicKeyInfo: SEQUENCE { AlgorithmIdentifier, BIT STRING(publicKey) }
        val pubKey = keyPair.public as RSAPublicKey
        val rsaPubKey = DerEncoder.writeSequence(
            DerEncoder.writeInteger(pubKey.modulus) +
                DerEncoder.writeInteger(pubKey.publicExponent)
        )
        val spki = DerEncoder.writeSequence(
            DerEncoder.writeSequence(
                DerEncoder.writeOID(DerEncoder.encodeOID(RSA_ENCRYPTION_OID)) +
                    DerEncoder.writeNull()
            ) +
                DerEncoder.writeBitString(rsaPubKey)
        )

        // version: EXPLICIT [0] INTEGER 2 (v3)
        val version = DerEncoder.writeExplicit(0, DerEncoder.writeInteger(2))

        // serialNumber: INTEGER (current time)
        val serial = DerEncoder.writeInteger(BigInteger.valueOf(System.currentTimeMillis()))

        // TBSCertificate
        val tbs = DerEncoder.writeSequence(
            version + serial + signatureAlg + name + validity + name + spki
        )

        // sign TBS with SHA256withRSA
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(tbs)
        val signatureBytes = signer.sign()

        // Certificate: SEQUENCE { tbs, signatureAlgorithm, BIT STRING(signature) }
        val certDer = DerEncoder.writeSequence(
            tbs + signatureAlg + DerEncoder.writeBitString(signatureBytes)
        )

        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(certDer.inputStream()) as X509Certificate
    }

    /**
     * Builds a Name (SEQUENCE OF RDN) from a DN string like
     * "CN=xxx, OU=xxx, O=xxx, C=xx". Each RDN is a SET OF AttributeTypeAndValue,
     * where AttributeTypeAndValue is SEQUENCE { OID, UTF8String }.
     */
    private fun buildName(dn: String): ByteArray {
        var nameContent = ByteArray(0)
        val rdns = dn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        for (rdn in rdns) {
            val eq = rdn.indexOf('=')
            if (eq < 0) continue
            val key = rdn.substring(0, eq).trim().uppercase(Locale.US)
            val value = rdn.substring(eq + 1).trim()
            val oid = when (key) {
                "CN" -> "2.5.4.3"
                "OU" -> "2.5.4.11"
                "O" -> "2.5.4.10"
                "C" -> "2.5.4.6"
                else -> null
            } ?: continue
            val atv = DerEncoder.writeSequence(
                DerEncoder.writeOID(DerEncoder.encodeOID(oid)) +
                    DerEncoder.writeUTF8String(value)
            )
            nameContent += DerEncoder.writeSet(atv)
        }
        return DerEncoder.writeSequence(nameContent)
    }

    private fun toUTCTime(epochMillis: Long): String {
        val sdf = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMillis))
    }
}
