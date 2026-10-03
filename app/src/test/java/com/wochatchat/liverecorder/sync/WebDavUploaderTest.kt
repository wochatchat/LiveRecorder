package com.wochatchat.liverecorder.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 11-11.1：WebDAV 路径纯函数 + 退避。 */
class WebDavUploaderTest {

    @Test
    fun `祖先目录链含全路径`() {
        assertEquals(
            listOf("/a", "/a/b"),
            WebDavUploader.ancestorDirs("/a/b/c.mp4"),
        )
    }

    @Test
    fun `根下文件无祖先目录`() {
        assertTrue(WebDavUploader.ancestorDirs("/c.mp4").isEmpty())
        assertTrue(WebDavUploader.ancestorDirs("c.mp4").isEmpty())
        assertTrue(WebDavUploader.ancestorDirs("").isEmpty())
    }

    @Test
    fun `深层路径逐级展开`() {
        assertEquals(
            listOf("/L", "/L/p", "/L/p/anchor"),
            WebDavUploader.ancestorDirs("/L/p/anchor/x_001.m4a"),
        )
    }

    @Test
    fun `远端路径拼接规整斜杠`() {
        assertEquals("http://n/LR/douyin/a.mp4", WebDavUploader.remotePath("http://n/LR/", "douyin/a.mp4"))
        assertEquals("http://n/LR/douyin/a.mp4", WebDavUploader.remotePath("http://n/LR", "/douyin/a.mp4"))
    }

    @Test
    fun `退避指数递增且封顶`() {
        assertEquals(5L, CloudSyncManager.backoffSec(0))
        assertEquals(10L, CloudSyncManager.backoffSec(1))
        assertEquals(20L, CloudSyncManager.backoffSec(2))
        assertEquals(300L, CloudSyncManager.backoffSec(20))
    }
}
