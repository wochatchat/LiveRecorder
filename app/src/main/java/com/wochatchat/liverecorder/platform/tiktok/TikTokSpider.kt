package com.wochatchat.liverecorder.platform.tiktok

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.platform.douyin.DouyinQuality
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * TikTokSpider — Phase 8c：TikTok 直播爬虫。
 * 对照上游 spider.py:286 get_tiktok_stream_data + stream.py:82 get_tiktok_stream_url。
 *
 * 主流程：
 *   1. GET 房间页（Chrome 桌面 UA + referer tiktok.com + cookie）→ 重试 3 次（间隔 1s）：
 *      - 页面含「discontinued operating TikTok」→ 区域封锁，视为未开播
 *      - 页面含 UNEXPECTED_EOF_WHILE_READING → 重试下一轮
 *      - 提取 <script id="SIGI_STATE"> JSON；失败 → 未开播（上游 raise → trace_error 兜底）
 *   2. LiveRoom.liveRoomUserInfo.user.status==2 → 开播：
 *      streamData.pull_data.stream_data（JSON 字符串）→ data 各画质键 main.flv/hls
 *      → sdk_params 解 vbitrate/resolution/VCodec，URL 拼 codec 参数
 *      → vbitrate 降序、分辨率降序 → 补齐 5 档 → 画质索引取档
 *      → m3u8 ?: flv HEAD 探测失败则 ±1 档回退
 *
 * 已知差异：上游要求配置代理才发 TikTok 请求（main.py:598），安卓端改为直接尝试——
 * 未配置代理时请求失败自然回落未开播，监控循环按平台代理白名单逐轮重试。
 */
open class TikTokSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/141.0.0.0 Safari/537.36"

        /** 上游硬编码兜底 cookie（spider.py:291-293）。 */
        const val DEFAULT_COOKIE =
            "1%7Cz7FKki38aKyy7i-BC9rEDwcrVvjcLcFEL6QIeqldoy4%7C1761302831%7C6c1461e9f1f980cbe0404c5190" +
                "5177d5d53bbd822e1bf66128887d942c9c3e2f"

        private const val BLOCKED_MARKER = "We regret to inform you that we have discontinued operating TikTok"
        private const val EOF_MARKER = "UNEXPECTED_EOF_WHILE_READING"

        private val SIGI_STATE_REGEX =
            Pattern.compile("<script id=\"SIGI_STATE\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL)

        /** 上游 main.py:596：URL 含 https://www.tiktok.com/ 走 TikTok 链路。 */
        fun isTiktokUrl(url: String): Boolean = url.contains("tiktok.com/")

        /** SIGI_STATE JSON 提取（上游 re.findall 同语义，取首个匹配）。 */
        fun extractSigiState(html: String): String? {
            val m = SIGI_STATE_REGEX.matcher(html)
            return if (m.find()) m.group(1) else null
        }
    }

    data class TikTokStreamInfo(
        val anchorName: String = "",
        val title: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val flvUrl: String = "",
        val recordUrl: String = "",
    )

    /** 单档流地址（含码率与分辨率，用于排序）。 */
    data class QualityUrl(val url: String, val vbitrate: Int, val width: Int, val height: Int)

    open suspend fun getStreamInfo(
        url: String,
        quality: String? = null,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): TikTokStreamInfo {
        val headers = mapOf(
            "referer" to "https://www.tiktok.com/",
            "user-agent" to WEB_UA,
            "cookie" to (cookie?.takeIf { it.isNotBlank() } ?: DEFAULT_COOKIE),
        )
        val c = clientOrProxy(proxyAddr)

        // 上游 for i in range(3)：EOF 脏页重试，其余异常路径直接视为未开播
        var sigi: String? = null
        for (attempt in 0 until 3) {
            val html = try { c.get(url, headers).text } catch (e: Exception) { return TikTokStreamInfo() }
            delay(1000)
            if (html.contains(BLOCKED_MARKER)) return TikTokStreamInfo()
            if (html.contains(EOF_MARKER)) continue
            sigi = extractSigiState(html) ?: return TikTokStreamInfo()
            break
        }
        val json = sigi?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return TikTokStreamInfo()
        return selectStream(json, quality) { probeUrl -> c.head(probeUrl) }
    }

    /**
     * stream.py:82 get_tiktok_stream_url 同语义：
     * user.status==2 开播 → 画质列表排序/补齐/取档 → 探测回退 → recordUrl = m3u8 ?: flv。
     */
    open suspend fun selectStream(
        sigi: JSONObject,
        quality: String?,
        probe: suspend (String) -> Boolean,
    ): TikTokStreamInfo {
        val liveRoom = sigi.optJSONObject("LiveRoom")?.optJSONObject("liveRoomUserInfo")
            ?: return TikTokStreamInfo()
        val user = liveRoom.optJSONObject("user") ?: return TikTokStreamInfo()
        val anchorName = "${user.optString("nickname")}-${user.optString("uniqueId")}"
        // 上游 user.get("status", 4)：缺失默认非 2 → 未开播
        if (user.optInt("status", 4) != 2) {
            return TikTokStreamInfo(anchorName = anchorName)
        }
        val liveRoomObj = liveRoom.optJSONObject("liveRoom") ?: return TikTokStreamInfo(anchorName = anchorName)
        val title = liveRoomObj.optString("title")
        val streamData = liveRoomObj.optJSONObject("streamData")
            ?.optJSONObject("pull_data")?.optString("stream_data")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONObject("data")
            ?: return TikTokStreamInfo(anchorName = anchorName)

        val (qualityName, qualityIndex) = try {
            DouyinQuality.resolveQualityIndex(quality)
        } catch (e: IndexOutOfBoundsException) {
            // 上游 get_quality_index 越界 → IndexError → trace_error 兜空结果
            return TikTokStreamInfo(anchorName = anchorName)
        }
        val flvList = qualityUrlList(streamData, "flv")
        val hlsList = qualityUrlList(streamData, "hls")
        if (flvList.isEmpty() || hlsList.isEmpty()) {
            return TikTokStreamInfo(anchorName = anchorName)
        }
        val flvPadded = padToFive(flvList)
        val hlsPadded = padToFive(hlsList)

        val flv = flvPadded.getOrElse(qualityIndex) { flvPadded.last() }.url
        val m3u8 = hlsPadded.getOrElse(qualityIndex) { hlsPadded.last() }.url
        // 上游：check_url = m3u8 or flv 探测失败 → index±1（另一端）
        val ok = runCatching { probe(m3u8.ifEmpty { flv }) }.getOrDefault(false)
        val (finalFlv, finalM3u8) = if (!ok) {
            val alt = if (qualityIndex < 4) qualityIndex + 1 else qualityIndex - 1
            flvPadded.getOrElse(alt) { flvPadded.first() }.url to
                hlsPadded.getOrElse(alt) { hlsPadded.first() }.url
        } else {
            flv to m3u8
        }
        return TikTokStreamInfo(
            anchorName = anchorName,
            title = title,
            isLive = true,
            m3u8Url = finalM3u8,
            flvUrl = finalFlv,
            recordUrl = finalM3u8.ifEmpty { finalFlv },
        )
    }

    /**
     * stream.py get_video_quality_url 同语义：
     * 遍历 data 各画质键 → main.{qKey} 拼 codec 参数 → vbitrate≠0 且有 resolution 才入列
     * → vbitrate 降序，同码率先宽后高降序（上游两次 sort 的最终序）。
     */
    fun qualityUrlList(streamData: JSONObject, qKey: String): List<QualityUrl> {
        val list = mutableListOf<QualityUrl>()
        for (key in streamData.keys()) {
            val main = streamData.optJSONObject(key)?.optJSONObject("main") ?: continue
            val sdk = runCatching { JSONObject(main.optString("sdk_params")) }.getOrNull() ?: continue
            val vbitrate = sdk.optInt("vbitrate", 0)
            val vCodec = sdk.optString("VCodec", "")
            var playUrl = ""
            val rawUrl = main.optString(qKey, "")
            if (rawUrl.isNotEmpty()) {
                playUrl = if (rawUrl.endsWith(".flv") || rawUrl.endsWith(".m3u8")) {
                    "$rawUrl?codec=$vCodec"
                } else {
                    "$rawUrl&codec=$vCodec"
                }
            }
            val resolution = sdk.optString("resolution", "")
            if (vbitrate != 0 && resolution.isNotEmpty()) {
                val parts = resolution.split("x")
                if (parts.size == 2) {
                    list.add(QualityUrl(playUrl, vbitrate, parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0))
                }
            }
        }
        return list.sortedWith(
            compareByDescending<QualityUrl> { it.vbitrate }
                .thenByDescending { it.width }
                .thenByDescending { it.height },
        )
    }

    /** 补齐 5 档（上游 while len < 5: append last）；空列表返回空（调用方回落未开播）。 */
    fun padToFive(list: List<QualityUrl>): List<QualityUrl> {
        if (list.isEmpty()) return list
        val result = list.toMutableList()
        while (result.size < 5) result.add(result.last())
        return result
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}
