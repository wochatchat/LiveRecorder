package com.wochatchat.liverecorder.platform.douyin

import com.wochatchat.liverecorder.net.LiveHttpClient

/**
 * 抖音源分发 facade（对照上游 main.py:580 抖音分支 + stream.get_douyin_stream_url 组合）。
 *
 * 分发规则（main.py:584）：
 * - URL 含 douyin.com/ 且非 v.douyin.com 非 /user/ → web 路径（DouyinWebSpider）
 * - 其余（v.douyin.com 短链 / /user/ 主页）→ app 路径（DouyinAppSpider，
 *   内部再按 live.douyin.com 前缀委托 web、reflow 解析、UnsupportedUrl 时 HTML 兜底）
 *
 * 失败语义：爬虫层整体 catch 返回空结果；画质越界同样兜底 isLive=false。
 * 录制模块（1g）只需调 [fetchStreamInfo] 拿 record_url。
 */
class DouyinSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
    private val cookie: String? = null,
) {
    private val webSpider = DouyinWebSpider(client, cookie)
    private val appSpider = DouyinAppSpider(client, cookie, webSpider)

    /**
     * 源分发 + 画质映射，一步到位。
     * @param proxyAddr 非空时本次请求走代理（4a：对齐上游 get_douyin_stream_data proxy_addr 透传），
     *   内部按代理地址新建 client（轮询/录制频次低，开销可忽略）
     */
    suspend fun fetchStreamInfo(url: String, quality: String? = null, proxyAddr: String? = null): DouyinStreamInfo {
        val spider = if (proxyAddr.isNullOrBlank()) this else DouyinSpider(LiveHttpClient(proxyAddr), cookie)
        val room = when (spider.route(url)) {
            DouyinRoute.WEB -> spider.webSpider.fetch(url)
            DouyinRoute.APP -> spider.appSpider.fetch(url)
        }
        return DouyinQuality.resolveStream(room, quality, spider.client)
    }

    /**
     * 路由选择（上游 main.py:583 分流，嵌套在 record_url 含 douyin.com/ 之内）：
     * 含 douyin.com/ 且非 v.douyin.com 非 /user/ → web；否则（含非抖音链接）→ app。
     */
    internal fun route(url: String): DouyinRoute =
        if (url.contains("douyin.com/") && !url.contains("v.douyin.com") && !url.contains("/user/"))
            DouyinRoute.WEB else DouyinRoute.APP

    internal enum class DouyinRoute { WEB, APP }
}
