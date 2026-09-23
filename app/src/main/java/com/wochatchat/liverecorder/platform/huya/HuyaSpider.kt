/*
 * HuyaSpider — Phase 4 第一批：虎牙直播爬虫。
 *
 * 对照上游 spider.py:
 *   - get_huya_stream_data(:408) web 页解析（主路径）：GET www.huya.com/{rid} →
 *       正则 `stream: (\{"data".*?),"iWebDefaultBitRate"` → data[0].gameLiveInfo +
 *       data[0].gameStreamInfoList（首个 CDN 为录制源）。
 *   - get_huya_app_stream_url(:425)：wx app 路径（备用）：mp.huya.com/cache.php
 *       ?m=Live&do=profileRoom&roomid={rid}&showSecret=1 → baseSteamInfoList
 *       （anti-code 已预计算，直接拼 URL；liveData.introduction 标题）。
 *
 * 取流（stream.py:210 get_huya_stream_url）：
 *   取 stream_info_list[0]（首个 CDN）；flv/m3u8 anti-code 实时重算
 *   （wsSecret=md5(fm前缀_uid_streamName_md5(seqid|ctype|t)_{wsTime})），
 *   exsphd 分段里倒序抽 264_ 后的码率档位作画质参数（&ratio=）；record 源恒为 FLV。
 *   房间号含字母时先抓 HTML 提 ProfileRoom（上游 spider.py:437）。
 */
package com.wochatchat.liverecorder.platform.huya

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.random.Random

/** 虎牙爬虫：web 页路径（主）+ wx app 路径（备用，app 路径不重算 anti-code）。 */
class HuyaSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:123.0) Gecko/20100101 Firefox/123.0"
        private const val APP_UA =
            "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))"
        private const val APP_REFERER =
            "https://servicewechat.com/wx74767bf0b684f7d3/301/page-frame.html"
        private const val CACHE_API = "https://mp.huya.com/cache.php"
        private const val SDK_VERSION = 2403051612L
        private const val PARAMS_T = 100

        private val RE_STREAM = Regex("""stream: (\{"data".*?),"iWebDefaultBitRate"""")
        private val RE_PROFILE_ROOM = Regex("""ProfileRoom":(.*?),"sPrivateHost""")
        private val RE_EXSPHD = Regex("""(?<=264_)\d+""")

        fun isHuyaUrl(url: String): Boolean = url.contains("huya.com/")

        /** 房间号段（spider.py:434：? 前最后一段）；含字母返回 null（需 HTML 提 ProfileRoom）。 */
        fun parseRoomId(url: String): String? {
            val seg = url.split("?").first().substringAfterLast("/")
            return seg.ifEmpty { null }?.takeIf { it.none { ch -> ch.isLetter() } }
        }
    }

    /** 房间信息结果（对齐上游 get_huya_stream_data 返回 + app 路径字段）。 */
    data class HuyaInfo(
        val roomId: String? = null,
        val anchorName: String = "",
        val title: String = "",
        val isLive: Boolean = false,
        val streams: List<HuyaStream> = emptyList(),
    )

    /** 单 CDN 条目（gameStreamInfoList[i] / baseSteamInfoList[i] 归一化）。 */
    data class HuyaStream(
        val cdnType: String = "",
        val flvUrl: String = "",
        val m3u8Url: String = "",
        val streamName: String = "",
        val flvAntiCode: String = "",
        val hlsAntiCode: String = "",
        val flvSuffix: String = "flv",
        val hlsSuffix: String = "m3u8",
    )

    /** 取流结果（recordUrl 恒为 FLV，对齐上游 record_url = flv_url or m3u8_url）。 */
    data class HuyaPlayback(
        val anchorName: String,
        val title: String,
        val quality: String,
        val m3u8Url: String,
        val flvUrl: String,
        val recordUrl: String,
    )

    /**
     * 获取虎牙房间信息：web 路径优先（上游主链路），异常/无流回落 app 路径。
     */
    suspend fun getHuyaInfo(url: String, proxyAddr: String? = null, cookie: String? = null): HuyaInfo {
        val web = runCatching { getHuyaInfoByWeb(url, proxyAddr, cookie) }.getOrNull()
        if (web != null && web.streams.isNotEmpty()) return web
        val app = runCatching { getHuyaInfoByApp(url, proxyAddr, cookie) }.getOrNull()
        return app ?: web ?: HuyaInfo()
    }

    // ---- web 路径（spider.py:408）----

    suspend fun getHuyaInfoByWeb(url: String, proxyAddr: String? = null, cookie: String? = null): HuyaInfo {
        val headers = buildMap {
            put("User-Agent", WEB_UA)
            put("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val html = clientOrProxy(proxyAddr).get(url, headers).text
        return parseWebResponse(html)
    }

    /** web 解析（spider.py:429-430 + stream.py:210）：正则失败/JSON 异常 → 未开播空结果。 */
    internal fun parseWebResponse(html: String): HuyaInfo {
        val jsonStr = RE_STREAM.find(html)?.groupValues?.get(1) ?: return HuyaInfo()
        val data0 = runCatching {
            val arr = JSONObject(jsonStr + "}").optJSONArray("data") ?: return@runCatching null
            arr.optJSONObject(0)
        }.getOrNull() ?: return HuyaInfo()
        val live = data0.optJSONObject("gameLiveInfo")
        val streams = parseWebStreamList(data0.optJSONArray("gameStreamInfoList"))
        return HuyaInfo(
            anchorName = live.optString("nick", ""),
            title = live.optString("introduction", ""),
            isLive = streams.isNotEmpty(),
            streams = streams,
        )
    }

    private fun parseWebStreamList(arr: org.json.JSONArray?): List<HuyaStream> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    HuyaStream(
                        cdnType = o.optString("sCdnType", ""),
                        flvUrl = o.optString("sFlvUrl", ""),
                        m3u8Url = o.optString("sHlsUrl", ""),
                        streamName = o.optString("sStreamName", ""),
                        flvAntiCode = o.optString("sFlvAntiCode", ""),
                        hlsAntiCode = o.optString("sHlsAntiCode", ""),
                        flvSuffix = o.optString("sFlvUrlSuffix", "flv"),
                        hlsSuffix = o.optString("sHlsUrlSuffix", "m3u8"),
                    )
                )
            }
        }
    }

    // ---- app 路径（spider.py:425 get_huya_app_stream_url）----

    suspend fun getHuyaInfoByApp(url: String, proxyAddr: String? = null, cookie: String? = null): HuyaInfo {
        var roomId = parseRoomId(url)
        if (roomId == null) {
            // 含字母房间号：抓页面提 ProfileRoom（spider.py:437-440，失败即报错语义）
            val html = clientOrProxy(proxyAddr).get(url, mapOf("User-Agent" to APP_UA)).text
            roomId = RE_PROFILE_ROOM.find(html)?.groupValues?.get(1)?.trim()
                ?: return HuyaInfo()
        }
        val apiUrl = "$CACHE_API?m=Live&do=profileRoom&roomid=$roomId&showSecret=1"
        val headers = buildMap {
            put("User-Agent", APP_UA)
            put("xweb_xhr", "1")
            put("referer", APP_REFERER)
            put("accept-language", "zh-CN,zh;q=0.9")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val resp = clientOrProxy(proxyAddr).get(apiUrl, headers)
        return parseAppResponse(resp.text, roomId)
    }

    /** app 解析（spider.py:441-468）：realLiveStatus != 'ON' → 未开播。 */
    internal fun parseAppResponse(jsonStr: String, roomId: String): HuyaInfo {
        val data = runCatching {
            JSONObject(jsonStr).optJSONObject("data")
        }.getOrNull() ?: return HuyaInfo()
        val anchorName = data.optJSONObject("profileInfo")?.optString("nick", "") ?: ""
        val title = data.optJSONObject("liveData")?.optString("introduction", "") ?: ""
        val isOn = data.optString("realLiveStatus", "") == "ON"
        val arr = data.optJSONObject("stream")?.optJSONArray("baseSteamInfoList")
        val streams = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val streamName = o.optString("sStreamName", "")
                    if (streamName.isEmpty()) continue
                    add(
                        HuyaStream(
                            cdnType = o.optString("sCdnType", ""),
                            streamName = streamName,
                            flvAntiCode = o.optString("sFlvAntiCode", ""),
                            hlsAntiCode = o.optString("sHlsAntiCode", ""),
                            flvSuffix = o.optString("sFlvUrlSuffix", "flv"),
                            hlsSuffix = o.optString("sHlsUrlSuffix", "m3u8"),
                            flvUrl = "${o.optString("sFlvUrl", "")}/$streamName.${
                                o.optString("sFlvUrlSuffix", "flv")
                            }?${o.optString("sFlvAntiCode", "")}",
                            m3u8Url = "${o.optString("sHlsUrl", "")}/$streamName.${
                                o.optString("sHlsUrlSuffix", "m3u8")
                            }?${o.optString("sHlsAntiCode", "")}",
                        )
                    )
                }
            }
        }
        return HuyaInfo(
            roomId = roomId,
            anchorName = anchorName,
            title = title,
            isLive = isOn && streams.isNotEmpty(),
            streams = streams,
        )
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)

    // ---- 取流（stream.py:210 get_huya_stream_url）----

    /**
     * 上游 main.py:618-630 分流语义：
     * quality ∈ [OD,BD,UHD] → app 路径（TX CDN 优先，预计算 URL，无 ratio 画质段）；
     * 其余（HD/SD/LD）→ web 路径 + anti-code 重算 + ratio 取码率档。
     */
    fun selectStream(info: HuyaInfo, quality: String?): HuyaPlayback? {
        if (!info.isLive || info.streams.isEmpty()) return null
        val q = normalizeQuality(quality)
        return if (q in setOf("OD", "BD", "UHD")) selectFromAppPaths(info, q)
        else selectFromWebPaths(info, q)
    }

    /** 数字画质 → QUALITY_MAPPING 命名（上游 get_quality_index 语义），缺失默认 OD。 */
    private fun normalizeQuality(quality: String?): String {
        val s = (quality ?: "").uppercase()
        if (s.isEmpty()) return "OD"
        if (s.first().isDigit()) {
            val idx = s.takeWhile { it.isDigit() }.toInt().coerceIn(0, 5)
            return listOf("OD", "BD", "UHD", "HD", "SD", "LD")[idx]
        }
        return s
    }

    /** app 路径取流：TX/HW/HS/AL 优先级选 CDN，TX 做 ctype/fs 替换并强转 https（spider.py:476-510）。 */
    private fun selectFromAppPaths(info: HuyaInfo, quality: String): HuyaPlayback {
        var recordUrl = ""
        var firstFlv = ""
        var firstM3u8 = ""
        for (cdn in priorityOrder) {
            val s = info.streams.firstOrNull { it.cdnType == cdn } ?: continue
            var url = "https://" + s.flvUrl.substringAfter("://")
            if (cdn == "TX") {
                url = url.replace("&ctype=tars_mp", "&ctype=huya_webh5").replace("&fs=bhct", "&fs=bgct")
            }
            recordUrl = url
            break
        }
        if (recordUrl.isEmpty()) recordUrl = info.streams.first().flvUrl
        firstFlv = info.streams.first().flvUrl
        firstM3u8 = info.streams.first().m3u8Url
        return HuyaPlayback(
            anchorName = info.anchorName,
            title = info.title,
            quality = quality,
            m3u8Url = firstM3u8,
            flvUrl = firstFlv,
            recordUrl = recordUrl,
        )
    }

    /** CDN 优先级（上游 spider.py:477，2025/03 AL 不可用）。 */
    private val priorityOrder = listOf("TX", "HW", "HS", "AL")

    /** web 路径取流：stream_list[0] + anti-code 重算 + exsphd 画质档（stream.py:210-303）。 */
    private fun selectFromWebPaths(info: HuyaInfo, quality: String): HuyaPlayback? {
        val s = info.streams.first()
        val anti = buildAntiCode(s.flvAntiCode, s.streamName)
        var flv = "${s.flvUrl}/${s.streamName}.${s.flvSuffix}?$anti&ratio="
        var m3u8 = "${s.m3u8Url}/${s.streamName}.${s.hlsSuffix}?$anti&ratio="
        val exsphd = s.flvAntiCode.substringAfter("&exsphd=", "")
        if (exsphd.isNotEmpty() && quality !in setOf("OD", "BD")) {
            val qList = RE_EXSPHD.findAll(exsphd).map { it.value }.toList().reversed().toMutableList()
            while (qList.size < 5) qList.add(qList.last())
            val options = mapOf("UHD" to 0, "HD" to 1, "SD" to 2, "LD" to 3)
            val ratio = options[quality] ?: return null
            flv += qList.getOrNull(ratio) ?: qList.last()
            m3u8 += qList.getOrNull(ratio) ?: qList.last()
        }
        return HuyaPlayback(
            anchorName = info.anchorName,
            title = info.title,
            quality = quality,
            m3u8Url = m3u8,
            flvUrl = flv,
            recordUrl = flv,
        )
    }

    /**
     * anti-code 重算（stream.py:220-262）：
     * fm 参数 URL 解码 + base64 解码取 `_` 前缀；wsSecret = md5(pf_uid_streamName_md5(seqid|ctype|t)_{wsTime})。
     */
    internal fun buildAntiCode(oldAntiCode: String, streamName: String): String {
        val query = parseQuery(oldAntiCode)
        val ctype = query["ctype"] ?: ""
        val fs = query["fs"] ?: ""
        val t13 = System.currentTimeMillis()
        val sdkSid = t13
        val initUuid = ((t13 % 10_000_000_000L) * 1000 + Random.nextInt(1000)) % 4294967295L
        val uid = Random.nextLong(1_400_000_000_000L, 1_400_001_000_000L)
        val seqId = uid + sdkSid
        val wsTime = (((t13 + 110624) / 1000)).toString(16)
        val fmRaw = query["fm"] ?: ""
        val pf = String(java.util.Base64.getDecoder().decode(fmUrlDecode(fmRaw))).split("_").first()
        val secretHash = md5("$seqId|$ctype|$PARAMS_T")
        val wsSecret = md5("${pf}_${uid}_${streamName}_${secretHash}_$wsTime")
        return "wsSecret=$wsSecret&wsTime=$wsTime&seqid=$seqId&ctype=$ctype&ver=1" +
            "&fs=$fs&uuid=$initUuid&u=$uid&t=$PARAMS_T&sv=$SDK_VERSION&sdk_sid=$sdkSid&codec=264"
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun fmUrlDecode(s: String): String =
        java.net.URLDecoder.decode(s, "UTF-8")

    /** 极简 query 解析（对齐 urllib.parse.parse_qs 的取值语义，值已 URL 解码）。 */
    internal fun parseQuery(antiCode: String): Map<String, String> =
        antiCode.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
}
