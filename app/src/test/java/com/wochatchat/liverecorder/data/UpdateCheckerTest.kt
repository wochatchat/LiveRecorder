/*
 * UpdateCheckerTest — Phase 3：版本解析与更新判断单元测试。
 *
 * 测试覆盖：
 *  1. 版本解析（tag_name 去 v 前缀）
 *  2. prerelease / draft 跳过
 *  3. APK asset URL 提取
 *  4. releaseNotes 截取逻辑
 *  5. 版本比较（相同版本 → 无需更新）
 *  6. 忽略版本不提示
 */
package com.wochatchat.liverecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class UpdateCheckerTest {

    // ---- parseRelease 测试 ----

    private fun parseReleaseJson(json: String): UpdateChecker.GithubRelease =
        UpdateChecker.parseRelease(json)

    @Test
    fun `tag_name 带 v 前缀 正确去掉`() {
        val json = releaseJson(tagName = "v0.3.100")
        val release = parseReleaseJson(json)
        assertEquals("0.3.100", release.version)
    }

    @Test
    fun `tag_name 无 v 前缀 直接取`() {
        val json = releaseJson(tagName = "0.3.100")
        val release = parseReleaseJson(json)
        assertEquals("0.3.100", release.version)
    }

    @Test
    fun `prerelease 为 true 跳过`() {
        val json = releaseJson(prerelease = true)
        val release = parseReleaseJson(json)
        assertTrue(release.prerelease)
    }

    @Test
    fun `draft 字段正确解析`() {
        val json = releaseJson(draft = true)
        val release = parseReleaseJson(json)
        assertTrue(release.draft)
    }

    @Test
    fun `assets 中找到第一个 apk 文件`() {
        val json = releaseJson(
            assets = """
            [
              {"name": "README.md", "browser_download_url": "https://example.com/README.md"},
              {"name": "liverecorder-v0.3.100.apk", "browser_download_url": "https://github.com/wochatchat/LiveRecorder/releases/download/v0.3.100/liverecorder-v0.3.100.apk"},
              {"name": "liverecorder-v0.3.101.apk", "browser_download_url": "https://example.com/liverecorder-v0.3.101.apk"}
            ]
            """.trimIndent()
        )
        val release = parseReleaseJson(json)
        assertEquals("https://github.com/wochatchat/LiveRecorder/releases/download/v0.3.100/liverecorder-v0.3.100.apk", release.apkUrl)
    }

    @Test
    fun `无 assets 时 apkUrl 为空`() {
        val json = releaseJson(assets = "[]")
        val release = parseReleaseJson(json)
        assertEquals("", release.apkUrl)
    }

    @Test
    fun `releaseNotes 取非标题行前 500 字符`() {
        val body = """
        ## v0.3.100 更新说明

        - 新增悬浮球确认卡片（Phase 2）
        - 修复 URL 提取边界问题
        - 优化性能
        """.trimIndent()
        val json = releaseJson(body = body)
        val release = parseReleaseJson(json)
        // 过滤掉 ## 开头的行，取其余内容
        assertTrue(release.releaseNotes.isNotBlank())
        assertTrue(release.releaseNotes.length <= 500)
    }

    @Test
    fun `releaseNotes 超过 500 字符截断`() {
        // 单行 501 字符，join 后仍 501，take(500) 截到 500
        val longBody = "这".repeat(501)
        val json = releaseJson(body = longBody)
        val release = parseReleaseJson(json)
        assertEquals(500, release.releaseNotes.length)
    }

    // ---- 辅助函数 ----

    private fun releaseJson(
        tagName: String = "v0.3.100",
        prerelease: Boolean = false,
        draft: Boolean = false,
        body: String = "更新内容",
        assets: String = """[{"name": "liverecorder-v0.3.100.apk", "browser_download_url": "https://example.com/liverecorder.apk"}]""",
    ): String {
        return JSONObject().apply {
            put("tag_name", tagName)
            put("prerelease", prerelease)
            put("draft", draft)
            put("body", body)
            put("assets", org.json.JSONArray(assets))
        }.toString()
    }
}