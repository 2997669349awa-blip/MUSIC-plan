package com.example.musicplugin

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * v1.2.2：签名校验 + 加固
 *
 * 启动时检查 APK 签名是否与原版一致，
 * 若被二次打包/重新签名则返回 false，由调用方弹窗并退出。
 *
 * 原版签名 SHA-256 指纹（release.keystore / alias=filemanager）已分散存放，
 * 运行时拼接比对，增加逆向破解难度。
 */
object SignatureVerifier {

    // 原版签名 SHA-256，分成 4 段存放，避免明文被直接搜索
    private val P1 = "B1:D7:DC:2F:C0:65:2E:4A:3F:92:8E:9B:0C:FB:2C:B4"
    private val P2 = "4E:24:DA:88:CF:85:C2:D4:1C:16:99:B1:80:78:64:4F"

    /**
     * 校验当前 APK 签名是否为原版
     * @return true 原版；false 签名异常（被重打包）
     */
    fun isOriginalSignature(context: Context): Boolean {
        return try {
            val expected = (P1 + ":" + P2).lowercase().replace(":", "")
            val actual = getSignatureSha256(context)
            actual != null && actual == expected
        } catch (e: Throwable) {
            // 读取失败也视为异常，阻止运行
            false
        }
    }

    private fun getSignatureSha256(context: Context): String? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Android 9+ 必须用 getSigningKeyHistory，且需要 GET_SIGNING_CERTIFICATES 权限（系统授予）
            val pkgInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            pkgInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            val pkgInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
            pkgInfo.signatures
        } ?: return null

        if (signatures.isEmpty()) return null
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(signatures[0].toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
