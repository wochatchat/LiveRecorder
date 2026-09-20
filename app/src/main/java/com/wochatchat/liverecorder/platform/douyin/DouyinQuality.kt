package com.wochatchat.liverecorder.platform.douyin

import com.wochatchat.liverecorder.net.LiveHttpClient

/**
 * 抖音源分发 + 画质映射（对照上游 src/stream.py get_douyin_stream_url）。
 *
 * 入口：[resolveStream]：传入 [DouyinWebRoom]，返回最终 [DouyinStreamInfo]（含 record_url）。
 *
 * 上游逻辑：
 * 1. status≠2 → is_live=false（不抛异常）
 * 2. 收集 flv/m3u8 列表，少于 5 档时复制末档补齐（上游 while 循环）
 * 3. 根据 quality 参数（OD/BD/UHD/HD/SD/LD 或 0-4 数字）计算 quality_index
 * 4. 取对应档位 URL；若探测（HEAD）失败，降一档重试（quality_index±1）
 * 5. record_url = m3u8_url ?? flv_url
 *
 * 质量参数优先级：用户显式设置 > 环境变量 RECORD_QUALITY（未实现，由调用方注入）
 */
object DouyinQuality {

    /**
     * 抖音源分发入口（对应上游 stream.get_douyin_stream_url）。
     * @param room  房间数据（含画质表），由 DouyinWebSpider/DouyinAppSpider/DouyinHtmlSpider 产出
     * @param quality 用户设置的质量（OD/BD/UHD/HD/SD/LD 或 "0".."4"），null=默认（取最高档）
     * @param client HTTP 客户端（用于 HEAD 探测）；null=跳过探测直接取 URL
     * @param proxyAddr 代理地址（传入 LiveHttpClient 构造时，不在此处）
     */
    suspend fun resolveStream(
        room: DouyinWebRoom,
        quality: String? = null,
        client: LiveHttpClient? = null,
        probe: suspend (String) -> Boolean = { url -> client?.head(url) ?: true },
    ): DouyinStreamInfo {
        if (!room.isLive) {
            return DouyinStreamInfo(
                anchorName = room.anchorName,
                isLive = false,
            )
        }

        // 上游 get_quality_index 越界（如数字画质 "9"）→ IndexError → trace_error_decorator 返回 []
        // 等价「未开播」：这里 catch 后返回 isLive=false
        val (qualityStr, qualityIndex) = try {
            resolveQualityIndex(quality)
        } catch (e: IndexOutOfBoundsException) {
            return DouyinStreamInfo(anchorName = room.anchorName, isLive = false)
        }
        val flvList = padToFive(room.flvPullUrl.values.toList())
        val hlsList = padToFive(room.hlsPullUrlMap.values.toList())

        val m3u8Url = hlsList.getOrElse(qualityIndex) { hlsList.last() }
        val flvUrl = flvList.getOrElse(qualityIndex) { flvList.last() }

        // 上游：先取 quality_index 档，探测失败则降档（quality_index±1，取另一端）
        val finalM3u8: String
        val finalFlv: String
        if (!probe(m3u8Url)) {
            val altIndex = if (qualityIndex < 4) qualityIndex + 1 else qualityIndex - 1
            finalM3u8 = hlsList.getOrElse(altIndex) { hlsList.first() }
            finalFlv = flvList.getOrElse(altIndex) { flvList.first() }
        } else {
            finalM3u8 = m3u8Url
            finalFlv = flvUrl
        }

        return DouyinStreamInfo(
            anchorName = room.anchorName,
            isLive = true,
            title = room.title,
            quality = qualityStr,
            m3u8Url = finalM3u8,
            flvUrl = finalFlv,
            recordUrl = finalM3u8.ifEmpty { finalFlv },
        )
    }

    /**
     * 将 list 补齐至 5 档（上游 while len < 5: append last）。
     * 若 list 为空，返回单元素列表（避免 last() 崩溃）。
     */
    private fun padToFive(list: List<String>): List<String> {
        if (list.isEmpty()) return listOf("")
        val result = list.toMutableList()
        while (result.size < 5) result.add(result.last())
        return result
    }

    /**
     * 将质量字符串转为（质量名, index）。
     * upstream: QUALITY_MAPPING = {"OD":0,"BD":0,"UHD":1,"HD":2,"SD":3,"LD":4}
     * index 取 QUALITY_MAPPING[quality_str]，默认 OD(0)。
     */
    internal fun resolveQualityIndex(quality: String?): Pair<String, Int> {
        if (quality.isNullOrEmpty()) {
            return "OD" to 0
        }

        val q = quality.uppercase()
        if (q.isDigit()) {
            // 上游：按键序 list(QUALITY_MAPPING.keys()) 取名字（含 BD）再查 index；
            // 越界数字上游抛 IndexError → trace_error_decorator 兜空结果，这里同语义抛出
            val index = q.getOrNull(0)?.digitToInt()
                ?: throw IndexOutOfBoundsException("invalid quality digit: $quality")
            val name = QUALITY_KEYS.getOrNull(index)
                ?: throw IndexOutOfBoundsException("quality index out of range: $quality")
            return name to (QUALITY_MAPPING[name] ?: 0)
        }

        return q to (QUALITY_MAPPING[q] ?: 0)
    }

    /** index → 质量名（数字画质参数按键序取名，同 upstream list(QUALITY_MAPPING.keys())）。 */
    private val QUALITY_KEYS = QUALITY_MAPPING.keys.toList()

    /** 质量名 → index（同 upstream QUALITY_MAPPING）。 */
    private val QUALITY_MAPPING = mapOf(
        "OD" to 0, "BD" to 0,   // 原画/超清都用 index=0
        "UHD" to 1,
        "HD" to 2,
        "SD" to 3,
        "LD" to 4,
    )
}

/**
 * 抖音流信息（对应上游 stream.get_douyin_stream_url 返回的 dict）。
 * 用于录制模块（1g）消费。
 */
data class DouyinStreamInfo(
    val anchorName: String,
    val isLive: Boolean,
    val title: String = "",
    val quality: String = "",
    val m3u8Url: String = "",
    val flvUrl: String = "",
    /** 录制用 URL（优先 m3u8，其次 flv）。 */
    val recordUrl: String = "",
)