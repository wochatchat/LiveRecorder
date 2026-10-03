package com.wochatchat.liverecorder.data

import org.json.JSONObject

/**
 * Phase 4-4.1：单条监控条目的录制参数覆盖。
 * 所有字段可空 —— null 表示回落全局 AppSettings，非 null 覆盖全局值。
 * 对齐上游 URL_config.ini「单条 URL 独立参数」语义。
 */
data class PerUrlSettings(
    /** 画质（原画/超清/高清/标清/流畅），null=跟随全局。 */
    val quality: String? = null,
    /** 保存格式（ts/mkv/mp4），null=跟随全局。 */
    val saveFormat: String? = null,
    /** 分段录制开关，null=跟随全局。 */
    val segmented: Boolean? = null,
    /** 视频分段时间(秒)，null=跟随全局。 */
    val segmentTimeSec: Int? = null,
    /** 循环时间(秒)（该条目的监控轮询间隔），null=跟随全局。 */
    val loopIntervalSec: Long? = null,
    /** Phase 10-10.1：仅录制音频 m4a（FFmpeg -vn），null=关闭。 */
    val audioOnly: Boolean? = null,
) {
    /** 是否所有字段都未覆盖（= 无自定义，UI 可隐藏「参数」徽标）。 */
    val isEmpty: Boolean
        get() = quality == null && saveFormat == null && segmented == null &&
            segmentTimeSec == null && loopIntervalSec == null && audioOnly == null

    /** 有效性校验（UI 保存前调用；非法返回错误文案，null=合法）。 */
    fun validate(): String? = when {
        quality != null && quality !in VALID_QUALITIES -> "画质值非法"
        saveFormat != null && saveFormat !in VALID_SAVE_FORMATS -> "保存格式非法"
        segmentTimeSec != null && (segmentTimeSec < 10 || segmentTimeSec > 86400) -> "分段时间须在 10~86400 秒"
        loopIntervalSec != null && (loopIntervalSec < 60 || loopIntervalSec > 86400) -> "循环时间须在 60~86400 秒"
        else -> null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        quality?.let { put("quality", it) }
        saveFormat?.let { put("save_format", it) }
        segmented?.let { put("segmented", it) }
        segmentTimeSec?.let { put("segment_time_sec", it) }
        loopIntervalSec?.let { put("loop_interval_sec", it) }
        audioOnly?.let { put("audio_only", it) }
    }

    companion object {
        val VALID_QUALITIES = listOf("原画", "超清", "高清", "标清", "流畅")
        val VALID_SAVE_FORMATS = listOf("ts", "mkv", "mp4")

        fun fromJson(o: JSONObject): PerUrlSettings = PerUrlSettings(
            quality = o.optString("quality").takeIf { it.isNotBlank() && it != "null" },
            saveFormat = o.optString("save_format").takeIf { it.isNotBlank() && it != "null" },
            segmented = if (o.has("segmented") && !o.isNull("segmented")) o.getBoolean("segmented") else null,
            segmentTimeSec = if (o.has("segment_time_sec") && !o.isNull("segment_time_sec")) o.getInt("segment_time_sec") else null,
            loopIntervalSec = if (o.has("loop_interval_sec") && !o.isNull("loop_interval_sec")) o.getLong("loop_interval_sec") else null,
            audioOnly = if (o.has("audio_only") && !o.isNull("audio_only")) o.getBoolean("audio_only") else null,
        )
    }
}
