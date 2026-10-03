package com.example.filemanager

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Root权限工具
 * 通过 su 命令执行需要root权限的操作
 */
object RootUtils {

    private var rootChecked = false
    private var hasRoot = false

    /**
     * 检查设备是否有Root权限
     */
    fun isRootAvailable(): Boolean {
        if (rootChecked) return hasRoot
        rootChecked = true
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()
            process.waitFor()
            hasRoot = output != null && output.contains("uid=0")
        } catch (e: Exception) {
            hasRoot = false
        }
        return hasRoot
    }

    /**
     * 使用Root权限列出目录
     */
    fun listFilesWithRoot(path: String): List<RootFileInfo> {
        val result = mutableListOf<RootFileInfo>()
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls -la \"$path\""))
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
     * 使用Root权限复制文件
     */
    fun copyFileWithRoot(srcPath: String, destPath: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cp \"$srcPath\" \"$destPath\""))
            val code = process.waitFor()
            code == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 使用Root权限删除文件
     */
    fun deleteWithRoot(path: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "rm -rf \"$path\""))
            val code = process.waitFor()
            code == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 使用Root权限读取文件内容
     */
    fun readFileWithRoot(path: String): String? {
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

    /**
     * 解析 ls -la 输出
     * 例: drwxr-xr-x 2 root root 4096 2024-01-01 12:00 dirname
     */
    private fun parseLsLine(line: String, basePath: String): RootFileInfo? {
        if (line.isBlank()) return null
        if (line.startsWith("total")) return null

        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size < 7) return null

        val permissions = parts[0]
        val isDirectory = permissions.startsWith("d")
        val name = parts.subList(6, parts.size).joinToString(" ")

        // 去掉日期时间部分，提取文件名
        // ls -la 格式: permissions links owner group size date time name
        // date可能是 "2024-01-01" 或 "Jan 1"
        var fileName = name
        // 如果名字中包含日期格式的部分，需要截取
        // 简单处理：取最后部分作为文件名
        if (parts.size >= 8) {
            // 尝试找日期后的文件名
            // 格式可能是: ... size 2024-01-01 12:00 name
            // 或: ... size Jan 1 12:00 name
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
                // 文件名在日期和时间之后
                // 日期 + 时间 = 2个字段
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

data class RootFileInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val permissions: String
) {
    fun formattedSize(): String {
        return FileItem.formatFileSize(size)
    }
}
