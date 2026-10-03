package com.wochatchat.liverecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * V3-1：幽灵文件回收纯逻辑测试——覆盖判定（自身/祖先目录）、活跃窗口跳过、
 * 扩展名过滤、按目录聚合。
 */
class GhostRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_700_000_000_000L

    private fun File.touchVideo(name: String, bytes: Long = 1024, ageMs: Long = 60 * 60 * 1000L): File {
        val f = File(this, name)
        f.parentFile.mkdirs()
        f.writeText("x".repeat(bytes.toInt()))
        f.setLastModified(now - ageMs)
        return f
    }

    @Test
    fun `目录在历史中则其下文件全部视为已覆盖`() {
        val base = tmp.newFolder("downloads")
        val dir = File(base, "抖音直播/主播A/2026-10-01")
        dir.touchVideo("seg001.ts")
        dir.touchVideo("seg002.ts")
        dir.touchVideo("seg001.mp4")

        val ghosts = GhostRecovery.findGhosts(
            base, knownPaths = setOf(dir.absolutePath), nowMs = now,
        )
        assertTrue(ghosts.isEmpty())
    }

    @Test
    fun `文件自身在历史中则已覆盖`() {
        val base = tmp.newFolder("downloads")
        val f = base.touchVideo("主播_时间戳.flv")
        val ghosts = GhostRecovery.findGhosts(base, setOf(f.absolutePath), now)
        assertTrue(ghosts.isEmpty())
    }

    @Test
    fun `孤儿分段按目录聚合为一条候选`() {
        val base = tmp.newFolder("downloads")
        val dir = File(base, "抖音直播/主播B")
        dir.touchVideo("seg001.ts", bytes = 100)
        dir.touchVideo("seg002.ts", bytes = 200)

        val ghosts = GhostRecovery.findGhosts(base, emptySet(), now)
        assertEquals(1, ghosts.size)
        val g = ghosts.first()
        assertEquals(dir.absolutePath, g.dirPath)
        assertEquals(300L, g.bytes)
        assertEquals("主播B", g.displayName)
        assertEquals(2, g.files.size)
    }

    @Test
    fun `活跃窗口内修改的文件跳过（可能是进行中录制）`() {
        val base = tmp.newFolder("downloads")
        base.touchVideo("fresh.flv", ageMs = GhostRecovery.ACTIVE_WINDOW_MS / 2)
        base.touchVideo("old.flv", ageMs = GhostRecovery.ACTIVE_WINDOW_MS * 2)

        val ghosts = GhostRecovery.findGhosts(base, emptySet(), now)
        assertEquals(1, ghosts.size)
        assertEquals("old", ghosts.first().displayName)
    }

    @Test
    fun `非视频扩展名跳过`() {
        val base = tmp.newFolder("downloads")
        base.touchVideo("meta.json")
        base.touchVideo("cookie.txt")
        assertTrue(GhostRecovery.findGhosts(base, emptySet(), now).isEmpty())
    }

    @Test
    fun `根目录散文件聚合到 baseDir 自身且名为文件名去扩展`() {
        val base = tmp.newFolder("downloads")
        base.touchVideo("主播C_2026-10-01_10-00-00.mkv")

        val ghosts = GhostRecovery.findGhosts(base, emptySet(), now)
        assertEquals(1, ghosts.size)
        assertEquals(base.absolutePath, ghosts.first().dirPath)
        assertEquals("主播C_2026-10-01_10-00-00", ghosts.first().displayName)
    }

    @Test
    fun `目录不存在返回空`() {
        assertTrue(GhostRecovery.findGhosts(File("/nonexistent/dir"), emptySet(), now).isEmpty())
    }

    @Test
    fun `isCovered 沿祖先链判定`() {
        val dir = tmp.newFolder("a/b/c")
        val f = File(dir, "seg.ts")
        assertTrue(GhostRecovery.isCovered(f, setOf(dir.absolutePath)))
        assertTrue(GhostRecovery.isCovered(f, setOf(f.absolutePath)))
        assertFalse(GhostRecovery.isCovered(f, setOf(tmp.root.absolutePath + "/other")))
        assertFalse(GhostRecovery.isCovered(f, emptySet()))
    }

    @Test
    fun `已知路径含历史反斜杠归一——绝对路径匹配按 File 归一`() {
        val base = tmp.newFolder("downloads")
        val dir = File(base, "p")
        dir.touchVideo("s.ts")
        // 历史里存的可能带冗余分隔符，File(绝对路径).absolutePath 归一后仍应命中
        val ghosts = GhostRecovery.findGhosts(
            base, setOf(dir.absolutePath + File.separator), now,
        )
        assertTrue(ghosts.isEmpty())
    }
}
