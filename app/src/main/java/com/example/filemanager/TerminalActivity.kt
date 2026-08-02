package com.example.filemanager

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.filemanager.databinding.ActivityTerminalBinding
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内置终端模拟器 v1.6.4
 *
 * 特性：
 * - 大量 busybox 风格内建命令（即使无 root 也可使用）
 * - 三级特权回退执行外部命令：Root(su) → Shizuku(ADB) → 普通 shell
 * - 启动时自动检测 Shizuku，若运行中未授权则自动申请权限
 * - 维护工作目录、命令历史、cd/pwd/history
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding
    private var cwd: String = "/"
    private val history = mutableListOf<String>()
    private var historyIndex = -1

    private val SHIZUKU_REQUEST_CODE = 7001

    private val shizukuPermissionListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            runOnUiThread {
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    appendOutput("\n[Shizuku 权限已授权，命令将以 ADB 身份执行]\n\n")
                } else {
                    appendOutput("\n[Shizuku 权限被拒绝，命令将以普通 shell 身份执行]\n\n")
                }
                updatePrompt()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.terminal)

        cwd = intent.getStringExtra("cwd") ?: FileUtils.getStorageRoot().absolutePath
        if (!File(cwd).canRead() && !AdbUtils.isPrivilegedAccessAvailable()) cwd = "/"

        binding.tvOutput.movementMethod = ScrollingMovementMethod()

        updatePrompt()

        // 欢迎信息
        appendOutput("FileManager Terminal v1.6.4\n")
        appendOutput("特权模式: ${AdbUtils.getPrivilegeMode()}\n")
        appendOutput("输入 'help' 查看可用命令，'clear' 清屏，'exit' 退出\n")
        appendOutput("当前目录: $cwd\n\n")

        // 自动申请 Shizuku 权限
        tryRequestShizukuPermission()

        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                executeCommand()
                true
            } else false
        }

        binding.etInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                executeCommand()
                return@setOnKeyListener true
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (history.isNotEmpty()) {
                            if (historyIndex == -1) historyIndex = history.size - 1
                            else if (historyIndex > 0) historyIndex--
                            binding.etInput.setText(history[historyIndex])
                            binding.etInput.setSelection(binding.etInput.text.length)
                        }
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (history.isNotEmpty() && historyIndex != -1) {
                            if (historyIndex < history.size - 1) {
                                historyIndex++
                                binding.etInput.setText(history[historyIndex])
                            } else {
                                historyIndex = -1
                                binding.etInput.setText("")
                            }
                            binding.etInput.setSelection(binding.etInput.text.length)
                        }
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }

        binding.btnClear.setOnClickListener {
            binding.tvOutput.text = ""
            appendOutput("已清屏\n")
        }

        binding.etInput.requestFocus()
    }

    /**
     * 自动尝试申请 Shizuku 权限
     * 若 Shizuku 服务正在运行但本应用未授权，自动弹出系统授权对话框
     */
    private fun tryRequestShizukuPermission() {
        try {
            if (rikka.shizuku.Shizuku.isPreV11()) return
            if (!AdbUtils.isShizukuRunning()) {
                appendOutput("[提示] Shizuku 服务未运行，命令将以普通 shell 身份执行\n")
                appendOutput("[提示] 启动 Shizuku 服务后可使用 ADB 权限访问 /data 等目录\n\n")
                return
            }
            if (AdbUtils.isShizukuAuthorized()) {
                appendOutput("[提示] Shizuku 已授权，命令将以 ADB 身份执行\n\n")
                return
            }
            // 服务运行中但未授权，自动申请
            val requested = AdbUtils.requestShizukuPermission(SHIZUKU_REQUEST_CODE)
            if (!requested) {
                appendOutput("[提示] Shizuku 服务运行中但需要手动授权\n")
                appendOutput("[提示] 请打开 Shizuku 应用，在「使用 Shizuku 的应用」中允许「文件管理器」\n\n")
            } else {
                appendOutput("[提示] 正在请求 Shizuku 权限...\n")
            }
        } catch (e: Throwable) {
            // 忽略
        }
    }

    private fun updatePrompt() {
        val shortPath = if (cwd == "/") "/" else cwd.substringAfterLast("/").ifEmpty { cwd }
        val mode = AdbUtils.getPrivilegeMode()
        val promptChar = if (mode.startsWith("root")) "#" else "$"
        binding.tvPrompt.text = "[$mode] $shortPath$promptChar"
    }

    private fun appendOutput(text: String) {
        runOnUiThread {
            binding.tvOutput.append(text)
            binding.scrollOutput.post {
                binding.scrollOutput.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    private fun executeCommand() {
        val raw = binding.etInput.text.toString()
        binding.etInput.setText("")

        if (raw.isBlank()) {
            appendOutput("\n")
            return
        }

        if (history.isEmpty() || history.last() != raw) {
            history.add(raw)
        }
        historyIndex = -1

        appendOutput("${binding.tvPrompt.text} $raw\n")

        val cmd = raw.trim()

        // 内建命令优先处理
        if (handleBuiltin(cmd)) return

        // 外部命令（带 cwd）
        runExternalCommand(cmd)
    }

    /**
     * 处理内建命令，返回 true 表示已处理
     */
    private fun handleBuiltin(cmd: String): Boolean {
        val parts = cmd.split(Regex("\\s+"))
        val name = parts[0]
        val args = if (parts.size > 1) parts.subList(1, parts.size) else emptyList()

        when (name) {
            "exit", "quit" -> { finish(); return true }
            "clear", "cls" -> { binding.tvOutput.text = ""; return true }
            "help", "?" -> { appendOutput(getHelpText()); return true }
            "pwd" -> { appendOutput("$cwd\n"); return true }
            "cd" -> { handleCd(args); return true }
            "history" -> {
                history.forEachIndexed { i, h -> appendOutput("${i + 1}  $h\n") }
                return true
            }
            "echo" -> {
                // 支持 -n 不换行
                var text = args.joinToString(" ")
                var newline = true
                if (text.startsWith("-n ")) {
                    newline = false
                    text = text.substring(3)
                }
                appendOutput(if (newline) "$text\n" else text)
                return true
            }
            "whoami" -> {
                appendOutput(when {
                    AdbUtils.isRootAvailable() -> "root"
                    AdbUtils.isShizukuAuthorized() -> "shell"
                    else -> "u0_a${android.os.Process.myUid() / 100000}"
                } + "\n")
                return true
            }
            "id" -> {
                appendOutput("uid=${android.os.Process.myUid()} gid=${android.os.Process.myTid()}\n")
                return true
            }
            "date" -> {
                val fmt = if (args.isNotEmpty()) args.joinToString(" ") else "yyyy-MM-dd HH:mm:ss"
                val display = try {
                    SimpleDateFormat(fmt, Locale.getDefault()).format(Date())
                } catch (e: Exception) {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                }
                appendOutput("$display\n")
                return true
            }
            "uptime" -> {
                appendOutput(" ${System.currentTimeMillis() / 1000}  (since boot, ms)\n")
                return true
            }
            "true" -> { return true }
            "false" -> { appendOutput("[exit 1]\n"); return true }
            "env" -> {
                System.getenv().forEach { (k, v) -> appendOutput("$k=$v\n") }
                return true
            }
            "export" -> {
                if (args.isEmpty()) {
                    System.getenv().forEach { (k, v) -> appendOutput("export $k=\"$v\"\n") }
                }
                return true
            }
            "unamer", "uname" -> {
                var out = "Linux"
                if ("-a" in args) {
                    out = "Linux android ${android.os.Build.DISPLAY} " +
                          "#${android.os.Build.VERSION.SDK_INT} " +
                          "${android.os.Build.CPU_ABI} GNU/Linux"
                }
                appendOutput("$out\n")
                return true
            }
            // —— 简易内建文件操作 ——
            "cat" -> {
                if (args.isEmpty()) { appendOutput("cat: 缺少文件参数\n"); return true }
                for (arg in args) {
                    val f = resolvePath(arg)
                    if (!f.exists()) { appendOutput("cat: $arg: No such file\n"); continue }
                    if (!f.canRead() && !AdbUtils.isPrivilegedAccessAvailable()) {
                        appendOutput("cat: $arg: Permission denied (需 ADB/Root 权限)\n"); continue
                    }
                    val text = readFilePrivileged(f.absolutePath)
                    appendOutput(text + if (!text.endsWith("\n")) "\n" else "")
                }
                return true
            }
            "touch" -> {
                if (args.isEmpty()) { appendOutput("touch: 缺少文件参数\n"); return true }
                for (arg in args) {
                    val f = resolvePath(arg)
                    try { f.createNewFile() } catch (e: Exception) {}
                }
                return true
            }
            "mkdir" -> {
                if (args.isEmpty()) { appendOutput("mkdir: 缺少目录参数\n"); return true }
                for (arg in args) {
                    val f = resolvePath(arg)
                    val ok = try { f.mkdirs() } catch (e: Exception) { false }
                    if (!ok && !f.exists()) appendOutput("mkdir: 无法创建 $arg\n")
                }
                return true
            }
            "rm" -> {
                if (args.isEmpty()) { appendOutput("rm: 缺少参数\n"); return true }
                val recursive = "-r" in args || "-rf" in args || "-fr" in args
                val files = args.filter { !it.startsWith("-") }
                for (arg in files) {
                    val f = resolvePath(arg)
                    if (!f.exists()) { appendOutput("rm: $arg: No such file\n"); continue }
                    val ok = if (recursive) deleteRecursivePrivileged(f) else {
                        try { f.delete() } catch (e: Exception) { false }
                    }
                    if (!ok) appendOutput("rm: 无法删除 $arg (可能需要权限)\n")
                }
                return true
            }
            "rmdir" -> {
                if (args.isEmpty()) { appendOutput("rmdir: 缺少参数\n"); return true }
                for (arg in args) {
                    val f = resolvePath(arg)
                    if (!f.isDirectory) { appendOutput("rmdir: $arg: Not a directory\n"); continue }
                    val ok = try { f.delete() } catch (e: Exception) { false }
                    if (!ok) appendOutput("rmdir: 无法删除 $arg\n")
                }
                return true
            }
            "mv" -> {
                if (args.size < 2) { appendOutput("mv: 需要源和目标\n"); return true }
                val src = resolvePath(args[0])
                val dst = resolvePath(args[1])
                val ok = try { src.renameTo(dst) } catch (e: Exception) { false }
                if (!ok) appendOutput("mv: 无法移动\n")
                return true
            }
            "cp" -> {
                if (args.size < 2) { appendOutput("cp: 需要源和目标\n"); return true }
                val src = resolvePath(args[0])
                val dst = resolvePath(args[1])
                if (!src.exists()) { appendOutput("cp: ${args[0]}: No such file\n"); return true }
                val ok = try {
                    src.copyTo(dst, overwrite = true)
                    true
                } catch (e: Exception) { false }
                if (!ok) appendOutput("cp: 复制失败\n")
                return true
            }
            "ls" -> {
                handleLs(args)
                return true
            }
            "ll" -> {
                handleLs(listOf("-l") + args)
                return true
            }
            "stat" -> {
                if (args.isEmpty()) { appendOutput("stat: 缺少参数\n"); return true }
                for (arg in args) {
                    val f = resolvePath(arg)
                    if (!f.exists()) { appendOutput("stat: $arg: No such file\n"); continue }
                    appendOutput(statFile(f))
                }
                return true
            }
            "wc" -> {
                if (args.isEmpty()) { appendOutput("wc: 缺少参数\n"); return true }
                for (arg in args) {
                    val f = resolvePath(arg)
                    if (!f.isFile) { appendOutput("wc: $arg: 不可读\n"); continue }
                    val text = readFilePrivileged(f.absolutePath)
                    val lines = text.count { it == '\n' }
                    val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                    val chars = text.length
                    appendOutput("$lines $words $chars ${f.name}\n")
                }
                return true
            }
            "head" -> {
                if (args.isEmpty()) { appendOutput("head: 缺少参数\n"); return true }
                val n = if (args.size >= 2 && args[0] == "-n") args[1].toIntOrNull() ?: 10 else 10
                val pathArg = args.last { !it.startsWith("-") }
                val f = resolvePath(pathArg)
                if (!f.isFile) { appendOutput("head: $pathArg: 不可读\n"); return true }
                val text = readFilePrivileged(f.absolutePath)
                appendOutput(text.lines().take(n).joinToString("\n") + "\n")
                return true
            }
            "tail" -> {
                if (args.isEmpty()) { appendOutput("tail: 缺少参数\n"); return true }
                val n = if (args.size >= 2 && args[0] == "-n") args[1].toIntOrNull() ?: 10 else 10
                val pathArg = args.last { !it.startsWith("-") }
                val f = resolvePath(pathArg)
                if (!f.isFile) { appendOutput("tail: $pathArg: 不可读\n"); return true }
                val text = readFilePrivileged(f.absolutePath)
                appendOutput(text.lines().takeLast(n).joinToString("\n") + "\n")
                return true
            }
            "grep" -> {
                if (args.size < 2) { appendOutput("grep: 用法 grep PATTERN FILE\n"); return true }
                val pattern = args[0]
                val f = resolvePath(args[1])
                if (!f.isFile) { appendOutput("grep: ${args[1]}: 不可读\n"); return true }
                val text = readFilePrivileged(f.absolutePath)
                text.lines().forEach { line ->
                    if (pattern in line) appendOutput("$line\n")
                }
                return true
            }
            "find" -> {
                if (args.isEmpty()) { appendOutput("find: 缺少路径\n"); return true }
                val root = resolvePath(args[0])
                if (!File(root.absolutePath).exists() && !AdbUtils.isPrivilegedAccessAvailable()) {
                    appendOutput("find: ${args[0]}: No such directory\n")
                    return true
                }
                val maxDepth = 3  // 限制深度避免卡死
                findRecursive(root, 0, maxDepth)
                return true
            }
            "df" -> {
                val stat = android.os.StatFs(cwd)
                appendOutput("Filesystem      Size   Used   Free  Use%\n")
                val total = stat.totalBytes / 1024
                val free = stat.availableBytes / 1024
                val used = total - free
                val pct = if (total > 0) used * 100 / total else 0
                appendOutput("${cwd.padEnd(15)} ${total.toString().padStart(8)} ${used.toString().padStart(8)} ${free.toString().padStart(8)} ${pct.toString().padStart(3)}%\n")
                return true
            }
            "free" -> {
                val mi = android.app.ActivityManager.MemoryInfo()
                val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                am.getMemoryInfo(mi)
                appendOutput("             total       used       free\n")
                appendOutput("Mem:  ${mi.totalMem / 1024}   ${(mi.totalMem - mi.availMem) / 1024}   ${mi.availMem / 1024}\n")
                return true
            }
            "ps" -> {
                // 通过特权执行 ps
                return false
            }
            "ifconfig", "ip" -> {
                return false // 走外部命令
            }
            "which" -> {
                if (args.isEmpty()) { appendOutput("which: 缺少命令名\n"); return true }
                val cmdName = args[0]
                val paths = System.getenv("PATH")?.split(":") ?: listOf("/system/bin", "/system/xbin")
                for (p in paths) {
                    val f = File(p, cmdName)
                    if (f.exists() && f.canExecute()) {
                        appendOutput("${f.absolutePath}\n")
                        return true
                    }
                }
                appendOutput("which: $cmdName not found\n")
                return true
            }
            "su" -> {
                if (AdbUtils.isRootAvailable()) {
                    appendOutput("[已切换到 root 模式]\n")
                } else {
                    appendOutput("su: 权限被拒（设备未 Root）\n")
                }
                return true
            }
            "shizuku" -> {
                appendOutput("Shizuku 服务: ${if (AdbUtils.isShizukuRunning()) "运行中" else "未运行"}\n")
                appendOutput("本应用授权: ${if (AdbUtils.isShizukuAuthorized()) "已授权" else "未授权"}\n")
                appendOutput("特权模式: ${AdbUtils.getPrivilegeMode()}\n")
                if (AdbUtils.isShizukuRunning() && !AdbUtils.isShizukuAuthorized()) {
                    AdbUtils.requestShizukuPermission(SHIZUKU_REQUEST_CODE)
                }
                updatePrompt()
                return true
            }
            "ver", "version" -> {
                appendOutput("FileManager Terminal v1.6.4\n")
                appendOutput("Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
                appendOutput("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
                return true
            }
        }
        return false
    }

    private fun handleCd(args: List<String>) {
        val arg = args.firstOrNull() ?: ""
        if (arg.isEmpty() || arg == "~") {
            cwd = FileUtils.getStorageRoot().absolutePath
            updatePrompt()
            return
        }
        val target = if (arg.startsWith("/")) arg else File(cwd, arg).absolutePath
        val dir = File(target).canonicalFile ?: File(target)
        if (!dir.exists()) {
            appendOutput("cd: no such directory: $arg\n")
        } else if (!dir.isDirectory) {
            appendOutput("cd: not a directory: $arg\n")
        } else if (!dir.canRead() && !AdbUtils.isPrivilegedAccessAvailable()) {
            appendOutput("cd: permission denied: $arg (需 Root/Shizuku 权限)\n")
        } else {
            cwd = dir.absolutePath
            updatePrompt()
        }
    }

    private fun handleLs(args: List<String>) {
        val longFormat = "-l" in args || args.contains("-la") || args.contains("-al")
        val showAll = "-a" in args || args.contains("-la") || args.contains("-al")
        val pathArgs = args.filter { !it.startsWith("-") }
        val target = if (pathArgs.isNotEmpty()) resolvePath(pathArgs[0]) else File(cwd)

        val files = if (target.canRead()) {
            target.listFiles()?.toList() ?: emptyList()
        } else if (AdbUtils.isPrivilegedAccessAvailable()) {
            // 通过特权列出
            AdbUtils.listFilesWithPrivilege(target.absolutePath).map { File(it.path) }
        } else {
            appendOutput("ls: ${target.absolutePath}: Permission denied\n")
            return
        }

        val filtered = if (showAll) files else files.filter { !it.name.startsWith(".") }
        val sorted = filtered.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        if (longFormat) {
            for (f in sorted) {
                appendOutput(formatLongListing(f))
            }
        } else {
            val names = sorted.map { if (it.isDirectory) "${it.name}/" else it.name }
            // 简单的多列输出
            appendOutput(names.joinToString("  ") + "\n")
        }
    }

    private fun formatLongListing(f: File): String {
        val perm = (if (f.isDirectory) "d" else "-") +
                   (if (f.canRead()) "r" else "-") +
                   (if (f.canWrite()) "w" else "-") +
                   (if (f.canExecute()) "x" else "-") + "rwxrwxrwx"
        val size = f.length().toString().padStart(10)
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
        val name = if (f.isDirectory) "${f.name}/" else f.name
        return "$perm 1 root root $size $date $name\n"
    }

    private fun statFile(f: File): String {
        val sb = StringBuilder()
        sb.appendLine("  File: ${f.absolutePath}")
        sb.appendLine("  Size: ${f.length()}")
        sb.appendLine("  Type: ${if (f.isDirectory) "directory" else "regular file"}")
        sb.appendLine("  Mode: ${if (f.canRead()) "r" else "-"}${if (f.canWrite()) "w" else "-"}${if (f.canExecute()) "x" else "-"}")
        sb.appendLine("  Modify: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(f.lastModified()))}")
        return sb.toString()
    }

    private fun findRecursive(dir: File, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        val files = if (dir.canRead()) dir.listFiles() else null
        if (files == null) {
            // 尝试特权
            if (AdbUtils.isPrivilegedAccessAvailable()) {
                AdbUtils.listFilesWithPrivilege(dir.absolutePath).forEach {
                    appendOutput("${it.path}\n")
                }
            }
            return
        }
        for (f in files) {
            appendOutput("${f.absolutePath}\n")
            if (f.isDirectory && f.canRead()) {
                findRecursive(f, depth + 1, maxDepth)
            }
        }
    }

    private fun deleteRecursivePrivileged(f: File): Boolean {
        return try {
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        } catch (e: Exception) {
            // 通过特权删除
            AdbUtils.deleteWithPrivilege(f.absolutePath)
        }
    }

    private fun readFilePrivileged(path: String): String {
        val f = File(path)
        return if (f.canRead()) {
            try { f.readText() } catch (e: Exception) { "" }
        } else {
            AdbUtils.readFileWithPrivilege(path) ?: ""
        }
    }

    private fun resolvePath(arg: String): File {
        return if (arg.startsWith("/")) File(arg)
        else if (arg == "~") File(FileUtils.getStorageRoot().absolutePath)
        else if (arg.startsWith("~/")) File(FileUtils.getStorageRoot(), arg.substring(2))
        else File(cwd, arg)
    }

    private fun runExternalCommand(command: String) {
        Thread {
            try {
                val fullCmd = "cd '$cwd' && $command"
                val (output, code) = AdbUtils.execPrivileged(fullCmd)
                appendOutput(output)
                if (code != 0 && output.isNotEmpty() && !output.endsWith("\n")) appendOutput("\n")
                if (code != 0) appendOutput("[exit code: $code]\n")
            } catch (e: Exception) {
                appendOutput("执行失败: ${e.message}\n")
            }
        }.start()
    }

    private fun getHelpText(): String {
        return """
=== FileManager Terminal v1.6.4 ===

【内建命令】（无需 root，应用层实现）
  文件操作: ls/ll cat cp mv rm rmdir mkdir touch stat find
  文本处理: echo head tail wc grep
  系统信息: pwd whoami id date uptime env uname ver
  终端控制: help clear cls exit cd history which shizuku
  存储信息: df free
  特殊: su (切换root)  true/false

【外部命令】（通过 sh 执行，特权模式下可访问系统目录）
  chmod chown chroot ping netstat ifconfig ip ps kill
  tar zip unzip gzip gunzip dd mount umount ln symlink
  sleep wait nohup time xargs tee tr sort uniq cut paste
  diff patch md5sum sha1sum sha256sum base64 od hexdump
  vi nano getprop setprop logcat dumpsys pm am wm
  service cmd settings input screencap screenrecord

【特权模式】
  当前: ${AdbUtils.getPrivilegeMode()}
  • root       : 设备已 Root，命令以 su 执行
  • adb(shizuku): Shizuku 已授权，命令以 shell 用户身份执行（等同 ADB）
  • shell      : 普通 shell，只能访问应用可读目录

【使用提示】
  • 上下方向键切换历史命令
  • 输入 shizuku 查看/申请 ADB 权限
  • cd 到 /data 等系统目录需特权模式
  • 当前目录: $cwd

""".trimIndent()
    }

    override fun onResume() {
        super.onResume()
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Throwable) {}
        updatePrompt()
    }

    override fun onPause() {
        super.onPause()
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Throwable) {}
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
