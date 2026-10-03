package com.example.filemanager

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.io.FileOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.jar.JarFile
import java.util.zip.ZipFile

/**
 * APK 工具：反编译查看信息、签名、去除签名校验
 */
object ApkUtils {

    data class ApkInfo(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val minSdk: Int,
        val targetSdk: Int,
        val permissions: List<String>,
        val activities: List<String>,
        val services: List<String>,
        val receivers: List<String>,
        val providers: List<String>,
        val label: String,
        val fileSize: Long,
        val filePath: String
    )

    /**
     * 从 APK 文件获取信息（反编译基础信息）
     */
    fun getApkInfo(context: Context, apkFile: File): ApkInfo? {
        return try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath,
                PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_PERMISSIONS or
                PackageManager.GET_META_DATA)

            if (packageInfo == null) return null

            packageInfo.applicationInfo?.sourceDir = apkFile.absolutePath
            packageInfo.applicationInfo?.publicSourceDir = apkFile.absolutePath

            val appInfo = packageInfo.applicationInfo
            val label = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                appInfo?.packageName ?: "未知"
            }

            ApkInfo(
                packageName = packageInfo.packageName ?: "未知",
                versionName = packageInfo.versionName ?: "未知",
                versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong(),
                minSdk = appInfo?.minSdkVersion ?: 0,
                targetSdk = appInfo?.targetSdkVersion ?: 0,
                permissions = packageInfo.requestedPermissions?.toList() ?: emptyList(),
                activities = packageInfo.activities?.map { it.name } ?: emptyList(),
                services = packageInfo.services?.map { it.name } ?: emptyList(),
                receivers = packageInfo.receivers?.map { it.name } ?: emptyList(),
                providers = packageInfo.providers?.map { it.name } ?: emptyList(),
                label = label,
                fileSize = apkFile.length(),
                filePath = apkFile.absolutePath
            )
        } catch (e: Exception) {
            null
        }
    }

    data class ApkEntry(
        val name: String,
        val size: Long,
        val compressedSize: Long,
        val isDirectory: Boolean,
        val time: Long
    )

    fun listApkContents(apkFile: File): List<ApkEntry> {
        val result = mutableListOf<ApkEntry>()
        try {
            ZipFile(apkFile).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    result.add(ApkEntry(
                        name = entry.name,
                        size = entry.size,
                        compressedSize = entry.compressedSize,
                        isDirectory = entry.isDirectory,
                        time = entry.time
                    ))
                }
            }
        } catch (e: Exception) {
        }
        return result.sortedBy { it.name }
    }

    /**
     * 去除签名校验
     */
    fun removeSignature(apkFile: File, outputFile: File): Boolean {
        return try {
            ZipFile(apkFile).use { zf ->
                FileOutputStream(outputFile).use { fos ->
                    java.util.zip.ZipOutputStream(fos).use { zos ->
                        val entries = zf.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.name.startsWith("META-INF/") &&
                                (entry.name.endsWith(".SF") ||
                                 entry.name.endsWith(".RSA") ||
                                 entry.name.endsWith(".DSA") ||
                                 entry.name.endsWith(".EC") ||
                                 entry.name == "META-INF/MANIFEST.MF")) {
                                continue
                            }
                            zos.putNextEntry(java.util.zip.ZipEntry(entry.name))
                            if (!entry.isDirectory) {
                                zf.getInputStream(entry).use { it.copyTo(zos) }
                            }
                            zos.closeEntry()
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isSigned(apkFile: File): Boolean {
        return try {
            JarFile(apkFile).use { jf ->
                val entries = jf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("META-INF/") &&
                        (entry.name.endsWith(".SF") ||
                         entry.name.endsWith(".RSA") ||
                         entry.name.endsWith(".DSA"))) {
                        return true
                    }
                }
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 对APK进行签名（v1 JAR签名）
     */
    fun signApk(apkFile: File, outputFile: File): Boolean {
        return try {
            // 先去除旧签名
            val unsignedFile = File(outputFile.parentFile, "unsigned_temp.apk")
            removeSignature(apkFile, unsignedFile)

            // 生成密钥对
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048)
            val keyPair = keyPairGenerator.generateKeyPair()

            // 生成自签名证书
            val cert = X509CertGenerator.generate(
                keyPair,
                "CN=FileManager, OU=Dev, O=FileManager, C=CN",
                System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000,
                System.currentTimeMillis() + 3650L * 24 * 60 * 60 * 1000
            )

            // 执行 JAR 签名
            signJarV1(unsignedFile, outputFile, keyPair, cert)

            unsignedFile.delete()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun signJarV1(inputFile: File, outputFile: File, keyPair: KeyPair, cert: X509Certificate) {
        // 1. 生成 MANIFEST.MF
        val manifest = StringBuilder()
        manifest.append("Manifest-Version: 1.0\n")
        manifest.append("Created-By: FileManager 1.3\n\n")

        val fileEntries = mutableMapOf<String, ByteArray>()
        ZipFile(inputFile).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && !entry.name.startsWith("META-INF/")) {
                    val data = zf.getInputStream(entry).readBytes()
                    fileEntries[entry.name] = data

                    val sha1 = MessageDigest.getInstance("SHA-1").digest(data)
                    val base64Sha1 = android.util.Base64.encodeToString(sha1, android.util.Base64.NO_WRAP)

                    manifest.append("Name: ${entry.name}\n")
                    manifest.append("SHA-1-Digest: $base64Sha1\n\n")
                }
            }
        }

        // 2. 生成 CERT.SF
        val manifestBytes = manifest.toString().toByteArray()
        val manifestSha1 = MessageDigest.getInstance("SHA-1").digest(manifestBytes)

        val sf = StringBuilder()
        sf.append("Signature-Version: 1.0\n")
        sf.append("Created-By: FileManager 1.3\n")
        sf.append("SHA-1-Digest-Manifest: ${android.util.Base64.encodeToString(manifestSha1, android.util.Base64.NO_WRAP)}\n\n")

        // 3. 签名 CERT.SF
        val sfBytes = sf.toString().toByteArray()
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(sfBytes)
        val sigBytes = signer.sign()

        // 4. 生成 PKCS#7 签名块
        val pkcs7 = Pkcs7Builder.build(sigBytes, cert)

        // 5. 写入签名后的 APK
        FileOutputStream(outputFile).use { fos ->
            java.util.zip.ZipOutputStream(fos).use { zos ->
                // 写签名文件
                zos.putNextEntry(java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
                zos.write(manifestBytes)
                zos.closeEntry()

                zos.putNextEntry(java.util.zip.ZipEntry("META-INF/CERT.SF"))
                zos.write(sfBytes)
                zos.closeEntry()

                zos.putNextEntry(java.util.zip.ZipEntry("META-INF/CERT.RSA"))
                zos.write(pkcs7)
                zos.closeEntry()

                // 写原始文件
                fileEntries.forEach { (name, data) ->
                    zos.putNextEntry(java.util.zip.ZipEntry(name))
                    zos.write(data)
                    zos.closeEntry()
                }
            }
        }
    }
}
