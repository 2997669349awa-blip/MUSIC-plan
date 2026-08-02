package com.example.filemanager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * ADB权限工具（v1.6.0 重写）
 *
 * 真正集成 Shizuku API：
 * 1. 检测 Shizuku 服务是否运行（Shizuku.pingBinder()）
 * 2. 检测本应用是否已被 Shizuku 授权（checkSelfPermission）
 * 3. 申请权限（requestPermission）
 * 4. 通过 Shizuku 的 IProcessService 以 shell 身份执行特权命令
 *
 * 旧的 isAdbAvailable() 仅尝试 ls /data，无法区分 Shizuku 是否授权本应用，
 * 因此用户即便装了 Shizuku 也"申请不到 ADB 权限"。本次重写彻底解决该问题。
 */
object AdbUtils {

    data class AdbApp(
        val packageName: String,
        val name: String,
        val description: String,
        val marketUrl: String
    )

    // 已知的ADB权限管理应用（moe.shizuku.privileged.api 是 Shizuku 当前正确包名）
    val KNOWN_ADB_APPS = listOf(
        AdbApp(
            "moe.shizuku.privileged.api",
            "Shizuku",
            "通过ADB运行的服务，允许其他应用以ADB权限执行操作",
            "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"
        ),
        AdbApp(
            "moe.lz.luca.dhizuku",
            "Dhizuku",
            "类似Shizuku的ADB权限管理工具",
            "https://play.google.com/store/apps/details?id=moe.lz.luca.dhizuku"
        ),
        AdbApp(
            "rikka.shizuku",
            "Shizuku (旧版包名)",
            "通过ADB运行的服务，允许其他应用以ADB权限执行操作",
            "https://play.google.com/store/apps/details?id=rikka.shizuku"
        )
    )

    // 系统目录
    val SYSTEM_DIRS = listOf("/data", "/system", "/dev", "/proc", "/sys", "/sbin", "/vendor", "/etc")

    /**
     * 检测已安装的ADB权限管理应用
     */
    fun detectInstalledAdbApps(context: Context): List<AdbApp> {
        val installed = mutableListOf<AdbApp>()
        val pm = context.packageManager
        for (app in KNOWN_ADB_APPS) {
            try {
                pm.getPackageInfo(app.packageName, 0)
                installed.add(app)
            } catch (e: PackageManager.NameNotFoundException) {
                // 未安装
            }
        }
        return installed
    }

    // ==================== Shizuku API 真实状态检测 ====================

    /**
     * Shizuku 服务是否运行（即 Shizuku 应用已启动服务）
     * 这是关键：用户即使安装了 Shizuku，如果没启动服务，binder 也是不可用的。
     */
    fun isShizukuRunning(): Boolean {
        return try {
            // ShizukuProvider 未注册时会抛出 SecurityException，统一视为不可用
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 本应用是否已被 Shizuku 授权
     * 即使 Shizuku 服务运行，应用也必须在 Shizuku 中被用户手动授权
     */
    fun isShizukuAuthorized(): Boolean {
        if (!isShizukuRunning()) return false
        return try {
            if (Shizuku.isPreV11()) {
                // 旧版 Shizuku 无需运行时权限
                true
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 申请 Shizuku 权限（弹出系统授权对话框）
     * @param requestCode Activity 中用于接收回调的请求码
     * @return true 表示请求已发起；false 表示无法请求（服务未运行等）
     */
    fun requestShizukuPermission(requestCode: Int): Boolean {
        if (!isShizukuRunning()) return false
        return try {
            if (Shizuku.isPreV11() || Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                true
            } else {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    // 用户曾拒绝过，需要先解释再请求
                    false
                } else {
                    Shizuku.requestPermission(requestCode)
                    true
                }
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 获取 Shizuku 服务接口（通过 binder 转 IShizukuService）
     * Shizuku.newProcess 在 13.1.5 中是 private，需通过 IShizukuService.newProcess 调用
     */
    private fun getShizukuService(): IShizukuService? {
        if (!isShizukuAuthorized()) return null
        return try {
            val binder = Shizuku.getBinder() ?: return null
            IShizukuService.Stub.asInterface(binder)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 从 IRemoteProcess 读取输出流
     */
    private fun readProcessOutput(process: IRemoteProcess): String {
        val sb = StringBuilder()
        try {
            val pfd: ParcelFileDescriptor = process.inputStream
            val fis = FileInputStream(pfd.fileDescriptor)
            val reader = BufferedReader(InputStreamReader(fis))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.appendLine(line)
            }
            reader.close()
            fis.close()
        } catch (e: Throwable) {
        }
        return sb.toString()
    }

    /**
     * 通过 Shizuku 执行命令（以 shell 用户身份，权限等同 ADB）
     * 使用 IShizukuService.newProcess()，返回输出文本
     */
    private fun execViaShizuku(command: String): String {
        val service = getShizukuService() ?: return ""
        return try {
            val process = service.newProcess(arrayOf("sh", "-c", command), null, null)
            val output = readProcessOutput(process)
            process.waitFor()
            output
        } catch (e: Throwable) {
            ""
        }
    }

    /**
     * 通过 Shizuku 执行命令，返回退出码
     */
    private fun execViaShizukuForResult(command: String): Int {
        val service = getShizukuService() ?: return -1
        return try {
            val process = service.newProcess(arrayOf("sh", "-c", command), null, null)
            // 先消费输出避免阻塞
            readProcessOutput(process)
            process.waitFor()
        } catch (e: Throwable) {
            -1
        }
    }

    // ==================== 兼容旧接口 ====================

    /**
     * 公开的特权执行命令接口，供终端使用
     * 三级回退：Root（su）→ Shizuku（shell 用户，等同 ADB）→ 普通应用 shell
     * @return Pair(输出文本, 退出码)；退出码 -1 表示执行失败
     */
    fun execPrivileged(command: String): Pair<String, Int> {
        // 1. Root
        if (isRootAvailable()) {
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.appendLine(line)
                }
                val code = process.waitFor()
                Pair(sb.toString(), code)
            } catch (e: Exception) {
                Pair("", -1)
            }
        }
        // 2. Shizuku
        if (isShizukuAuthorized()) {
            val service = getShizukuService()
            if (service != null) {
                return try {
                    val proc = service.newProcess(arrayOf("sh", "-c", command), null, null)
                    val output = readProcessOutput(proc)
                    val code = proc.waitFor()
                    Pair(output, code)
                } catch (e: Throwable) {
                    Pair("", -1)
                }
            }
        }
        // 3. 普通 shell
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.appendLine(line)
            }
            val code = process.waitFor()
            Pair(sb.toString(), code)
        } catch (e: Exception) {
            Pair("", -1)
        }
    }

    /**
     * 获取当前特权模式描述（供终端显示）
     */
    fun getPrivilegeMode(): String {
        return when {
            isRootAvailable() -> "root"
            isShizukuAuthorized() -> "adb (shizuku)"
            isShizukuRunning() -> "shell (shizuku未授权)"
            else -> "shell"
        }
    }

    /**
     * 检测ADB权限是否可用（真正通过 Shizuku API 判断）
     * 旧实现仅尝试 ls /data，无法区分 Shizuku 是否授权本应用，已废弃。
     */
    fun isAdbAvailable(): Boolean {
        return isShizukuAuthorized()
    }

    /**
     * 检测Root权限是否可用
     */
    fun isRootAvailable(): Boolean {
        return RootUtils.isRootAvailable()
    }

    /**
     * 检测是否有任何特权方式可用（Root 或 已授权的 Shizuku）
     */
    fun isPrivilegedAccessAvailable(): Boolean {
        return isRootAvailable() || isShizukuAuthorized()
    }

    // ==================== 打开 ADB 应用 ====================

    /**
     * 打开ADB权限管理应用
     * 先尝试启动应用，失败则打开应用详情页
     */
    fun openAdbApp(context: Context, packageName: String): Boolean {
        // 方式1: 尝试通过启动Intent打开
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            }
        } catch (e: Exception) {
        }

        // 方式2: 尝试通过包名直接打开主Activity
        try {
            val pm = context.packageManager
            val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
                ?: pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            }
        } catch (e: Exception) {
        }

        // 方式3: 打开应用详情页作为回退
        return try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 打开应用详情页
     */
    fun openAppSettings(context: Context, packageName: String) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
        }
    }

    /**
     * 打开 Android 无线调试设置页（用于在 Shizuku 中启动服务）
     */
    fun openWirelessDebuggingSettings(context: Context): Boolean {
        return try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent("com.android.settings.action.WIRELESS_DEBUGGING_SETTINGS")
            } else {
                Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // 回退到开发者选项
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    // ==================== 特权命令执行 ====================

    /**
     * 通过特权方式执行命令
     * 优先使用 Root（如果可用），否则使用 Shizuku
     * @return 命令输出
     */
    fun execCommand(command: String): String {
        val useRoot = isRootAvailable()
        if (useRoot) {
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.appendLine(line)
                }
                process.waitFor()
                sb.toString()
            } catch (e: Exception) {
                ""
            }
        }
        // 使用 Shizuku
        return execViaShizuku(command)
    }

    /**
     * 通过特权方式列出目录内容
     */
    fun listFilesWithPrivilege(path: String): List<RootFileInfo> {
        val result = mutableListOf<RootFileInfo>()
        val useRoot = isRootAvailable()
        val cmdArray = if (useRoot) {
            arrayOf("su", "-c", "ls -la \"$path\"")
        } else {
            // 通过 Shizuku 执行
            return listFilesViaShizuku(path)
        }

        try {
            val process = Runtime.getRuntime().exec(cmdArray)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val info = parseLsLine(line!!, path)
                if (info != null) result.add(info)
            }
            process.waitFor()
        } catch (e: Exception) {
        }
        return result
    }

    /**
     * 通过 Shizuku 列出目录
     */
    private fun listFilesViaShizuku(path: String): List<RootFileInfo> {
        val result = mutableListOf<RootFileInfo>()
        val output = execViaShizuku("ls -la \"$path\"")
        if (output.isEmpty()) return result
        for (line in output.lines()) {
            val info = parseLsLine(line, path)
            if (info != null) result.add(info)
        }
        return result
    }

    /**
     * 通过特权方式读取文件
     */
    fun readFileWithPrivilege(path: String): String? {
        val useRoot = isRootAvailable()
        if (useRoot) {
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat \"$path\""))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.appendLine(line)
                }
                process.waitFor()
                sb.toString()
            } catch (e: Exception) {
                null
            }
        }
        val r = execViaShizuku("cat \"$path\"")
        return if (r.isEmpty()) null else r
    }

    /**
     * 通过特权方式删除文件
     */
    fun deleteWithPrivilege(path: String): Boolean {
        val useRoot = isRootAvailable()
        if (useRoot) {
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "rm -rf \"$path\""))
                val code = process.waitFor()
                code == 0
            } catch (e: Exception) {
                false
            }
        }
        return execViaShizukuForResult("rm -rf \"$path\"") == 0
    }

    /**
     * 解析 ls -la 输出
     */
    private fun parseLsLine(line: String, basePath: String): RootFileInfo? {
        if (line.isBlank()) return null
        if (line.startsWith("total")) return null

        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size < 7) return null

        val permissions = parts[0]
        val isDirectory = permissions.startsWith("d")
        var fileName = parts.subList(6, parts.size).joinToString(" ")

        if (parts.size >= 8) {
            var dateIdx = -1
            for (i in 5 until parts.size - 1) {
                if (parts[i].matches("\\d{4}-\\d{2}-\\d{2}".toRegex()) ||
                    parts[i].matches("\\d{2}:\\d{2}".toRegex()) ||
                    parts[i] in setOf("Jan", "Feb", "Mar", "Apr", "May", "Jun",
                                      "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")) {
                    dateIdx = i
                    break
                }
            }
            if (dateIdx >= 0) {
                fileName = parts.subList(dateIdx + 2, parts.size).joinToString(" ")
            }
        }

        if (fileName.isEmpty() || fileName == "." || fileName == "..") return null

        val size = if (parts.size > 4) parts[4].toLongOrNull() ?: 0L else 0L
        val fullPath = if (basePath.endsWith("/")) "$basePath$fileName" else "$basePath/$fileName"

        return RootFileInfo(
            name = fileName,
            path = fullPath,
            isDirectory = isDirectory,
            size = size,
            permissions = permissions
        )
    }
}
