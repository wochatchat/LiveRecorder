/*
 * LaixiuSpider — 8a：来秀直播（对照 spider.py:3309 get_laixiu_stream_url）。
 *
 * 上游 laixiu.js 未被 Python 路径使用（get_laixiu_stream_url 内直接算 sign），
 * 故本平台为纯 Kotlin 实现：uuid(无-) + 时间戳 + 固定盐 → MD5 requestId。
 *
 * 流程（spider.py:3309-3361）：
 *   ① URL 取 roomId/anchorId 参数；② 签名头（timestamp/imei/requestId）；
 *   ③ GET api.imkktv.com/liveroom/getShareLiveVideo?roomId=；
 *   ④ playStatus==0 开播 → playUrl(flv)。
 */
package com.wochatchat.liverecorder.platform.laixiu

import com.wochatchat.liverecorder.net.LiveHttpClient
import java.security.MessageDigest
import java.util.UUID

open class LaixiuSpider(private val client: LiveHttpClient = LiveHttpClient()) {

    companion object {
        /** 上游 calculate_sign 固定盐（spider.py:3317）。 */
        const val SIGN_SALT = "kk792f28d6ff1f34ec702c08626d454b39pro"

        fun isLaixiuUrl(url: String): Boolean = url.contains("imkktv.com")

        /** (?:roomId|anchorId)=(.*?)(?=&|$)（spider.py:336）。 */
        fun parseRoomId(url: String): String =
            Regex("""(?:roomId|anchorId)=(.*?)(?=&|$)""").find(url)?.groupValues?.get(1) ?: ""

        /** 签名输入：web{imei}{timestamp}{salt}（上游 f"web{s}{a}{u}"）。 */
        fun buildSignInput(imei: String, timestampMs: Long): String =
            "web$imei$timestampMs$SIGN_SALT"

        fun md5Hex(input: String): String =
            MessageDigest.getInstance("MD5")
                .digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    data class LaixiuStreamInfo(
        val anchorName: String = "",
        val title: String = "",
        val isLive: Boolean = false,
        val flvUrl: String = "",
        val recordUrl: String = "",
    )

    /** 签名三元组（timestamp/imei/requestId），测试可注入时间与 uuid。 */
    data class SignData(val timestamp: Long, val imei: String, val requestId: String)

    fun calculateSign(
        timestamp: Long = System.currentTimeMillis(),
        imei: String = UUID.randomUUID().toString().replace("-", ""),
    ): SignData {
        val input = "web$imei$timestamp$SIGN_SALT"
        return SignData(timestamp, imei, md5Hex(input))
    }

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
        signProvider: () -> SignData = { calculateSign() },
    ): LaixiuStreamInfo {
        val sign = signProvider()
        val headers = buildMap {
            put(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/138.0.0.0 Safari/537.36 Edg/138.0.0.0",
            )
            put("mobileModel", "web")
            put("timestamp", sign.timestamp.toString())
            put("loginType", "2")
            put("versionCode", "10003")
            put("imei", sign.imei)
            put("requestId", sign.requestId)
            put("channel", "9")
            put("version", "1.0.0")
            put("os", "web")
            put("platform", "WEB")
            put("Origin", "https://www.imkktv.com")
            put("Referer", "https://www.imkktv.com/")
            put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            if (!cookie.isNullOrBlank()) put("cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)
        val roomId = parseRoomId(url)
        val api = "https://api.imkktv.com/liveroom/getShareLiveVideo?roomId=$roomId"
        val json = try { c.get(api, headers).text } catch (e: Exception) { return LaixiuStreamInfo() }
        return parseResponse(json)
    }

    // ---- internal ----

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)

    internal fun parseResponse(jsonStr: String): LaixiuStreamInfo {
        val json = try { org.json.JSONObject(jsonStr) } catch (e: Exception) { return LaixiuStreamInfo() }
        val data = json.optJSONObject("data") ?: return LaixiuStreamInfo()
        val anchorName = data.optString("nickname", "")
        // 上游 live_status = room_data['playStatus'] == 0（0 为开播）
        if (data.optInt("playStatus", -1) != 0) return LaixiuStreamInfo(anchorName = anchorName)
        val flv = data.optString("playUrl", "")
        return LaixiuStreamInfo(
            anchorName = anchorName,
            isLive = true,
            flvUrl = flv,
            recordUrl = flv,
        )
    }
}
