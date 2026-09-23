/*
 * PlatformRouter — Phase 3e：平台分发（对照上游 main.py:583 start_record 分流）。
 *
 * 上游按 record_url 域名逐平台分流（douyin.com → 抖音、www.douyu.com → 斗鱼，…），
 * 每平台 = spider.get_xxx_info_data（房间信息）+ stream.get_xxx_stream_url（画质映射+流地址）。
 *
 * 斗鱼流程（上游 spider.get_douyu_info_data + stream.get_douyu_stream_url）：
 *   betard 判开播/拿 rid → get_douyu_stream_data（签名 → getH5Play）
 *   → flv_url = rtmp_url/rtmp_live 作为 flv_url/record_url（stream.py:303）。
 *   未开播直接透传 is_live=false（不请求流）。
 *
 * 统一返回 DouyinStreamInfo 作为录制层消费的通用模型（斗鱼字段映射进同构）：
 *   flvUrl = 斗鱼 flv（FLV 直下，HLS 需 ffmpeg 暂不作为 record 源）
 *   recordUrl = flvUrl ?: streamUrl（上游 select_source_url：非 FLV 优先平台走 record_url）
 *   quality = 斗鱼 rate 标签（蓝光/超清/高清/标清）
 *
 * 其余 URL（含 douyin.com）→ 抖音路径，保持 Phase 1 行为不变。
 */
package com.wochatchat.liverecorder.platform

import com.wochatchat.liverecorder.platform.douyin.DouyinSpider
import com.wochatchat.liverecorder.platform.douyin.DouyinStreamInfo
import com.wochatchat.liverecorder.platform.douyu.DouyuSpider
import com.wochatchat.liverecorder.platform.kuaishou.KuaishouSpider

class PlatformRouter(
    private val douyinSpider: DouyinSpider = DouyinSpider(),
    private val douyuSpider: DouyuSpider = DouyuSpider(),
    private val kuaishouSpider: KuaishouSpider = KuaishouSpider(),
) {
    companion object {
        /** 斗鱼画质码映射（上游 stream.py get_douyu_stream_url video_quality_options）。 */
        val DOUYU_QUALITY_OPTIONS = mapOf(
            "OD" to "0", "BD" to "0",
            "UHD" to "3", "HD" to "2",
            "SD" to "1", "LD" to "1",
        )

        /** 上游 get_quality_code 缺失时画质为 null → 斗鱼默认 '0'（options.get(code, '0')）。 */
        const val DOUYU_DEFAULT_RATE = "0"

        fun isDouyuUrl(url: String): Boolean = url.contains("douyu.com/")

        /** 上游 main.py:609：live.kuaishou.com → 快手直播链路。 */
        fun isKuaishouUrl(url: String): Boolean = url.contains("live.kuaishou.com/")
    }

    /** 源分发 + 画质映射，一步到位（MonitorLoop 轮询与 RecordController 录制共用）。
     *  [cookies] 平台键 → cookie 串（4b AuthStore），快手等平台按需取用。 */
    suspend fun fetchStreamInfo(
        url: String,
        quality: String? = null,
        proxyAddr: String? = null,
        cookies: Map<String, String> = emptyMap(),
    ): DouyinStreamInfo = when {
        isDouyuUrl(url) -> fetchDouyu(url, quality, proxyAddr)
        isKuaishouUrl(url) -> fetchKuaishou(url, quality, proxyAddr, cookies["kuaishou"])
        else -> douyinSpider.fetchStreamInfo(url, quality, proxyAddr)
    }

    /**
     * 斗鱼 → 抖音同构映射（上游两步：get_douyu_info_data → get_douyu_stream_url）：
     * - 未开播：透传 anchor_name + is_live=false（不请求流地址）
     * - 开播：getH5Play → FLV（rtmp_url/rtmp_live）作为 flv/record 源；HLS 仅作 m3u8Url 参考
     */
    private suspend fun fetchDouyu(url: String, quality: String? = null, proxyAddr: String? = null): DouyinStreamInfo {
        val info = douyuSpider.getDouyuInfo(url, proxyAddr)
        if (!info.isLive) {
            return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        }
        val rid = info.roomId ?: DouyuSpider.parseRidFromUrl(url)
            ?: return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        val stream = douyuSpider.getDouyuStreamData(rid, rate = douyuRate(quality), proxyAddr = proxyAddr)
        val flv = stream.flvUrl
        val hls = stream.streamUrl
        return DouyinStreamInfo(
            anchorName = stream.anchorName,
            isLive = !flv.isNullOrEmpty() || !hls.isNullOrEmpty(),
            title = info.title ?: "",
            quality = stream.qualityLabel ?: "",
            m3u8Url = hls.orEmpty(),
            flvUrl = flv.orEmpty(),
            recordUrl = flv ?: hls.orEmpty(),
        )
    }

    /** 画质码 → 斗鱼 rate（上游 video_quality_options.get(code, '0') 语义）。 */
    private fun douyuRate(qualityCode: String?): String =
        qualityCode?.let { DOUYU_QUALITY_OPTIONS[it] } ?: DOUYU_DEFAULT_RATE

    /**
     * 快手 → 抖音同构映射（上游两步：get_kuaishou_stream_data → get_kuaishou_stream_url）：
     * web 页路径（主链路）+ /u/ 短链 api2 优先回落 web；recordUrl 恒为 FLV（HLS 仅参考）。
     */
    private suspend fun fetchKuaishou(
        url: String,
        quality: String?,
        proxyAddr: String?,
        cookie: String?,
    ): DouyinStreamInfo {
        val info = kuaishouSpider.getKuaishouInfo(url, proxyAddr, cookie)
        val play = kuaishouSpider.selectStream(info, quality)
            ?: return DouyinStreamInfo(anchorName = info.anchorName, isLive = false)
        return DouyinStreamInfo(
            anchorName = info.anchorName,
            isLive = true,
            quality = play.quality,
            m3u8Url = play.m3u8Url,
            flvUrl = play.flvUrl,
            recordUrl = play.flvUrl.ifEmpty { play.m3u8Url },
        )
    }
}
