package com.example.filemanager

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FileItem(
    val file: File
) {
    val name: String get() = if (file.isDirectory) file.name else file.name
    val isDirectory: Boolean get() = file.isDirectory
    val length: Long get() = file.length()
    val lastModified: Long get() = file.lastModified()
    val isHidden: Boolean get() = file.name.startsWith(".")

    fun formattedSize(): String {
        if (file.isDirectory) {
            val count = file.list()?.size ?: 0
            return "$count 项"
        }
        return formatFileSize(file.length())
    }

    fun formattedDate(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(file.lastModified()))
    }

    companion object {
        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var size = bytes.toDouble()
            var unitIndex = 0
            while (size >= 1024 && unitIndex < units.lastIndex) {
                size /= 1024.0
                unitIndex++
            }
            return String.format(Locale.getDefault(), "%.1f %s", size, units[unitIndex])
        }
    }
}
