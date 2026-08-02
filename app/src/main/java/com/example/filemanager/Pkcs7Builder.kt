package com.example.filemanager

import java.security.cert.X509Certificate

/**
 * Builds a PKCS#7 SignedData structure (ContentInfo) for a signature block,
 * using only [DerEncoder]. The certificate bytes and issuer name are taken
 * already DER-encoded from the supplied [X509Certificate] and included raw.
 */
object Pkcs7Builder {

    private const val SIGNED_DATA_OID = "1.2.840.113549.1.7.2"
    private const val SHA256_OID = "2.16.840.1.101.3.4.2.1"
    private const val SHA256_WITH_RSA_OID = "1.2.840.113549.1.1.11"

    fun build(signature: ByteArray, cert: X509Certificate): ByteArray {
        // Already DER-encoded by the platform; include raw.
        val certBytes = cert.getEncoded()
        val issuerBytes = cert.issuerX500Principal.encoded
        val serial = cert.serialNumber

        // digestAlgorithms SET { SEQUENCE { OID sha256 } }
        val digestAlgorithms = DerEncoder.writeSet(
            DerEncoder.writeSequence(
                DerEncoder.writeOID(DerEncoder.encodeOID(SHA256_OID))
            )
        )

        // certificates [0] IMPLICIT (raw certificate bytes)
        val certificates = DerEncoder.writeImplicit(0, certBytes)

        // issuerAndSerialNumber SEQUENCE { issuer Name, serialNumber INTEGER }
        val issuerAndSerial = DerEncoder.writeSequence(
            issuerBytes + DerEncoder.writeInteger(serial)
        )

        // SignerInfo SEQUENCE { version, issuerAndSerial, digestAlg, sigAlg, encryptedDigest }
        val signerInfo = DerEncoder.writeSequence(
            DerEncoder.writeInteger(1) +
                issuerAndSerial +
                DerEncoder.writeSequence(DerEncoder.writeOID(DerEncoder.encodeOID(SHA256_OID))) +
                DerEncoder.writeSequence(DerEncoder.writeOID(DerEncoder.encodeOID(SHA256_WITH_RSA_OID))) +
                DerEncoder.writeOctetString(signature)
        )

        val signerInfos = DerEncoder.writeSet(signerInfo)

        // SignedData SEQUENCE { version, digestAlgorithms, certificates, signerInfos }
        val signedData = DerEncoder.writeSequence(
            DerEncoder.writeInteger(1) +
                digestAlgorithms +
                certificates +
                signerInfos
        )

        // ContentInfo SEQUENCE { OID signedData, [0] EXPLICIT signedData }
        return DerEncoder.writeSequence(
            DerEncoder.writeOID(DerEncoder.encodeOID(SIGNED_DATA_OID)) +
                DerEncoder.writeExplicit(0, signedData)
        )
    }
}
