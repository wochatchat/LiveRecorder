/*
 * UrlExtractorTest — Phase 2：分享/剪贴板文本提取直播 URL 单元测试。
 *
 * 覆盖场景：
 *  1. 纯 URL（各平台直接返回）
 *  2. 带标题的分享文本（提取第一个直播 URL）
 *  3. 不支持平台文本 → null
 *  4. 空文本 / null → null
 *  5. 尾随标点去除（) 等常见分享结尾标点）
 *  6. 多个 URL → 取第一个受支持的
 *  7. 仅有不支持 URL → null
 *  8. http:// vs https:// 均支持
 */
package com.wochatchat.liverecorder.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlExtractorTest {

    // ---- 辅助 ----

    /** 测试提取结果是否为指定 URL（忽略尾部空白）。 */
    private fun assertExtract(input: String?, expected: String) {
        val result = UrlExtractor.extractSupported(input)
        assertEquals("输入: ${input?.take(80)}", expected, result)
    }

    private fun assertNone(input: String?) {
        val result = UrlExtractor.extractSupported(input)
        assertNull("应为 null，输入: ${input?.take(80)}", result)
    }

    // ---- 场景测试 ----

    @Test
    fun `null 返回 null`() = assertNone(null)

    @Test
    fun `空字符串 返回 null`() = assertNone("")

    @Test
    fun `纯空白 返回 null`() = assertNone("   \n\t  ")

    // ---- 纯 URL 测试 ----

    @Test
    fun `斗鱼直播间 URL 直接提取`() =
        assertExtract("https://www.douyu.com/8888", "https://www.douyu.com/8888")

    @Test
    fun `Bilibili 直播间 URL 直接提取`() =
        assertExtract("https://live.bilibili.com/123456", "https://live.bilibili.com/123456")

    @Test
    fun `抖音直播间 URL 直接提取`() =
        assertExtract("https://live.douyin.com/123456", "https://live.douyin.com/123456")

    @Test
    fun `虎牙直播间 URL 直接提取`() =
        assertExtract("https://www.huya.com/888888", "https://www.huya.com/888888")

    @Test
    fun `快手直播间 URL 直接提取`() =
        assertExtract("https://live.kuaishou.com/short-video/abc123", "https://live.kuaishou.com/short-video/abc123")

    @Test
    fun `http 直链也支持`() =
        assertExtract("http://www.douyu.com/8888", "http://www.douyu.com/8888")

    // ---- 带标题分享文本 ----

    @Test
    fun `分享文本含标题和 URL 提取第一个`() =
        assertExtract(
            "来看看这个直播！https://www.douyu.com/8888 抖音直播间",
            "https://www.douyu.com/8888"
        )

    @Test
    fun `分享文本换行分隔提取 URL`() =
        assertExtract(
            """
            发现一个好直播间！
            https://live.bilibili.com/123456
            快来看看
            """.trimIndent(),
            "https://live.bilibili.com/123456"
        )

    @Test
    fun `Bigo 分享文本提取`() =
        assertExtract(
            "直播预告：https://www.bigo.tv/xyz123 主播正在直播",
            "https://www.bigo.tv/xyz123"
        )

    // ---- 尾部标点去除 ----

    @Test
    fun `URL 尾随右括号去除`() =
        assertExtract("快来看直播(https://www.douyu.com/8888)", "https://www.douyu.com/8888")

    @Test
    fun `URL 尾随中文括号去除`() =
        assertExtract("直播间：https://live.bilibili.com/123456）", "https://live.bilibili.com/123456")

    @Test
    fun `URL 尾随逗号去除`() =
        assertExtract("推荐 https://live.douyin.com/123456, 不错", "https://live.douyin.com/123456")

    @Test
    fun `URL 尾随句号去除`() =
        assertExtract("推荐 https://www.huya.com/888888.", "https://www.huya.com/888888")

    @Test
    fun `URL 尾随中文引号去除`() =
        assertExtract("「https://www.douyu.com/8888」", "https://www.douyu.com/8888")

    @Test
    fun `URL 尾随 ) 和双引号去除`() =
        assertExtract("快来！\"https://live.kuaishou.com/short-video/abc123\"", "https://live.kuaishou.com/short-video/abc123")

    // ---- 多 URL 取第一个受支持 ----

    @Test
    fun `多 URL 取第一个受支持的`() =
        assertExtract(
            "链接1: https://example.com/notsupported 链接2: https://www.douyu.com/8888",
            "https://www.douyu.com/8888"
        )

    @Test
    fun `多平台 URL 取第一个`() =
        assertExtract(
            "B站 https://live.bilibili.com/123 抖音 https://live.douyin.com/456",
            "https://live.bilibili.com/123"
        )

    // ---- 不支持场景 ----

    @Test
    fun `无 URL 纯文字 返回 null`() =
        assertNone("这是一个普通分享文本，没有直播链接")

    @Test
    fun `仅不支持域名 URL 返回 null`() =
        assertNone("https://www.google.com/search?q=live")

    @Test
    fun `不支持域名加真实 URL 取真实 URL`() =
        assertExtract(
            "链接 https://google.com https://www.douyu.com/8888",
            "https://www.douyu.com/8888"
        )

    @Test
    fun `微信分享特殊格式 提取`() =
        assertExtract(
            "【直播】抖音直播间 live.douyin.com/123456 快来！",
            "https://live.douyin.com/123456"
        )

    @Test
    fun `空格分隔多 URL 提取`() =
        assertExtract(
            "https://example.com/not  https://live.bilibili.com/123456",
            "https://live.bilibili.com/123456"
        )
}