package com.wochatchat.liverecorder.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Phase 5a 全局录制配置（对齐上游 config.ini [录制设置] + [推送配置] 中尚未落地的项）。
 * 默认值逐项对照 /tmp/DouyinLiveRecorder/config/config.ini。
 *
 * 上游已有对应实现的配置项不在此重复：
 * 代理（4a）、平台 Cookie/账密（4b）、磁盘阈值（2h）、自动转 MP4（3h，MonitorStore）。
 *
 * 移动端明确不适用（语义缺失依据见 phase-5.md 5a）：
 * language（UI 跟随系统）、跳过代理检测/显示循环秒数/显示直播源地址/自定义脚本（CLI 专属）、
 * 线程数与排队读取时间（移动端轮询为顺序执行）、语言/保存路径（系统托管）、
 * h264 重编码与 mp3/m4a 音频格式（内置 ffmpeg 无 libx264，仅容器级 remux）、时间字幕。
 */
data class AppSettings(
    /** 循环时间(秒)（上游默认 300）。 */
    val loopIntervalSec: Long = 300,
    /** 画质（上游 原画|超清|高清|标清|流畅，经 get_quality_code 映射为画质码）。 */
    val quality: String = "原画",
    /** 分段录制是否开启（上游是）；关闭且无 ffmpeg 时回退 OkHttp 直下。 */
    val segmented: Boolean = true,
    /** 视频分段时间(秒)（上游默认 1800）。 */
    val segmentTimeSec: Int = 1800,
    /** 是否强制启用https录制（上游 main.py:1150）。 */
    val forceHttps: Boolean = false,
    /** 追加格式后删除原文件（TS→MP4 转换后是否删除原分片，上游默认是）。 */
    val deleteOriginalOnConvert: Boolean = true,
    /** 开播推送开启（上游默认是）。 */
    val pushOnLive: Boolean = true,
    /** 关播推送开启（上游默认否）。 */
    val pushOnOffline: Boolean = false,
    /** 只推送通知不录制（上游 disable_record，默认否）。 */
    val onlyNotify: Boolean = false,
)

/** Phase 5a：全局录制设置持久化（独立 "settings" DataStore，不动既有 MonitorStore 键）。 */
class AppSettingsStore(private val context: android.content.Context) {

    private val loopIntervalKey = longPreferencesKey("loop_interval_sec")
    private val qualityKey = stringPreferencesKey("quality")
    private val segmentedKey = booleanPreferencesKey("segmented")
    private val segmentTimeKey = intPreferencesKey("segment_time_sec")
    private val forceHttpsKey = booleanPreferencesKey("force_https")
    private val deleteOriginalKey = booleanPreferencesKey("delete_original_on_convert")
    private val pushOnLiveKey = booleanPreferencesKey("push_on_live")
    private val pushOnOfflineKey = booleanPreferencesKey("push_on_offline")
    private val onlyNotifyKey = booleanPreferencesKey("only_notify")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            loopIntervalSec = prefs[loopIntervalKey] ?: 300,
            quality = prefs[qualityKey] ?: "原画",
            segmented = prefs[segmentedKey] ?: true,
            segmentTimeSec = prefs[segmentTimeKey] ?: 1800,
            forceHttps = prefs[forceHttpsKey] ?: false,
            deleteOriginalOnConvert = prefs[deleteOriginalKey] ?: true,
            pushOnLive = prefs[pushOnLiveKey] ?: true,
            pushOnOffline = prefs[pushOnOfflineKey] ?: false,
            onlyNotify = prefs[onlyNotifyKey] ?: false,
        )
    }

    /** 整体保存（设置对话框确认时一次性写入）。 */
    suspend fun set(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[loopIntervalKey] = settings.loopIntervalSec
            prefs[qualityKey] = settings.quality
            prefs[segmentedKey] = settings.segmented
            prefs[segmentTimeKey] = settings.segmentTimeSec
            prefs[forceHttpsKey] = settings.forceHttps
            prefs[deleteOriginalKey] = settings.deleteOriginalOnConvert
            prefs[pushOnLiveKey] = settings.pushOnLive
            prefs[pushOnOfflineKey] = settings.pushOnOffline
            prefs[onlyNotifyKey] = settings.onlyNotify
        }
    }
}
