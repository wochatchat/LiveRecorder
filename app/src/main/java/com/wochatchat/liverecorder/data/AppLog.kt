package com.wochatchat.liverecorder.data

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 文件日志，对齐上游 src/logger.py 双文件日志：
 *
 * - logs/streamget.log ：非 INFO 级别（DEBUG / WARNING / ERROR），格式
 *   `yyyy-MM-dd HH:mm:ss.SSS | LEVEL    | tag - message`
 * - logs/playurl.log   ：仅 INFO 级别，格式 `yyyy-MM-dd HH:mm:ss.SSS | message`
 *
 * 轮转：文件达 300KB 时先删 .1 备份再把当前文件 rename 为 .1（retention=1，
 * 上游 loguru rotation="300 KB", retention=1 同语义）。
 *
 * 未调用 [init] 时全部 no-op（单测环境 / 未初始化不 crash）；
 * 所有写操作同时镜像到 logcat。写入失败静默降级为 logcat，不影响业务流程。
 */
object AppLog {

    private const val STREAMGET_FILE = "streamget.log"
    private const val PLAYURL_FILE = "playurl.log"
    private const val ROTATION_BYTES = 300L * 1024 // 300 KB，对齐上游 rotation

    @Volatile
    private var logDir: File? = null

    private val lock = ReentrantLock()
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(dir: File) {
        lock.withLock {
            dir.mkdirs()
            logDir = dir
        }
    }

    fun d(tag: String, message: String) = streamget("DEBUG", tag, message)
    fun w(tag: String, message: String) = streamget("WARNING", tag, message)
    fun e(tag: String, message: String) = streamget("ERROR", tag, message)

    /** INFO 级别：仅落 playurl.log（上游 filter 只放行 INFO）。 */
    fun i(tag: String, message: String) = playurl(tag, message)

    private fun streamget(level: String, tag: String, message: String) {
        val line = "${timeFmt.format(Date())} | ${level.padEnd(8)} | $tag - $message"
        appendAndMirror(line, level, file(STREAMGET_FILE))
    }

    private fun playurl(tag: String, message: String) {
        val line = "${timeFmt.format(Date())} | $message"
        appendAndMirror(line, "INFO", file(PLAYURL_FILE))
    }

    /** 未初始化时返回不可写路径：写失败走 catch 降级为 logcat。 */
    private fun file(name: String): File = File(logDir ?: File("/dev/null"), name)

    private fun appendAndMirror(line: String, level: String, file: File) {
        lock.withLock {
            try {
                if (logDir == null) { // 未初始化：仅 logcat
                    mirror(line, level)
                    return
                }
                rotateIfNeeded(file)
                FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { w ->
                    w.write(line)
                    w.newLine()
                }
            } catch (e: Exception) {
                Log.e("AppLog", "日志写入失败: ${e.message}")
            }
            mirror(line, level)
        }
    }

    private fun mirror(line: String, level: String) {
        val priority = when (level) {
            "DEBUG"   -> Log.DEBUG
            "WARNING" -> Log.WARN
            "ERROR"   -> Log.ERROR
            else      -> Log.INFO
        }
        Log.println(priority, "AppLog", line)
    }

    private fun rotateIfNeeded(file: File) {
        val dir = logDir ?: return
        if (file.exists() && file.length() >= ROTATION_BYTES) {
            val backup = File(dir, "${file.name}.1")
            if (backup.exists()) backup.delete()
            file.renameTo(backup)
        }
    }

    // ---------- 供日志页 / 导出使用的读取接口 ----------

    /** streamget.log 文件（含 .1 备份），不存在时返回 null。 */
    fun streamgetFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        val list = mutableListOf<File>()
        File(dir, "$STREAMGET_FILE.1").takeIf { it.exists() }?.let { list.add(it) }
        File(dir, STREAMGET_FILE).takeIf { it.exists() }?.let { list.add(it) }
        return list
    }

    fun playurlFiles(): List<File> =
        if (logDir == null) emptyList() else listOf(file(PLAYURL_FILE)).filter { it.exists() }

    /** 日志页显示：两个文件各取尾部 [maxLines] 行，带文件名分隔。 */
    fun readTail(maxLines: Int = 300): String = buildString {
        val sg = streamgetFiles()
        if (sg.isNotEmpty()) {
            appendLine("=== streamget.log ===")
            sg.forEach { append(tailOf(it, maxLines)) }
        }
        val pu = playurlFiles()
        if (pu.isNotEmpty()) {
            appendLine()
            appendLine("=== playurl.log ===")
            pu.forEach { append(tailOf(it, maxLines)) }
        }
        if (isEmpty()) append("(暂无日志)")
    }

    private fun tailOf(file: File, maxLines: Int): String {
        val lines = mutableListOf<String>()
        file.forEachLine(Charsets.UTF_8) { lines.add(it) }
        val start = maxOf(0, lines.size - maxLines)
        return lines.subList(start, lines.size).joinToString("\n") + "\n"
    }

    fun clear() {
        lock.withLock {
            val dir = logDir ?: return
            File(dir, STREAMGET_FILE).delete()
            File(dir, PLAYURL_FILE).delete()
            File(dir, "$STREAMGET_FILE.1").delete()
        }
    }
}
