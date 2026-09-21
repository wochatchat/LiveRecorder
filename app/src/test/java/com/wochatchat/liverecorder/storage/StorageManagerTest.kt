package com.wochatchat.liverecorder.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * StorageManager 单测（JVM）：阈值判定语义对齐上游 check_disk_capacity < 阈值。
 */
class StorageManagerTest {

    @Test
    fun isLow_pureSemantics() {
        // 上游 free < limit 触发；等于阈值不触发
        assertTrue(StorageManager.isLow(freeGb = 0.99, thresholdGb = 1.0))
        assertFalse(StorageManager.isLow(freeGb = 1.0, thresholdGb = 1.0))
        assertFalse(StorageManager.isLow(freeGb = 1.01, thresholdGb = 1.0))
        assertTrue(StorageManager.isLow(freeGb = 0.0, thresholdGb = 1.0))
    }

    @Test
    fun defaultThreshold_alignsUpstreamConfig() {
        // 上游 config.ini `录制空间剩余阈值(gb)` 默认 1.0
        assertEquals(1.0, StorageManager.DEFAULT_THRESHOLD_GB, 1e-9)
    }

    @Test
    fun freeGb_positiveForRealDirectory() {
        val dir = java.nio.file.Files.createTempDirectory("sm").toFile()
        val mgr = StorageManager(dir)
        assertTrue(mgr.freeGb() > 0.0)
        // 临时目录剩余空间远大于 1GB 阈值，不应触发
        assertFalse(mgr.isLow(StorageManager.DEFAULT_THRESHOLD_GB))
    }

    @Test
    fun missingDirectoryCreatedInInit() {
        val parent = java.nio.file.Files.createTempDirectory("sm2").toFile()
        val dir = File(parent, "downloads")
        StorageManager(dir)
        assertTrue(dir.isDirectory)
    }
}
