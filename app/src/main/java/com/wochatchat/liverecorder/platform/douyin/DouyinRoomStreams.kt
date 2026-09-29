package com.wochatchat.liverecorder.platform.douyin

import org.json.JSONObject

/**
 * 抖音开播房间画质表解析（上游 spider.py web/app 两条路径 status==2 的公共块，代码逐行相同）。
 */
internal object DouyinRoomStreams {

    /**
     * 从 room_data.stream_url 提取画质表（flv_pull_url / hls_pull_url_map）。
     * 上游逻辑：
     * - stream_data 优先取 pull_datas[第一个key].stream_data，否则 live_core_sdk_data.pull_data.stream_data
     * - data.origin 存在 → ORIGIN 原画档合并进 map 最前（origin_data 恒取 live_core_sdk_data.pull_data.stream_data，
     *   sdk_params.VCodec 追加为 &codec= 参数；{**origin, **原map} = ORIGIN 优先，同名 key 保持原值）
     * 上游 stream_url 缺失抛 RuntimeError（外层 catch 兜空结果）；Kotlin 侧由调用方保证已取到 stream_url。
     */
    fun parseQualityMaps(streamUrl: JSONObject): Pair<Map<String, String>, Map<String, String>> {
        val liveCore = streamUrl.optJSONObject("live_core_sdk_data")
        val pullDatas = streamUrl.optJSONObject("pull_datas")

        val streamDataJson = when {
            pullDatas != null && pullDatas.length() > 0 ->
                pullDatas.getJSONObject(pullDatas.keys().next()).getString("stream_data")
            liveCore != null ->
                liveCore.getJSONObject("pull_data").getString("stream_data")
            else -> throw RuntimeException(NOT_SUPPORTED_MSG)
        }

        val flvMap = mutableMapOf<String, String>()
        val hlsMap = mutableMapOf<String, String>()
        streamUrl.optJSONObject("flv_pull_url")?.let { obj ->
            obj.keys().forEach { flvMap[it] = obj.getString(it) }
        }
        streamUrl.optJSONObject("hls_pull_url_map")?.let { obj ->
            obj.keys().forEach { hlsMap[it] = obj.getString(it) }
        }

        val parsed = JSONObject(streamDataJson)
        if (parsed.getJSONObject("data").has("origin")) {
            // origin_data 恒取 live_core_sdk_data.pull_data.stream_data（不是 pull_datas）
            val originMain = liveCore!!.getJSONObject("pull_data").getString("stream_data")
                .let { JSONObject(it).getJSONObject("data").getJSONObject("origin").getJSONObject("main") }
            val codec = JSONObject(originMain.getString("sdk_params")).optString("VCodec", "")
            val codecSuffix = if (codec.isNotEmpty()) "&codec=$codec" else ""

            val mergedFlv = linkedMapOf(
                "ORIGIN" to (originMain.getString("flv") + codecSuffix)
            )
            val mergedHls = linkedMapOf<String, String>()
            mergedHls["ORIGIN"] = originMain.getString("hls") + codecSuffix
            mergedFlv.putAll(flvMap)
            mergedHls.putAll(hlsMap)
            return mergedFlv to mergedHls
        }
        return flvMap to hlsMap
    }

    private const val NOT_SUPPORTED_MSG =
        "The live streaming type or gameplay is not supported on the computer side yet, " +
            "please use the app to share the link for recording."
}
