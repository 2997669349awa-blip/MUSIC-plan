package com.example.filemanager

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object FileUtils {

    fun getStorageRoot(): File {
        val f = File("/storage/emulated/0")
        if (f.exists()) return f
        return android.os.Environment.getExternalStorageDirectory()
    }

    fun listFiles(dir: File, showHidden: Boolean): List<FileItem> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { showHidden || !it.name.startsWith(".") }
            .map { FileItem(it) }
    }

    fun sort(items: List<FileItem>, mode: SortMode): List<FileItem> {
        return when (mode) {
            SortMode.NAME -> items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))
            SortMode.SIZE -> items.sortedWith(compareBy({ !it.isDirectory }, { it.length }))
            SortMode.DATE -> items.sortedWith(compareBy({ !it.isDirectory }, { it.lastModified })).asReversed()
            SortMode.TYPE -> items.sortedWith(compareBy({ !it.isDirectory }, { getExtension(it.name).lowercase(Locale.getDefault()) }, { it.name.lowercase(Locale.getDefault()) }))
        }
    }

    private fun getExtension(name: String): String {
        val idx = name.lastIndexOf('.')
        return if (idx >= 0) name.substring(idx + 1) else ""
    }

    fun getMimeType(file: File): String {
        val ext = getExtension(file.name)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase(Locale.getDefault()))
        return mime ?: "*/*"
    }

    fun isTextFile(file: File): Boolean {
        val ext = getExtension(file.name).lowercase()
        return ext in setOf("txt", "log", "md", "json", "xml", "csv", "properties", "conf", "cfg", "ini", "java", "kt", "py", "js", "ts", "html", "css", "sql", "sh", "bat", "yml", "yaml", "gradle")
    }

    fun isImageFile(file: File): Boolean {
        val ext = getExtension(file.name).lowercase()
        return ext in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "tiff", "ico")
    }

    fun isZipFile(file: File): Boolean {
        val ext = getExtension(file.name).lowercase()
        return ext in setOf("zip")
    }

    fun isAudioFile(file: File): Boolean {
        val ext = getExtension(file.name).lowercase()
        return ext in setOf("mp3", "flac", "wav", "ogg", "aac", "m4a", "wma", "opus", "amr", "mid", "midi", "xm", "s3m", "mod", "it")
    }

    fun isArchiveFile(file: File): Boolean {
        val ext = getExtension(file.name).lowercase()
        return ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2")
    }

    fun openFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, getMimeType(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(fallback)
            } catch (_: Exception) {
            }
        }
    }

    fun deleteRecursively(file: File): Boolean {
        return file.deleteRecursively()
    }

    fun copyFile(src: File, dest: File): Boolean {
        return try {
            if (src.isDirectory) {
                if (!dest.exists()) dest.mkdirs()
                src.listFiles()?.forEach { child ->
                    copyFile(child, File(dest, child.name))
                }
                true
            } else {
                src.inputStream().use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun rename(file: File, newName: String): Boolean {
        return file.renameTo(File(file.parentFile, newName))
    }

    fun createFolder(parent: File, name: String): Boolean {
        return File(parent, name).mkdirs()
    }

    fun createFile(parent: File, name: String): Boolean {
        return File(parent, name).createNewFile()
    }

    // ===== ZIP 功能 =====

    /**
     * 列出ZIP压缩包内的文件列表
     */
    fun listZipEntries(zipFile: File): List<ZipEntryInfo> {
        val result = mutableListOf<ZipEntryInfo>()
        try {
            ZipFile(zipFile).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    result.add(ZipEntryInfo(
                        name = entry.name,
                        size = entry.size,
                        isDirectory = entry.isDirectory,
                        compressedSize = entry.compressedSize,
                        time = entry.time
                    ))
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return result
    }

    /**
     * 解压ZIP文件到指定目录
     */
    fun extractZip(zipFile: File, destDir: File): Boolean {
        return try {
            if (!destDir.exists()) destDir.mkdirs()
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry: ZipEntry
                while (zis.nextEntry.also { entry = it } != null) {
                    val outFile = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    zis.closeEntry()
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 压缩文件/文件夹为ZIP
     */
    fun compressToZip(files: List<File>, zipOutput: File): Boolean {
        return try {
            FileOutputStream(zipOutput).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    for (file in files) {
                        if (file.isDirectory) {
                            compressDirectory(file, file.name, zos)
                        } else {
                            addFileToZip(file, file.name, zos)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun compressDirectory(dir: File, basePath: String, zos: ZipOutputStream) {
        val entries = dir.listFiles() ?: return
        // 添加空目录条目
        if (entries.isEmpty()) {
            zos.putNextEntry(ZipEntry("$basePath/"))
            zos.closeEntry()
            return
        }
        for (file in entries) {
            val path = "$basePath/${file.name}"
            if (file.isDirectory) {
                compressDirectory(file, path, zos)
            } else {
                addFileToZip(file, path, zos)
            }
        }
    }

    private fun addFileToZip(file: File, entryName: String, zos: ZipOutputStream) {
        FileInputStream(file).use { fis ->
            zos.putNextEntry(ZipEntry(entryName))
            fis.copyTo(zos)
            zos.closeEntry()
        }
    }
}

data class ZipEntryInfo(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val compressedSize: Long,
    val time: Long
) {
    fun formattedSize(): String {
        if (size < 0) return "未知"
        return FileItem.formatFileSize(size)
    }

    fun formattedCompressedSize(): String {
        return FileItem.formatFileSize(compressedSize)
    }
}

enum class SortMode { NAME, SIZE, DATE, TYPE }
