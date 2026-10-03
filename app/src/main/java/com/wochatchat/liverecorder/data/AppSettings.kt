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
    /** 7c：保存格式（上游 save_type；mkv|mp4=直存单文件 / ts=分段默认，R49 默认 mp4 可边录边看）。 */
    val saveFormat: String = "mp4",
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
    // ----- 5b：文件命名规则（上游 main.py:1803-1807，默认值逐项对照） -----
    /** 保存文件夹是否以作者区分（上游默认是）。 */
    val folderByAuthor: Boolean = true,
    /** 保存文件夹是否以时间区分（上游默认否）。 */
    val folderByTime: Boolean = false,
    /** 保存文件夹是否以标题区分（上游默认否）。 */
    val folderByTitle: Boolean = false,
    /** 保存文件名是否包含标题（上游默认否）。 */
    val filenameByTitle: Boolean = false,
    /** 是否去除名称中的表情符号（上游默认是）。 */
    val cleanEmoji: Boolean = true,

    // ---- Phase 4-4.2: 定时监控 ----
    /** 定时监控开关（开启后仅在设定时段内执行轮询/自动录制）。 */
    val scheduleMonitorEnabled: Boolean = false,
    /** 定时监控开始时间（每日分钟数 0-1439，如 1200=20:00）。 */
    val scheduleStartMinute: Int = 0,
    /** 定时监控结束时间（每日分钟数，支持跨天，如 1380=23:00，30=00:30）。 */
    val scheduleEndMinute: Int = 1440,

    // ---- Phase 4-4.4: 省电模式 ----
    /** 省电模式：WiFi 下录制（无 WiFi 时暂停轮询与录制）。 */
    val wifiOnly: Boolean = false,
    /** 省电模式：熄屏时暂停轮询（后台服务降频，录制中不停止）。 */
    val screenOffPause: Boolean = false,

    // ---- Phase 6-6.1: 分级通知/静音时段 ----
    /** 静音时段开关：开启后 23:00-07:00 不发开播/关播提醒（录制不受影响）。 */
    val quietNotifyEnabled: Boolean = true,

    // ---- V3-1: 仪器化诊断 ----
    /** 诊断信息显示：开启后监控卡片状态行渲染一行 DBG 诊断文本（可截图定位录制断点），默认关。 */
    val diagEnabled: Boolean = false,
    /** V3-1 R2：录制失败通知（开录后的失败/断流耗尽发通知，不再静默），默认开。 */
    val recordFailureNotify: Boolean = true,

    // ---- Phase 8-8.1: 录制日报 ----
    /** 录制日报推送开关：每天 09:00 后推送昨日录制统计（走 HTTP 推送）。 */
    val dailyReportEnabled: Boolean = false,

    // ---- Phase 9: 主题定制 ----
    /** 主题色（Phase 9-9.1）：system=跟随系统动态色 / red / blue / green / purple / orange。 */
    val themeColor: String = "system",
    /** AMOLED 纯黑模式（Phase 9-9.2）：深色下背景/表面用 #000000。 */
    val amoledBlack: Boolean = false,

    // ---- Phase 9-9.3: 自定义状态色（ARGB hex 字符串，空 = 走语义默认色） ----
    /** 「录制中」徽标色。 */
    val statusRecordingColor: String = "",
    /** 「异常」徽标色（监控 Error/录制失败）。 */
    val statusErrorColor: String = "",
    /** 「离线」徽标色。 */
    val statusOfflineColor: String = "",

    // ---- Phase 10-10.2: 带宽感知画质升降 ----
    /** 自适应画质：WiFi 下升一档，流量下降一档（单条画质覆盖优先，不受影响）。 */
    val adaptiveQuality: Boolean = false,
) {
    companion object {
        /** 静音时段起止（小时）：23:00 起，07:00 止。 */
        const val QUIET_START_HOUR = 23
        const val QUIET_END_HOUR = 7

        /**
         * Phase 6-6.1：当前小时是否处于静音时段（纯函数便于单测）。
         * [quietNotifyEnabled] 关闭时永不为静音；跨午夜窗口（23→07）按区间并集判定。
         */
        fun isQuietHour(settings: AppSettings, hourOfDay: Int): Boolean {
            if (!settings.quietNotifyEnabled) return false
            val h = hourOfDay.coerceIn(0, 23)
            return h >= QUIET_START_HOUR || h < QUIET_END_HOUR
        }
    }
}

/** Phase 5a：全局录制设置持久化（独立 "settings" DataStore，不动既有 MonitorStore 键）。 */
class AppSettingsStore(private val context: android.content.Context) {

    private val loopIntervalKey = longPreferencesKey("loop_interval_sec")
    private val qualityKey = stringPreferencesKey("quality")
    private val segmentedKey = booleanPreferencesKey("segmented")
    private val segmentTimeKey = intPreferencesKey("segment_time_sec")
    /** 7c：保存格式（ts/mkv/mp4）。 */
    private val saveFormatKey = stringPreferencesKey("save_format")
    private val forceHttpsKey = booleanPreferencesKey("force_https")
    private val deleteOriginalKey = booleanPreferencesKey("delete_original_on_convert")
    private val pushOnLiveKey = booleanPreferencesKey("push_on_live")
    private val pushOnOfflineKey = booleanPreferencesKey("push_on_offline")
    private val onlyNotifyKey = booleanPreferencesKey("only_notify")
    private val folderByAuthorKey = booleanPreferencesKey("folder_by_author")
    private val folderByTimeKey = booleanPreferencesKey("folder_by_time")
    private val folderByTitleKey = booleanPreferencesKey("folder_by_title")
    private val filenameByTitleKey = booleanPreferencesKey("filename_by_title")
    private val cleanEmojiKey = booleanPreferencesKey("clean_emoji")
    /** 6f R21：首启引导完成标记（独立 Flow，不进 AppSettings 设置页）。 */
    private val onboardingCompletedKey = booleanPreferencesKey("onboarding_completed")
    /** Phase 3：用户忽略的版本号（忽略后不再提示，直到有新版本）。 */
    private val ignoredVersionKey = stringPreferencesKey("ignored_version")

    /** Phase 7-7.3：录制详情页首次提示是否已展示（只显示一次）。 */
    private val detailHintShownKey = booleanPreferencesKey("detail_hint_shown")

    // Phase 4-4.2：定时监控
    private val scheduleMonitorEnabledKey = booleanPreferencesKey("schedule_monitor_enabled")
    private val scheduleStartMinuteKey = intPreferencesKey("schedule_start_minute")
    private val scheduleEndMinuteKey = intPreferencesKey("schedule_end_minute")

    // Phase 4-4.4：省电模式
    private val wifiOnlyKey = booleanPreferencesKey("wifi_only")
    private val screenOffPauseKey = booleanPreferencesKey("screen_off_pause")

    // Phase 6-6.1：静音时段
    private val quietNotifyEnabledKey = booleanPreferencesKey("quiet_notify_enabled")

    /** Phase 8-8.1：录制日报推送开关。 */
    private val dailyReportEnabledKey = booleanPreferencesKey("daily_report_enabled")

    /** Phase 8-8.1：上次已发日报的日期（yyyy-MM-dd，按"发送当天"记，防重复）。 */
    private val lastDailyReportDateKey = stringPreferencesKey("last_daily_report_date")

    /** Phase 9-9.1：主题色（system/red/blue/green/purple/orange）。 */
    private val themeColorKey = stringPreferencesKey("theme_color")

    /** Phase 9-9.2：AMOLED 纯黑模式。 */
    private val amoledBlackKey = booleanPreferencesKey("amoled_black")

    /** Phase 9-9.3：自定义状态色（ARGB hex，空 = 语义默认）。 */
    private val statusRecordingColorKey = stringPreferencesKey("status_recording_color")
    private val statusErrorColorKey = stringPreferencesKey("status_error_color")
    private val statusOfflineColorKey = stringPreferencesKey("status_offline_color")

    /** V3-1：诊断信息显示开关。 */
    private val diagEnabledKey = booleanPreferencesKey("diag_enabled")

    /** V3-1 R2：录制失败通知开关（默认开）。 */
    private val recordFailureNotifyKey = booleanPreferencesKey("record_failure_notify")

    /** Phase 10-10.2：带宽感知画质升降开关。 */
    private val adaptiveQualityKey = booleanPreferencesKey("adaptive_quality")

    /** 6f R21：首启引导是否已完成。 */
    val onboardingCompleted: Flow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[onboardingCompletedKey] ?: false }

    /** 6f R21：引导完成（含跳过）时置位。 */
    suspend fun completeOnboarding() {
        context.settingsDataStore.edit { prefs ->
            prefs[onboardingCompletedKey] = true
        }
    }

    /** Phase 3：已忽略的版本号（忽略后不再提示）。 */
    val ignoredVersion: Flow<String?> = context.settingsDataStore.data
        .map { prefs -> prefs[ignoredVersionKey] }

    /** Phase 7-7.3：详情页首次提示是否已展示。 */
    val detailHintShown: Flow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[detailHintShownKey] ?: false }

    /** Phase 7-7.3：标记详情页首次提示已展示（只显示一次）。 */
    suspend fun markDetailHintShown() {
        context.settingsDataStore.edit { prefs ->
            prefs[detailHintShownKey] = true
        }
    }

    /** Phase 8-8.1：上次已发日报日期（空 = 从未发送）。 */
    val lastDailyReportDate: Flow<String> = context.settingsDataStore.data
        .map { prefs -> prefs[lastDailyReportDateKey] ?: "" }

    /** Phase 8-8.1：记录日报已发送日期（防重复）。 */
    suspend fun setLastDailyReportDate(date: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[lastDailyReportDateKey] = date
        }
    }

    /** Phase 3：忽略指定版本。 */
    suspend fun setIgnoredVersion(version: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[ignoredVersionKey] = version
        }
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            loopIntervalSec = prefs[loopIntervalKey] ?: 300,
            quality = prefs[qualityKey] ?: "原画",
            segmented = prefs[segmentedKey] ?: true,
            segmentTimeSec = prefs[segmentTimeKey] ?: 1800,
            saveFormat = prefs[saveFormatKey] ?: "mp4",
            forceHttps = prefs[forceHttpsKey] ?: false,
            deleteOriginalOnConvert = prefs[deleteOriginalKey] ?: true,
            pushOnLive = prefs[pushOnLiveKey] ?: true,
            pushOnOffline = prefs[pushOnOfflineKey] ?: false,
            onlyNotify = prefs[onlyNotifyKey] ?: false,
            folderByAuthor = prefs[folderByAuthorKey] ?: true,
            folderByTime = prefs[folderByTimeKey] ?: false,
            folderByTitle = prefs[folderByTitleKey] ?: false,
            filenameByTitle = prefs[filenameByTitleKey] ?: false,
            cleanEmoji = prefs[cleanEmojiKey] ?: true,
            scheduleMonitorEnabled = prefs[scheduleMonitorEnabledKey] ?: false,
            scheduleStartMinute = prefs[scheduleStartMinuteKey] ?: 0,
            scheduleEndMinute = prefs[scheduleEndMinuteKey] ?: 1440,
            wifiOnly = prefs[wifiOnlyKey] ?: false,
            screenOffPause = prefs[screenOffPauseKey] ?: false,
            quietNotifyEnabled = prefs[quietNotifyEnabledKey] ?: true,
            dailyReportEnabled = prefs[dailyReportEnabledKey] ?: false,
            themeColor = prefs[themeColorKey] ?: "system",
            amoledBlack = prefs[amoledBlackKey] ?: false,
            statusRecordingColor = prefs[statusRecordingColorKey] ?: "",
            statusErrorColor = prefs[statusErrorColorKey] ?: "",
            statusOfflineColor = prefs[statusOfflineColorKey] ?: "",
            diagEnabled = prefs[diagEnabledKey] ?: false,
            recordFailureNotify = prefs[recordFailureNotifyKey] ?: true,
            adaptiveQuality = prefs[adaptiveQualityKey] ?: false,
        )
    }

    /** 整体保存（设置对话框确认时一次性写入）。 */
    suspend fun set(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[loopIntervalKey] = settings.loopIntervalSec

            prefs[qualityKey] = settings.quality
            prefs[segmentedKey] = settings.segmented
            prefs[segmentTimeKey] = settings.segmentTimeSec
            prefs[saveFormatKey] = settings.saveFormat
            prefs[forceHttpsKey] = settings.forceHttps
            prefs[deleteOriginalKey] = settings.deleteOriginalOnConvert
            prefs[pushOnLiveKey] = settings.pushOnLive
            prefs[pushOnOfflineKey] = settings.pushOnOffline
            prefs[onlyNotifyKey] = settings.onlyNotify
            prefs[folderByAuthorKey] = settings.folderByAuthor
            prefs[folderByTimeKey] = settings.folderByTime
            prefs[folderByTitleKey] = settings.folderByTitle
            prefs[filenameByTitleKey] = settings.filenameByTitle
            prefs[cleanEmojiKey] = settings.cleanEmoji
            prefs[scheduleMonitorEnabledKey] = settings.scheduleMonitorEnabled
            prefs[scheduleStartMinuteKey] = settings.scheduleStartMinute.coerceIn(0, 1439)
            prefs[scheduleEndMinuteKey] = settings.scheduleEndMinute.coerceIn(0, 1440)
            prefs[wifiOnlyKey] = settings.wifiOnly
            prefs[screenOffPauseKey] = settings.screenOffPause
            prefs[quietNotifyEnabledKey] = settings.quietNotifyEnabled
            prefs[dailyReportEnabledKey] = settings.dailyReportEnabled
            prefs[themeColorKey] = settings.themeColor
            prefs[amoledBlackKey] = settings.amoledBlack
            prefs[statusRecordingColorKey] = settings.statusRecordingColor
            prefs[statusErrorColorKey] = settings.statusErrorColor
            prefs[statusOfflineColorKey] = settings.statusOfflineColor
            prefs[diagEnabledKey] = settings.diagEnabled
            prefs[recordFailureNotifyKey] = settings.recordFailureNotify
            prefs[adaptiveQualityKey] = settings.adaptiveQuality
        }
    }

    /**
     * Phase 4-4.2：当前分钟数是否处于定时监控时段内。
     * start==end → 全天；跨天窗口（如 22:00~06:00）按区间并集判定。
     */
    fun isWithinSchedule(settings: AppSettings, minuteOfDay: Int): Boolean {
        if (!settings.scheduleMonitorEnabled) return true
        val s = settings.scheduleStartMinute.coerceIn(0, 1440)
        val e = settings.scheduleEndMinute.coerceIn(0, 1440)
        if (s == e) return true
        return if (s < e) minuteOfDay in s until e
        else minuteOfDay >= s || minuteOfDay < e
    }
}
