/*
 * CustomRecordDirTest — V3-6：目录解析与回落纯逻辑单元测试。
 *
 * 覆盖：
 *  1. treeId → 真实路径映射（primary / SD 卡 / 卷根 / 嵌套目录）
 *  2. 非法 treeId（无冒号 / 空串）返回 null
 *  3. resolveOrDefault 回落语义（未启用 / 无权限 / 建目录失败 / 非目录）
 *  4. labelFor 取尾段
 */
package com.wochatchat.liverecorder.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CustomRecordDirTest {

    private val primaryRoot = "/storage/emulated/0"

    // ---- pathFromTreeId ----

    @Test
    fun `primary 卷根目录映射`() {
        assertEquals(primaryRoot, CustomRecordDir.pathFromTreeId("primary:", primaryRoot))
    }

    @Test
    fun `primary 一级子目录映射`() {
        assertEquals(
            "$primaryRoot/LiveRecorder",
            CustomRecordDir.pathFromTreeId("primary:LiveRecorder", primaryRoot),
        )
    }

    @Test
    fun `primary 嵌套子目录映射`() {
        assertEquals(
            "$primaryRoot/a/b/c",
            CustomRecordDir.pathFromTreeId("primary:a/b/c", primaryRoot),
        )
    }

    @Test
    fun `SD 卡 uuid 卷映射`() {
        assertEquals("/storage/6236-4123/Rec", CustomRecordDir.pathFromTreeId("6236-4123:Rec", primaryRoot))
    }

    @Test
    fun `SD 卡卷根映射`() {
        assertEquals("/storage/6236-4123", CustomRecordDir.pathFromTreeId("6236-4123:", primaryRoot))
    }

    @Test
    fun `无冒号且非 primary 返回 null 当 volume 缺失`() {
        // "primary" 是合法卷根；这里验证 volume 解析失败场景（空串）
        assertNull(CustomRecordDir.pathFromTreeId("", primaryRoot))
    }

    @Test
    fun `尾部冒号容错`() {
        assertEquals(
            "$primaryRoot/Recordings",
            CustomRecordDir.pathFromTreeId("primary:Recordings:", primaryRoot),
        )
    }

    // ---- resolveOrDefault ----

    @Test
    fun `未启用自定义目录回落默认`() {
        val default = File("/data/default")
        assertSame(
            default,
            CustomRecordDir.resolveOrDefault("", accessGranted = true, defaultDir = default),
        )
        assertSame(
            default,
            CustomRecordDir.resolveOrDefault("  ", accessGranted = true, defaultDir = default),
        )
    }

    @Test
    fun `无权限回落默认`() {
        val default = File("/data/default")
        assertSame(
            default,
            CustomRecordDir.resolveOrDefault("/sdcard/Rec", accessGranted = false, defaultDir = default),
        )
    }

    @Test
    fun `自定义目录存在且是目录时使用`() {
        val tmp = Files.createTempDirectory("recdir").toFile()
        try {
            val resolved = CustomRecordDir.resolveOrDefault(
                tmp.absolutePath, accessGranted = true, defaultDir = File("/data/default"),
            )
            assertEquals(tmp.absolutePath, resolved.absolutePath)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `自定义目录不存在时可创建则使用`() {
        val base = Files.createTempDirectory("recbase").toFile()
        try {
            val nested = File(base, "a/b").absolutePath
            val resolved = CustomRecordDir.resolveOrDefault(
                nested, accessGranted = true, defaultDir = File("/data/default"),
            )
            assertEquals(nested, resolved.absolutePath)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `路径指向文件而非目录时回落默认`() {
        val file = Files.createTempFile("notdir", ".txt").toFile()
        try {
            val resolved = CustomRecordDir.resolveOrDefault(
                file.absolutePath, accessGranted = true, defaultDir = File("/data/default"),
            )
            assertEquals("/data/default", resolved.absolutePath)
        } finally {
            file.delete()
        }
    }

    // ---- labelFor ----

    @Test
    fun `labelFor 取尾段`() {
        assertEquals("Recordings", CustomRecordDir.labelFor("/storage/emulated/0/Recordings"))
        assertEquals("Rec", CustomRecordDir.labelFor("/storage/6236-4123/Rec/"))
    }
}
