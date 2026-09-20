package com.wochatchat.liverecorder.platform.douyin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 抖音源分发单测（对照上游 main.py:583 分流规则）。
 */
class DouyinSpiderTest {

    private val spider = DouyinSpider()

    @Test fun `route - normal live room url goes web`() {
        assertEquals(
            DouyinSpider.DouyinRoute.WEB,
            spider.route("https://live.douyin.com/547977714661")
        )
    }

    @Test fun `route - web url with query goes web`() {
        assertEquals(
            DouyinSpider.DouyinRoute.WEB,
            spider.route("https://live.douyin.com/33535?show_type=live_cover")
        )
    }

    @Test fun `route - v douyin share link goes app`() {
        assertEquals(
            DouyinSpider.DouyinRoute.APP,
            spider.route("https://v.douyin.com/iRNBho6u/")
        )
    }

    @Test fun `route - user profile link goes app`() {
        assertEquals(
            DouyinSpider.DouyinRoute.APP,
            spider.route("https://www.douyin.com/user/MS4wLjABAAAAxxxxxxxx?iid=123")
        )
    }

    @Test fun `route - non douyin url defaults to app path`() {
        assertEquals(DouyinSpider.DouyinRoute.APP, spider.route("https://example.com/live"))
    }
}