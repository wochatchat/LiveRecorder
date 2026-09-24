package com.wochatchat.liverecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.regex.Pattern

class AppLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = tmp.newFolder("logs")
        AppLog.init(dir)
    }

    @Test
    fun infoGoesToPlayurlOnly() {
        AppLog.i("T", "开播消息")
        val pu = File(dir, "playurl.log")
        assertTrue(pu.exists())
        val line = pu.readText().trim().lines().single()
        assertTrue("格式: time | message", Pattern.matches("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \| .+""", line))
        assertTrue("含消息", line.contains("开播消息"))
        assertFalse("不落 streamget", File(dir, "streamget.log").exists())
    }

    @Test
    fun warningAndErrorGoToStreamget() {
        AppLog.w("MonitorLoop", "存储不足")
        AppLog.e("RecordController", "录制异常")
        val text = File(dir, "streamget.log").readText()
        val lines = text.trim().lines()
        assertEquals("W+E 两行", 2, lines.size)
        assertTrue("WARNING 格式", Pattern.matches("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \| WARNING  \| .+""", lines[0]))
        assertTrue("ERROR 格式", Pattern.matches("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \| ERROR    \| .+""", lines[1]))
        assertFalse("不落 playurl", File(dir, "playurl.log").exists())
    }

    @Test
    fun debugGoesToStreamget() {
        AppLog.d("Test", "debug 消息")
        val text = File(dir, "streamget.log").readText()
        val line = text.trim().lines().single()
        assertTrue("DEBUG 格式", Pattern.matches("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \| DEBUG    \| .+""", line))
    }

    @Test
    fun rotationAt300KB() {
        val big = "x".repeat(600)
        repeat(20) { AppLog.i("T", big) }
        val f = File(dir, "playurl.log")
        assertTrue("应触发轮转", f.length() < 350 * 1024)
        val backup = File(dir, "playurl.log.1")
        assertTrue("备份存在", backup.exists())
        assertTrue("当前文件重新写入", f.readText().isNotEmpty())
    }

    @Test
    fun readTailReturnsBoth() {
        AppLog.i("T1", "info1")
        AppLog.w("T2", "warn1")
        val tail = AppLog.readTail(200)
        assertTrue("含 streamget", tail.contains("streamget.log"))
        assertTrue("含 playurl", tail.contains("playurl.log"))
        assertTrue("含 warn1", tail.contains("warn1"))
        assertTrue("含 info1", tail.contains("info1"))
    }

    @Test
    fun clearDeletesAll() {
        AppLog.i("T", "msg")
        AppLog.w("T", "warn")
        AppLog.clear()
        val tail = AppLog.readTail()
        assertEquals("(无日志)", "(暂无日志)", tail.trim())
        assertFalse("streamget 清除", File(dir, "streamget.log").exists())
        assertFalse("playurl 清除", File(dir, "playurl.log").exists())
    }
}
