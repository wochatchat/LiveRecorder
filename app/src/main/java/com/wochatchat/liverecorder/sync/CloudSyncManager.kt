package com.wochatchat.liverecorder.sync

import com.wochatchat.liverecorder.data.AppLog
import com.wochatchat.liverecorder.data.CloudSyncStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Phase 11-11.1：云同步管理器。
 * 周期（[POLL_MS]）或 kick 后扫描录制历史 savePath，把尚未上传的文件经 WebDAV 上传；
 * 失败按指数退避重试（每个文件每轮最多 [MAX_ATTEMPTS] 次，下轮重新入队）。
 * 已上传集合仅存内存——重启后按「远端是否已传」不可知，改为本地文件 mtime 老于阈值才上传，
 * 重复上传代价可接受（幂等覆盖）。
 */
class CloudSyncManager(
    private val store: CloudSyncStore,
    private val uploader: WebDavUploader = WebDavUploader(),
    /** 保存目录根（相对路径映射远端目录）。 */
    private val baseDir: File,
    /** 待扫描的 savePath 集合（注入便于单测，生产读历史库）。 */
    private val savePaths: suspend () -> List<String> = { emptyList() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val uploaded = HashSet<String>()
    private val kick = MutableStateFlow(0)

    /** 录制结束后立即触发一次扫描（转换中的 ts 由新鲜度过滤兜住）。 */
    fun kickUpload() {
        kick.value = kick.value + 1
    }

    /**
     * 待上传文件（纯逻辑便于单测）：
     * - 已传过跳过；目录下递归收集；ts 分片要求 mtime 老于 [TS_MIN_AGE_MS]（防转换中误传），其余老于 [MIN_AGE_MS]
     */
    fun pendingFiles(savePaths: List<String>, now: Long = nowMs()): List<File> {
        val out = ArrayList<File>()
        for (p in savePaths) {
            val f = File(p)
            val files = if (f.isDirectory) f.walkBottomUp().filter { it.isFile }.toList() else listOf(f)
            for (cand in files) {
                if (cand.length() == 0L) continue
                if (cand.absolutePath in uploaded) continue
                val minAge = if (cand.name.endsWith(".ts", ignoreCase = true)) TS_MIN_AGE_MS else MIN_AGE_MS
                if (now - cand.lastModified() < minAge) continue
                out.add(cand)
            }
        }
        return out
    }

    /** 扫描并上传一轮，返回成功上传的文件数。 */
    suspend fun syncOnce(): Int {
        val settings = store.settings.first()
        if (!settings.enabled) return 0
        val creds = WebDavUploader.Creds(settings.serverUrl, settings.username, settings.password)
        if (!creds.valid) return 0
        val root = settings.remoteDir.trim().trimEnd('/').ifBlank { "LiveRecorder" }

        var okCount = 0
        for (file in pendingFiles(savePaths())) {
            val rel = file.absolutePath.removePrefix(baseDir.absolutePath).trimStart('/')
            val remote = WebDavUploader.remotePath("http://x/$root", rel).removePrefix("http://x")
            var attempt = 0
            while (attempt < MAX_ATTEMPTS) {
                when (uploader.upload(creds, remote, file)) {
                    is WebDavUploader.Result.Success -> {
                        uploaded.add(file.absolutePath)
                        if (!settings.keepLocal) file.delete()
                        okCount++
                        AppLog.i(TAG, "已上传: $remote")
                        break
                    }
                    is WebDavUploader.Result.Failure -> {
                        attempt++
                        AppLog.e(TAG, "上传失败(第${attempt}次): ${file.name}，退避 ${backoffSec(attempt)}s")
                        if (attempt < MAX_ATTEMPTS) sleep(backoffSec(attempt) * 1000)
                    }
                }
            }
        }
        return okCount
    }

    /** 常驻循环：每 [POLL_MS] 或 kick 触发一轮同步。 */
    suspend fun runLoop() {
        while (true) {
            runCatching { syncOnce() }
                .onFailure { AppLog.e(TAG, "云同步轮次异常: ${it.message}") }
            // kick 计数变化即提前触发；否则等一个轮询周期
            val seen = kick.value
            val deadline = nowMs() + POLL_MS
            while (nowMs() < deadline && kick.value == seen) {
                sleep(POLL_SLICE_MS)
            }
        }
    }

    companion object {
        private const val TAG = "CloudSyncManager"
        /** 轮询周期 15 分钟（对齐日报调度节奏）。 */
        const val POLL_MS = 15 * 60 * 1000L
        /** kick 轮询切片。 */
        const val POLL_SLICE_MS = 30 * 1000L
        /** 普通文件最小落盘年龄（防 remux/写入中误传）。 */
        const val MIN_AGE_MS = 60 * 1000L
        /** ts 分片最小年龄（可能还在排队转换/删除）。 */
        const val TS_MIN_AGE_MS = 10 * 60 * 1000L
        /** 单文件每轮最大尝试次数。 */
        const val MAX_ATTEMPTS = 3

        /** 指数退避（秒）：5/10/20/40/80/160，上限 300。 */
        fun backoffSec(attempt: Int): Long =
            (5L shl attempt.coerceIn(0, 6)).coerceAtMost(300)
    }
}
