/*
 * KuaishouSpider — Phase 4 第一批：快手直播爬虫。
 *
 * 对照上游 spider.py:
 *   - get_kuaishou_stream_data(:316)  web 路径（main.py:609 主链路唯一调用）：
 *       GET live.kuaishou.com 页 → __INITIAL_STATE__ 正则 → liveStream 片段
 *       （`{"liveStream".*?),"gameInfo` + "}"）→ author.name + playUrls.h264.representation
 *   - get_kuaishou_stream_data2(:365)：api2 路径（/u/ 短链，POST livev.m.chenzhongtech.com
 *       rest/k/live/byUser，did 设备伪装 cookie）；上游仅定义未在 main.py 调用，本侧用于
 *       /u/ 短链优先加速，任何异常按上游语义回落 web 路径。
 *
 * 取流（stream.py:157 get_kuaishou_stream_url）：
 *   - type=1（页面请求/解析失败）且未开播 → 透传
 *   - flv 带 bitrate（web 路径）：按 bitrate 降序，取第一个 ≤ 阈值档（OD/BD/UHD/HD/SD/LD
 *     → 99999/4000/2000/1000/800/600）；无命中取最高档
 *   - flv 不带 bitrate（api2 路径）：倒序补齐到 5 档后按下标取（QUALITY_MAPPING OD=0…LD=4）
 *   - record_url 恒为 flv_url（HLS 仅作参考，不作为录制源）
 */
package com.wochatchat.liverecorder.platform.kuaishou

import com.wochatchat.liverecorder.net.LiveHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 快手爬虫：web 页路径（主）+ api2 短链路径（/u/ 优先，失败回落 web）。 */
class KuaishouSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
        private const val ACCEPT_LANGUAGE =
            "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2"
        private const val API2_URL =
            "https://livev.m.chenzhongtech.com/rest/k/live/byUser?kpn=GAME_ZONE&captchaToken="
        private const val API2_UA = "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))"
        private const val API2_DID_COOKIE = "did=web_e988652e11b545469633396abe85a89f; didv=1796004001000"
        private const val API2_REFERER = "https://www.kuaishou.com/short-video/3x224rwabjmuc9y"
        private const val API2_CONTENT_TYPE = "application/json; charset=utf-8"

        /** 上游 QUALITY_MAPPING（stream.py:26），get_quality_index 查表用。 */
        val QUALITY_MAPPING = linkedMapOf("OD" to 0, "BD" to 1, "UHD" to 2, "HD" to 2, "SD" to 3, "LD" to 4)

        /** 上游 quality_mapping_bit（stream.py:168），bitrate 阈值档。 */
        val QUALITY_MAPPING_BIT = linkedMapOf(
            "OD" to 99999, "BD" to 4000, "UHD" to 2000, "HD" to 1000, "SD" to 800, "LD" to 600,
        )
        private val QUALITY_KEYS = QUALITY_MAPPING_BIT.keys.toList()
        private val QUALITY_BITS = QUALITY_MAPPING_BIT.values.toList()

        private val RE_INITIAL_STATE = Regex("""window\.__INITIAL_STATE__=(.*?);\(function\(\)\{var s;""")
        private val RE_PLAY_LIST = Regex("""(\{"liveStream".*?),"gameInfo"""")

        /**
         * 上游 get_quality_index（stream.py:29）：空 → ("OD", 0)；纯数字 → 下标取键；
         * 其余大写后查 QUALITY_MAPPING，缺失默认 0。
         */
        internal fun qualityIndex(quality: String?): Pair<String, Int> {
            if (quality.isNullOrBlank()) return QUALITY_MAPPING.keys.first() to 0
            var s = quality.uppercase()
            if (s.first().isDigit()) {
                val idx = s.takeWhile { it.isDigit() }.toInt().coerceIn(0, QUALITY_MAPPING.size - 1)
                s = QUALITY_MAPPING.keys.toList()[idx]
            }
            return s to QUALITY_MAPPING.getOrDefault(s, 0)
        }
    }

    /** 单个流条目（web 的 representation / api2 的 multiResolution urls 均归一化）。 */
    data class KsStreamUrl(val url: String, val bitrate: Int? = null)

    /** spider 层结果（对齐上游 get_kuaishou_stream_data 返回 dict）。 */
    data class KsStreamData(
        /** 1=请求/解析失败（上游透传语义，视为未开播）；2=正常解析。 */
        val type: Int,
        val isLive: Boolean,
        val anchorName: String = "",
        val m3u8UrlList: List<KsStreamUrl> = emptyList(),
        val flvUrlList: List<KsStreamUrl> = emptyList(),
    )

    /** 取流结果（stream.py get_kuaishou_stream_url 返回的 record 字段子集）。 */
    data class KsPlayback(
        val m3u8Url: String,
        val flvUrl: String,
        val quality: String,
    )

    /**
     * 获取快手房间信息（上游 main.py:609 唯一链路 + data2 /u/ 短链增强）：
     * URL 含 /u/ 时先走 api2（失败回落 web，上游 get_kuaishou_stream_data2 语义），
     * 其余直接走 web 页面解析。
     * @param cookie 可选 Cookie（4b AuthStore 键 "kuaishou"，对齐上游 ks_cookie）
     */
    suspend fun getKuaishouInfo(url: String, proxyAddr: String? = null, cookie: String? = null): KsStreamData {
        if (url.contains("/u/")) {
            val api2 = try {
                getKuaishouInfoByApi2(url, proxyAddr, cookie)
            } catch (e: Exception) {
                null
            }
            if (api2 != null && api2.anchorName.isNotEmpty()) return api2
        }
        return getKuaishouInfoByWeb(url, proxyAddr, cookie)
    }

    /** web 页面路径（上游 get_kuaishou_stream_data，main.py:609 实际调用链）。 */
    suspend fun getKuaishouInfoByWeb(url: String, proxyAddr: String? = null, cookie: String? = null): KsStreamData {
        val headers = buildMap {
            put("User-Agent", WEB_UA)
            put("Accept-Language", ACCEPT_LANGUAGE)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val html = try {
            clientOrProxy(proxyAddr).get(url, headers).text
        } catch (e: Exception) {
            return KsStreamData(type = 1, isLive = false)
        }
        return parseInitialState(html)
    }

    /** api2 短链路径（上游 get_kuaishou_stream_data2；调用方需自行处理回落 web）。 */
    suspend fun getKuaishouInfoByApi2(url: String, proxyAddr: String? = null, cookie: String? = null): KsStreamData {
        val eid = url.substringAfter("/u/").trim()
        val headers = mapOf(
            "User-Agent" to API2_UA,
            "Accept-Language" to ACCEPT_LANGUAGE,
            "Referer" to API2_REFERER,
            "content-type" to API2_CONTENT_TYPE,
            "Cookie" to cookie.takeUnless { it.isNullOrBlank() } ?: API2_DID_COOKIE,
        )
        val body = JSONObject()
            .put("source", 5)
            .put("eid", eid)
            .put("shareMethod", "card")
            .put("clientType", "WEB_OUTSIDE_SHARE_H5")
        val result = clientOrProxy(proxyAddr).post(
            API2_URL,
            headers = headers,
            body = body.toString().toRequestBody(API2_CONTENT_TYPE.toMediaType()),
        )
        return parseApi2(result.text)
    }

    // ---- internal（单测直测）----

    /** 4a：proxyAddr 非空时按代理地址新建 client（上游 proxy_addr 透传语义）。 */
    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)

    /**
     * 解析 web 页 __INITIAL_STATE__（spider.py:331-341）：
     * 正则/JSON 任一失败 → type=1 未开播（上游透传语义）；errorType / 无 liveStream → type=2 未开播。
     */
    internal fun parseInitialState(html: String): KsStreamData {
        val jsonStr = try {
            RE_INITIAL_STATE.find(html)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        } ?: return KsStreamData(type = 1, isLive = false)
        val playList = try {
            val m = RE_PLAY_LIST.find(jsonStr) ?: return KsStreamData(type = 1, isLive = false)
            JSONObject(m.groupValues[1] + "}")
        } catch (e: Exception) {
            return KsStreamData(type = 1, isLive = false)
        }
        if (playList.has("errorType") || !playList.has("liveStream")) {
            // 上游 errorType 分支同语义：type=2 未开播（不回填主播名）
            return KsStreamData(type = 2, isLive = false)
        }
        val liveStream = playList.optJSONObject("liveStream")
            // 上游 "IP banned" 分支同语义：liveStream 缺失/为空 → 未开播
            ?: return KsStreamData(type = 2, isLive = false)
        val anchorName = playList.authorName
        val playUrls = liveStream.opt("playUrls")
            ?: return KsStreamData(type = 2, isLive = false, anchorName = anchorName)
        // h264 缺 adaptationSet → 未开播（上游同语义）；旧版本结构（playUrls 为数组）取 [0]
        val rep: JSONArray? = when (playUrls) {
            is JSONObject ->
                if (playUrls.has("h264")) {
                    val h264 = playUrls.optJSONObject("h264")
                    if (h264 == null || !h264.has("adaptationSet")) null
                    else h264.getJSONObject("adaptationSet").optJSONArray("representation")
                } else null
            is JSONArray -> playUrls.optJSONObject(0)?.optJSONObject("adaptationSet")?.optJSONArray("representation")
            else -> null
        }
        val flvList = rep?.let { toStreamUrls(it) } ?: emptyList()
        return KsStreamData(
            type = 2,
            isLive = flvList.isNotEmpty(),
            anchorName = anchorName,
            flvUrlList = flvList,
        )
    }

    /** 解析 api2 返回（上游 get_kuaishou_stream_data2 主干；异常由调用方回落 web）。 */
    internal fun parseApi2(jsonStr: String): KsStreamData {
        val liveStream = JSONObject(jsonStr).getJSONObject("liveStream")
        val anchorName = liveStream.getJSONObject("user").optString("user_name", "")
        if (!liveStream.optBoolean("living")) {
            return KsStreamData(type = 2, isLive = false, anchorName = anchorName)
        }
        // multiResolution 双列表（存在才取）；备用直链 backup 上下游均不消费，本侧省略
        val m3u8List = liveStream.optJSONObject("multiResolutionHlsPlayUrls")
            ?.optJSONArray("urls")?.let { toStreamUrls(it) } ?: emptyList()
        val flvList = liveStream.optJSONObject("multiResolutionPlayUrls")
            ?.optJSONArray("urls")?.let { toStreamUrls(it) } ?: emptyList()
        return KsStreamData(
            type = 2,
            isLive = true,
            anchorName = anchorName,
            m3u8UrlList = m3u8List,
            flvUrlList = flvList,
        )
    }

    /** representation/urls 数组 → 流条目（无 bitrate 字段记 null，走下标选择分支）。 */
    private fun toStreamUrls(arr: JSONArray): List<KsStreamUrl> = buildList {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val url = item.optString("url", "")
            if (url.isEmpty()) continue
            val bitrate = if (item.has("bitrate") && !item.isNull("bitrate")) item.optInt("bitrate") else null
            add(KsStreamUrl(url, bitrate))
        }
    }

    private val JSONObject.authorName: String
        get() = optJSONObject("author")?.optString("name", "") ?: ""

    /**
     * 取流（stream.py:157 get_kuaishou_stream_url）：画质映射 + record URL 选择。
     * 未开播 → 返回 null（路由层按未开播透传）；recordUrl 恒为 FLV（HLS 仅作参考）。
     */
    fun selectStream(info: KsStreamData, quality: String?): KsPlayback? {
        if (!info.isLive) return null
        val (_, qualityIdx) = qualityIndex(quality)
        val m3u8 = if (info.m3u8UrlList.isNotEmpty()) pickReversed(info.m3u8UrlList, qualityIdx).url else ""
        var flv = ""
        var qualityName = qualityIndex(quality).first
        if (info.flvUrlList.isNotEmpty()) {
            if (info.flvUrlList.first().bitrate != null) {
                val (url, name) = pickByBitrate(info.flvUrlList, quality)
                flv = url
                qualityName = name
            } else {
                flv = pickReversed(info.flvUrlList, qualityIdx).url
            }
        }
        return KsPlayback(m3u8Url = m3u8, flvUrl = flv, quality = qualityName)
    }

    /**
     * bitrate 选择（stream.py:180-201）：降序排序后取第一个 ≤ 阈值档；
     * 数字画质 → 下标取 quality_mapping_bit 的 (键, 阈值)；未命中取最高档。
     * 返回 (url, 画质名)。
     */
    internal fun pickByBitrate(list: List<KsStreamUrl>, quality: String?): Pair<String, String> {
        val sorted = list.sortedByDescending { it.bitrate ?: 0 }
        val q = (quality ?: "").uppercase()
        val (name, threshold) = if (q.firstOrNull()?.isDigit() == true) {
            val idx = q.takeWhile { it.isDigit() }.toInt().coerceIn(0, QUALITY_KEYS.size - 1)
            QUALITY_KEYS[idx] to QUALITY_BITS[idx]
        } else {
            q to QUALITY_MAPPING_BIT.getOrDefault(q, 99999)
        }
        val chosen = sorted.indexOfFirst { (it.bitrate ?: 0) <= threshold }
            .let { if (it < 0) sorted.size - 1 else it }
        return sorted[chosen].url to name
    }

    /** 倒序 + 不足 5 档用末位补齐后按画质下标取（上游 while len < 5 append(-1) 语义）。 */
    internal fun pickReversed(list: List<KsStreamUrl>, qualityIdx: Int): KsStreamUrl {
        var list2 = list.reversed()
        while (list2.size < 5) list2 = list2 + list2.last()
        return list2[qualityIdx.coerceAtMost(list2.size - 1)]
    }
}
