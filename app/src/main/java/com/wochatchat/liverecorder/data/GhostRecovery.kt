package com.wochatchat.liverecorder.data

import java.io.File

/**
 * V3-1：幽灵文件回收——扫描录制目录，发现「有落盘文件但历史无记录」的孤儿，
 * 补写历史（completed=false，时间取文件 mtime），消除「录了一半白录」。
 *
 * 覆盖判定：文件自身或其任一祖先目录出现在已知 savePath 集合中即视为已入库
 * （ffmpeg 分段模式落库的是目录路径，其下分片/转码产物天然覆盖）。
 */
object GhostRecovery {

    /** 最近修改窗口内的文件跳过：可能是进行中录制的分片（进程内存态，未落库属正常）。 */
    const val ACTIVE_WINDOW_MS = 10 * 60 * 1000L

    /** 计入回收的视频/音频扩展名。 */
    val VIDEO_EXTS = setOf(".flv", ".ts", ".mp4", ".mkv", ".m4a")

    /** 一组孤儿文件（同一目录），聚合为一条历史记录。 */
    data class GhostGroup(
        val dirPath: String,
        /** 展示名：目录名（分段会话）或文件名（根目录散文件，去扩展名）。 */
        val displayName: String,
        val bytes: Long,
        val lastModifiedMs: Long,
        val files: List<File>,
    )

    /** 文件路径是否已被已知记录覆盖（自身或祖先目录在 [knownPaths] 中）。 */
    fun isCovered(file: File, knownPaths: Set<String>): Boolean {
        var cur: File? = file
        while (cur != null) {
            if (cur.absolutePath in knownPaths) return true
            cur = cur.parentFile
        }
        return false
    }

    /**
     * 扫描 [baseDir] 下未入库的视频文件，按目录分组返回候选。
     * [nowMs] 为当前时钟；[activeWindowMs] 内修改过的文件跳过（防误收进行中录制）。
     */
    fun findGhosts(
        baseDir: File,
        knownPaths: Set<String>,
        nowMs: Long,
        activeWindowMs: Long = ACTIVE_WINDOW_MS,
    ): List<GhostGroup> {
        if (!baseDir.isDirectory) return emptyList()
        val knownAbs = knownPaths.map { File(it).absolutePath }.toSet()
        val ghosts = mutableListOf<File>()
        baseDir.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            if (f.extension.lowercase().let { ext -> VIDEO_EXTS.any { it.removePrefix(".") == ext } }.not()) return@forEach
            if (isCovered(f, knownAbs)) return@forEach
            if (nowMs - f.lastModified() < activeWindowMs) return@forEach
            ghosts.add(f)
        }
        return ghosts.groupBy { it.parentFile }.map { (dir, files) ->
            val name = if (dir == baseDir) files.first().nameWithoutExtension else dir.name
            GhostGroup(
                dirPath = dir.absolutePath,
                displayName = name,
                bytes = files.sumOf { it.length() },
                lastModifiedMs = files.maxOf { it.lastModified() },
                files = files,
            )
        }.sortedBy { it.lastModifiedMs }
    }
}
