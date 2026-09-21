package com.wochatchat.liverecorder.storage

import java.io.File

/**
 * 存储空间检查（Phase 2-2h），语义对齐上游 src/utils.py check_disk_capacity：
 * 每轮监控循环检查录制保存目录的剩余空间，低于阈值（config.ini `录制空间剩余阈值(gb)`，
 * 默认 1.0 GB）则暂停监控与录制并通知（上游同语义是 exit_recording → 下载中断 + 主循环退出）；
 * 空间恢复（清理文件）后自动恢复轮询。
 */
class StorageManager(private val dir: File) {

    init {
        // 目录不存在时 File.usableSpace 返回 0，会误判为空间不足；先确保目录存在
        dir.mkdirs()
    }

    /** 保存目录所在分区的剩余空间（GB）。 */
    fun freeGb(): Double = dir.usableSpace / BYTES_PER_GB

    /** 剩余空间是否低于阈值。 */
    fun isLow(thresholdGb: Double): Boolean = isLow(freeGb(), thresholdGb)

    companion object {
        const val BYTES_PER_GB = 1024.0 * 1024 * 1024

        /** 阈值默认值（上游 config.ini 录制空间剩余阈值(gb) 默认 1.0）。 */
        const val DEFAULT_THRESHOLD_GB = 1.0

        fun isLow(freeGb: Double, thresholdGb: Double): Boolean = freeGb < thresholdGb
    }
}
