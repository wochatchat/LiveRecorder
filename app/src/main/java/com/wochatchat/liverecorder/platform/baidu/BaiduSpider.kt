package com.wochatchat.liverecorder.platform.baidu

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Random

/**
 * 百度直播（spider.py:1947 get_baidu_stream_data）。
 * GET mbd.baidu.com/searchbox → data.{key}.host.name / video.title + url_list → m3u8。
 */
open class BaiduSpider(private val client: LiveHttpClient = LiveHttpClient()) {
    companion object {
        private val DEVICE_IDS = listOf("h5-683e85bdf741bf2492586f7ca39bf465","h5-c7c6dc14064a136be4215b452fab9eea","h5-4581281f80bb8968bd9a9dfba6050d3a")
        fun isBaiduUrl(url: String) = url.contains("live.baidu.com/")
        fun parseRoomId(url: String) = Regex("room_id=(.*?)&").find(url)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }
    data class BaiduStreamInfo(val anchorName: String = "", val title: String = "", val isLive: Boolean = false, val m3u8Url: String = "", val recordUrl: String = "")
    open suspend fun getStreamInfo(url: String, proxyAddr: String? = null): BaiduStreamInfo {
        val roomId = parseRoomId(url) ?: return BaiduStreamInfo()
        val uid = DEVICE_IDS[Random().nextInt(DEVICE_IDS.size)]
        val c = if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
        val dataJson = """{"data":{"room_id":"$roomId","device_id":"$uid","source_type":0,"osname":"baiduboxapp"},"replay_slice":0,"nid":"","schemeParams":{"src_pre":"pc","src_suf":"other","bd_vid":"","share_uid":"","share_cuk":"","share_ecid":"","zb_tag":"","shareTaskInfo":"{\\"room_id\\":\\"$roomId\\"}","share_from":"","ext_params":"","nid":""}}"""
        val params = mapOf("cmd" to "371","action" to "star","service" to "bdbox","osname" to "baiduboxapp","data" to dataJson,"ua" to "360_740_ANDROID_0","bd_vid" to "","uid" to uid,"_" to System.currentTimeMillis().toString())
        val api = "https://mbd.baidu.com/searchbox?" + params.entries.joinToString("&") { (k,v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val resp = try { c.get(api, mapOf("Accept-Language" to "zh-CN,zh;q=0.9","Referer" to "https://live.baidu.com/","User-Agent" to "ios/7.830")) } catch (e: Exception) { return BaiduStreamInfo() }
        val json = try { JSONObject(resp.text) } catch (e: Exception) { return BaiduStreamInfo() }
        val key = json.optJSONObject("data")?.keys()?.asSequence()?.firstOrNull() ?: return BaiduStreamInfo()
        val data = json.getJSONObject("data").optJSONObject(key) ?: return BaiduStreamInfo()
        val anchorName = data.optJSONObject("host")?.optString("name","") ?: ""
        if (data.optString("status","1") != "0") return BaiduStreamInfo(anchorName = anchorName)
        val video = data.optJSONObject("video") ?: return BaiduStreamInfo(anchorName = anchorName)
        val m3u8 = buildM3u8Url(video) ?: return BaiduStreamInfo(anchorName = anchorName)
        return BaiduStreamInfo(anchorName = anchorName, title = title, isLive = true, m3u8Url = m3u8, recordUrl = m3u8)
    }
    private fun buildM3u8Url(video: JSONObject): String? {
        // 上游：url_clarity_list[].urls.flv → 取文件名换 .m3u8；否则 url_list[].urls[0].hls
        val clarityList = video.optJSONArray("url_clarity_list")
        if (clarityList != null && clarityList.length() > 0) {
            val flv = clarityList.getJSONObject(0).optJSONObject("urls")?.optString("flv", "") ?: ""
            if (flv.isNotBlank()) {
                val id = flv.substringBeforeLast(".").substringAfterLast("/")
                return "https://hls.liveshow.bdstatic.com/live/$id.m3u8"
            }
        }
        val urlList = video.optJSONArray("url_list")
        if (urlList != null && urlList.length() > 0) {
            val hls = urlList.getJSONObject(0).optJSONArray("urls")?.optJSONObject(0)
                ?.optString("hls", "") ?: return null
            val id = hls.split("?").first().substringBeforeLast("/").substringAfterLast("/")
            return "https://hls.liveshow.bdstatic.com/live/$id"
        }
        return null
    }
}