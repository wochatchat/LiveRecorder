package com.wochatchat.liverecorder.platform.qiandurebo

import com.wochatchat.liverecorder.net.LiveHttpClient
import java.util.regex.Pattern

/**
 * QiandureboSpider - Phase 9f: QianDuReBo.
 */
open class QiandureboSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private val RE_USER = Pattern.compile("var user = (.*?)[\r\n]+\\s*user\\.play_url", Pattern.DOTALL)
        private val RE_NICKNAME = Pattern.compile("\"zb_nickname\": \"(.*?)\"")
        private val RE_PLAY_URL = Pattern.compile("\"play_url\": \"(.*?)\"")
        private val RE_OFFLINE = Pattern.compile("common-text-center\" style=\"display:block")
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0"
    }

    data class QiandureboStreamInfo(
        val anchorName: String = "",
        val isLive: Boolean = false,
        val flvUrl: String = "",
        val recordUrl: String = "",
    )

    fun parseRoomId(url: String): String =
        url.split("?").first().substringAfterLast("/")

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): QiandureboStreamInfo {
        val roomId = parseRoomId(url)
        if (roomId.isBlank()) return QiandureboStreamInfo()
        val headers = buildMap {
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            put("Referer", "https://qiandurebo.com/web/index.php")
            put("User-Agent", UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)
        val resp = runCatching { c.get(url, headers) }.getOrNull()
            ?: return QiandureboStreamInfo()
        val html = resp.text
        if (RE_OFFLINE.toRegex().containsMatchIn(html)) return QiandureboStreamInfo()
        val userBlock = RE_USER.toRegex().find(html)?.groupValues?.get(1)
            ?: return QiandureboStreamInfo()
        val anchorName = RE_NICKNAME.toRegex().find(userBlock)?.groupValues?.get(1) ?: ""
        if (anchorName.isBlank()) return QiandureboStreamInfo()
        val playUrl = RE_PLAY_URL.toRegex().find(userBlock)?.groupValues?.get(1) ?: ""
        if (playUrl.isBlank()) return QiandureboStreamInfo(anchorName = anchorName)
        return QiandureboStreamInfo(
            anchorName = anchorName,
            isLive = true,
            flvUrl = playUrl,
            recordUrl = playUrl,
        )
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}