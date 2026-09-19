package com.wochatchat.liverecorder.platform.douyin

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.sign.AbSign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 抖音 web 路径房间数据模型（对应上游 get_douyin_web_stream_data 返回的 room_data 子集）。
 *
 * 上游任何异常都兜底为 {'anchor_name': ""}，这里用 [fetchError] 保留错误信息供日志/诊断，
 * 字段语义与上游一致：status==2 表示正在直播。
 */
data class DouyinWebRoom(
    val anchorName: String,
    val status: Int,
    val title: String,
    /** room_data.stream_url.flv_pull_url（含上游合并进来的 ORIGIN 档） */
    val flvPullUrl: Map<String, String>,
    /** room_data.stream_url.hls_pull_url_map（含上游合并进来的 ORIGIN 档） */
    val hlsPullUrlMap: Map<String, String>,
    val fetchError: String? = null,
) {
    val isLive: Boolean get() = status == 2

    companion object {
        fun empty(error: String? = null) =
            DouyinWebRoom("", 4, "", emptyMap(), emptyMap(), error)
    }
}
/**
     * 解析 enter 接口响应 JSON。
     * 上游：data['data'][0] → nickname → status==2 时 merge stream_url → return room_data。
     */
    internal fun parseRoomJson(json: String, roomUrl: String): DouyinWebRoom {
        val root = JSONObject(json)
        val data = root.getJSONObject("data")
        val arr = data.optJSONArray("data")
        if (arr == null || arr.length() == 0) {
            throw RuntimeException("$roomUrl VR live is not supported")
        }
        val room = arr.getJSONObject(0)
        val anchorName = data.getJSONObject("user").getString("nickname")
        val status = room.optInt("status", 4)
        val title = room.optString("title", "")

        if (status != 2) return DouyinWebRoom(anchorName, status, title, emptyMap(), emptyMap())

        // status==2 但 stream_url 不存在 → 上游抛出 RuntimeError，被外层 catch → 空结果
        val streamUrl = room.getJSONObject("stream_url")

        // 提取 pull_datas / live_core_sdk_data
        val liveCore = streamUrl.optJSONObject("live_core_sdk_data")
        val pullDatas = streamUrl.optJSONObject("pull_datas")

        val streamDataJson = when {
            pullDatas != null && pullDatas.length() > 0 -> {
                val key = pullDatas.keys().next()
                pullDatas.getJSONObject(key).getString("stream_data")
            }
            liveCore != null -> {
                liveCore.getJSONObject("pull_data").getString("stream_data")
            }
            else -> throw RuntimeException(
                "The live streaming type or gameplay is not supported on the computer side yet, " +
                    "please use the app to share the link for recording."
            )
        }

        val parsed = JSONObject(streamDataJson)

        // 读原始画质 map
        val flvMap = mutableMapOf<String, String>()
        val hlsMap = mutableMapOf<String, String>()

        streamUrl.optJSONObject("flv_pull_url")?.let { obj ->
            obj.keys().forEach { flvMap[it] = obj.getString(it) }
        }
        streamUrl.optJSONObject("hls_pull_url_map")?.let { obj ->
            obj.keys().forEach { hlsMap[it] = obj.getString(it) }
        }

        // origin 优先合并（ORIGIN 档插到 map 最前；若已有同名 key 则保持原值）
        if (parsed.getJSONObject("data").has("origin")) {
            // origin_data 必须从 live_core_sdk_data.pull_data.stream_data 解析（不是 pull_datas）
            val originStreamData = liveCore!!
                .getJSONObject("pull_data").getString("stream_data")
            val originJson = JSONObject(originStreamData)
            val originMain = originJson.getJSONObject("data")
                .getJSONObject("origin").getJSONObject("main")
            val sdkParams = JSONObject(originMain.getString("sdk_params"))
            val codec = sdkParams.optString("VCodec", "")
            val codecSuffix = if (codec.isNotEmpty()) "&codec=$codec" else ""

            val originFlvEntry = "ORIGIN" to (originMain.getString("flv") + codecSuffix)
            val originHlsEntry = "ORIGIN" to (originMain.getString("hls") + codecSuffix)

            // {**origin_m3u8, **hls_pull_url_map}：ORIGIN 优先
            flvMap = mutableMapOf(originFlvEntry).apply { putAll(flvMap) }
            hlsMap = mutableMapOf(originHlsEntry).apply { putAll(hlsMap) }
        }

        return DouyinWebRoom(anchorName, status, title, flvMap, hlsMap)
    }

    private companion object {
        private const val WEB_RID_PARAM = "web_rid"
        private const val API_BASE = "https://live.douyin.com/webcast/room/web/enter/"

        private val API_PARAMS = linkedMapOf(
            "aid" to "6383",
            "app_name" to "douyin_web",
            "live_id" to "1",
            "device_platform" to "web",
            "language" to "zh-CN",
            "browser_language" to "zh-CN",
            "browser_platform" to "Win32",
            "browser_name" to "Chrome",
            "browser_version" to "116.0.0.0",
            WEB_RID_PARAM to "",
            "msToken" to "",
        )

        /** 硬编码 ttwid 基础 Cookie，失效时替换（docs/04-risks.md）。 */
        private const val DEFAULT_COOKIE =
            "ttwid=1%7C2iDIYVmjzMcpZ20fcaFde0VghXAA3NaNXE_SLR68IyE%7C1761045455%7Cab35197d5cfb21df6cbb2fa7ef1c9262206b062c315b9d04da746d0b37dfbc7d"

        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}