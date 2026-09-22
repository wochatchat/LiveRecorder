/*
 * DouyuInfo — Phase 3d：斗鱼房间信息数据类。
 */
package com.wochatchat.liverecorder.platform.douyu

/** 斗鱼房间信息（对照 spider.py get_douyu_info_data 返回结构） */
data class DouyuInfo(
    /** 主播昵称 */
    val anchorName: String,
    /** 是否开播 */
    val isLive: Boolean,
    /** 直播标题（开播时非空） */
    val title: String? = null,
    /** 房间号（来自 betard room_id 字段） */
    val roomId: String? = null,
)

/** 斗鱼流信息（对照 spider.py get_douyu_stream_data 返回结构） */
data class DouyuStreamInfo(
    val roomId: String,
    val anchorName: String,
    /** m3u8 流 URL（可为 null，表示无可用流） */
    val streamUrl: String? = null,
    /** FLV 直下 URL（上游 stream.py get_douyu_stream_url：rtmp_url/rtmp_live，录制用） */
    val flvUrl: String? = null,
    /** 流描述 */
    val qualityLabel: String? = null,
    /** 原始 JSON 响应（用于调试/扩展） */
    val rawJson: String? = null,
)