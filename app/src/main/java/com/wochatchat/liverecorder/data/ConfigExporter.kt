package com.wochatchat.liverecorder.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 4-4.3：全量配置导出/导入。
 * 导出格式 v1（见 README）：app_settings / proxy / push / urls / per_url_overrides / auth。
 * credentials.password 为明文 JSON，导入需用户二次确认。
 */
object ConfigExporter {

    private const val CONFIG_VERSION = 1

    /** 导出附带的认证数据（null = 用户选择不导出 cookie/账密）。 */
    data class AuthData(
        val cookies: Map<String, String>,
        val credentials: Map<String, Pair<String, String>>,
    )

    sealed class ImportResult {
        data class Success(
            val appSettings: AppSettings,
            val proxySettings: ProxySettings,
            val pushConfig: com.wochatchat.liverecorder.push.PushConfig,
            val urls: List<String>,
            val disabledUrls: Set<String>,
            val perUrlOverrides: Map<String, PerUrlSettings>,
            val cookies: Map<String, String>,
            val credentials: Map<String, Pair<String, String>>,
        ) : ImportResult()

        /** 文件包含 cookie/账密，需用户确认后重试。 */
        data object NeedsPasswordConfirmation : ImportResult()

        data class Error(val message: String) : ImportResult()
    }

    /** 导出全部配置为格式化 JSON 字符串。 */
    fun exportAll(
        appSettings: AppSettings,
        proxySettings: ProxySettings,
        pushConfig: com.wochatchat.liverecorder.push.PushConfig,
        urls: List<String>,
        disabledUrls: Set<String>,
        perUrlOverrides: Map<String, PerUrlSettings>,
        authData: AuthData?,
    ): String = JSONObject().apply {
        put("version", CONFIG_VERSION)
        put("exported_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
        put("app_settings", appSettingsToJson(appSettings))
        put("proxy_settings", proxySettings.toJson())
        put("push_config", pushConfigToJson(pushConfig))
        put("monitor_urls", JSONArray(urls))
        put("disabled_urls", JSONArray(disabledUrls.toList()))
        val overridesObj = JSONObject()
        perUrlOverrides.forEach { (url, s) -> overridesObj.put(url, s.toJson()) }
        put("per_url_overrides", overridesObj)
        authData?.let { auth ->
            put("cookies", JSONObject().apply {
                auth.cookies.forEach { (k, v) -> put(k, v) }
            })
            put("credentials", JSONObject().apply {
                auth.credentials.forEach { (k, pair) ->
                    put(k, JSONObject().apply {
                        put("username", pair.first)
                        put("password", pair.second)
                    })
                }
            })
        }
    }.toString(2)

    /** 导入：解析 JSON；含敏感数据且未确认时返回 NeedsPasswordConfirmation。 */
    fun parseImport(json: String, passwordConfirmed: Boolean = false): ImportResult {
        return try {
            val root = JSONObject(json)
            val version = root.optInt("version", 0)
            if (version != CONFIG_VERSION) {
                return ImportResult.Error("配置文件版本不匹配（v$version，当前 v$CONFIG_VERSION）")
            }
            val cookies = mutableMapOf<String, String>()
            val credentials = mutableMapOf<String, Pair<String, String>>()
            root.optJSONObject("cookies")?.let { obj ->
                obj.keys().forEach { k -> if (!obj.isNull(k)) cookies[k] = obj.optString(k) }
            }
            root.optJSONObject("credentials")?.let { creds ->
                creds.keys().forEach { k ->
                    if (!creds.isNull(k)) {
                        val c = creds.optJSONObject(k) ?: return@forEach
                        cookies.getOrPut(k) { "" }
                        credentials[k] = Pair(
                            c.optString("username", ""),
                            c.optString("password", ""),
                        )
                    }
                }
            }
            if ((cookies.isNotEmpty() || credentials.isNotEmpty()) && !passwordConfirmed) {
                return ImportResult.NeedsPasswordConfirmation
            }
            ImportResult.Success(
                appSettings = root.optJSONObject("app_settings")
                    ?.let { appSettingsFromJson(it) } ?: AppSettings(),
                proxySettings = root.optJSONObject("proxy_settings")
                    ?.let { ProxySettings.fromJson(it) } ?: ProxySettings(),
                pushConfig = root.optJSONObject("push_config")
                    ?.let { pushConfigFromJson(it) }
                    ?: com.wochatchat.liverecorder.push.PushConfig(),
                urls = root.optJSONArray("monitor_urls")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                } ?: emptyList(),
                disabledUrls = root.optJSONArray("disabled_urls")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
                } ?: emptySet(),
                perUrlOverrides = root.optJSONObject("per_url_overrides")?.let { obj ->
                    buildMap {
                        obj.keys().forEach { url ->
                            obj.optJSONObject(url)?.let { put(url, PerUrlSettings.fromJson(it)) }
                        }
                    }
                } ?: emptyMap(),
                cookies = cookies,
                credentials = credentials,
            )
        } catch (e: Exception) {
            ImportResult.Error("配置文件解析失败: ${e.message}")
        }
    }

    // ---- JSON 映射（AppSettings / ProxySettings / PushConfig 字段平铺） ----

    private fun appSettingsToJson(s: AppSettings): JSONObject = JSONObject().apply {
        put("loop_interval_sec", s.loopIntervalSec)
        put("quality", s.quality)
        put("segmented", s.segmented)
        put("segment_time_sec", s.segmentTimeSec)
        put("save_format", s.saveFormat)
        put("force_https", s.forceHttps)
        put("delete_original_on_convert", s.deleteOriginalOnConvert)
        put("push_on_live", s.pushOnLive)
        put("push_on_offline", s.pushOnOffline)
        put("only_notify", s.onlyNotify)
        put("folder_by_author", s.folderByAuthor)
        put("folder_by_time", s.folderByTime)
        put("folder_by_title", s.folderByTitle)
        put("filename_by_title", s.filenameByTitle)
        put("clean_emoji", s.cleanEmoji)
        put("schedule_monitor_enabled", s.scheduleMonitorEnabled)
        put("schedule_start_minute", s.scheduleStartMinute)
        put("schedule_end_minute", s.scheduleEndMinute)
        put("wifi_only", s.wifiOnly)
        put("screen_off_pause", s.screenOffPause)
    }

    private fun appSettingsFromJson(o: JSONObject): AppSettings = AppSettings(
        loopIntervalSec = o.optLong("loop_interval_sec", 300),
        quality = o.optString("quality", "原画"),
        segmented = o.optBoolean("segmented", true),
        segmentTimeSec = o.optInt("segment_time_sec", 1800),
        saveFormat = o.optString("save_format", "mp4"),
        forceHttps = o.optBoolean("force_https", false),
        deleteOriginalOnConvert = o.optBoolean("delete_original_on_convert", true),
        pushOnLive = o.optBoolean("push_on_live", true),
        pushOnOffline = o.optBoolean("push_on_offline", false),
        onlyNotify = o.optBoolean("only_notify", false),
        folderByAuthor = o.optBoolean("folder_by_author", true),
        folderByTime = o.optBoolean("folder_by_time", false),
        folderByTitle = o.optBoolean("folder_by_title", false),
        filenameByTitle = o.optBoolean("filename_by_title", false),
        cleanEmoji = o.optBoolean("clean_emoji", true),
        scheduleMonitorEnabled = o.optBoolean("schedule_monitor_enabled", false),
        scheduleStartMinute = o.optInt("schedule_start_minute", 0),
        scheduleEndMinute = o.optInt("schedule_end_minute", 1440),
        wifiOnly = o.optBoolean("wifi_only", false),
        screenOffPause = o.optBoolean("screen_off_pause", false),
    )

    private fun proxySettingsToJson(s: ProxySettings): JSONObject = JSONObject().apply {
        put("enabled", s.enabled)
        put("addr", s.addr)
        put("platforms", JSONArray(s.platforms))
    }

    private fun pushConfigToJson(c: com.wochatchat.liverecorder.push.PushConfig): JSONObject =
        JSONObject().apply {
            put("enabled", c.enabled)
            put("type", c.type)
            put("apis", JSONArray(c.apis))
            put("title", c.title)
            put("live_message", c.liveMessage)
            put("offline_message", c.offlineMessage)
            put("bark_level", c.barkLevel)
            put("bark_sound", c.barkSound)
            put("ntfy_tags", c.ntfyTags)
            put("ntfy_priority", c.ntfyPriority)
        }

    private fun pushConfigFromJson(o: JSONObject): com.wochatchat.liverecorder.push.PushConfig =
        com.wochatchat.liverecorder.push.PushConfig(
            enabled = o.optBoolean("enabled", false),
            type = o.optString("type", "ntfy"),
            apis = o.optJSONArray("apis")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            } ?: emptyList(),
            title = o.optString("title", ""),
            liveMessage = o.optString("live_message", ""),
            offlineMessage = o.optString("offline_message", ""),
            barkLevel = o.optString("bark_level", ""),
            barkSound = o.optString("bark_sound", ""),
            ntfyTags = o.optString("ntfy_tags", ""),
            ntfyPriority = o.optInt("ntfy_priority", 0),
        )
}
