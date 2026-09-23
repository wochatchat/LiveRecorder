package com.wochatchat.liverecorder.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wochatchat.liverecorder.push.PushConfig
import com.wochatchat.liverecorder.storage.StorageManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "monitor")

/**
 * Monitored live URLs. Stored as one URL per line in a single string key —
 * newline-joined keeps insertion order (a StringSet would not).
 */
class MonitorStore(private val context: Context) {

    private val key = stringPreferencesKey("urls")

    /** 监控总开关（2b）：开启时服务常驻并轮询检查开播状态。 */
    private val monitorKey = booleanPreferencesKey("monitor_enabled")

    val monitorEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[monitorKey] ?: false
    }

    suspend fun setMonitorEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[monitorKey] = enabled }
    }

    val urls: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[key]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    }

    /** 单条停用集合（2g，对齐上游 URL_config.ini 行首 `#` 注释即停用的语义）。 */
    private val disabledKey = stringPreferencesKey("disabled_urls")

    /** 已停用的 url 集合。 */
    val disabledUrls: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[disabledKey]?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    /** 仅已启用的监控条目（轮询用；上游逐行跳过 # 注释行同语义）。 */
    val enabledUrls: Flow<List<String>> = combine(urls, disabledUrls) { list, disabled ->
        list.filter { it !in disabled }
    }

    /** 单条启停：true=启用（参与轮询/自动录制），false=停用（等价上游 # 注释行）。 */
    suspend fun setEnabled(url: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[disabledKey]?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
            prefs[disabledKey] =
                (if (enabled) current - url else current + url).joinToString("\n")
        }
    }

    /** 编辑条目：替换 url（同一条目改名，保持列表位置与启停状态）。 */
    suspend fun renameUrl(oldUrl: String, newUrl: String) {
        val trimmed = newUrl.trim()
        if (trimmed.isEmpty() || trimmed == oldUrl) return
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            if (oldUrl in current && trimmed !in current) {
                prefs[key] = current.map { if (it == oldUrl) trimmed else it }.joinToString("\n")
            }
        }
    }

    suspend fun add(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            if (trimmed !in current) {
                prefs[key] = (current + trimmed).joinToString("\n")
            }
        }
    }

    suspend fun remove(url: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            prefs[key] = (current - url).joinToString("\n")
        }
    }

    /** 录制空间剩余阈值（GB）（2h，对齐上游 config.ini `录制空间剩余阈值(gb)`，默认 1.0）。 */
    private val diskLimitKey = stringPreferencesKey("disk_limit_gb")

    val diskLimitGb: Flow<Double> = context.dataStore.data.map { prefs ->
        prefs[diskLimitKey]?.toDoubleOrNull() ?: StorageManager.DEFAULT_THRESHOLD_GB
    }

    suspend fun setDiskLimitGb(gb: Double) {
        context.dataStore.edit { prefs -> prefs[diskLimitKey] = gb.toString() }
    }

    /** 3-3h：录制完成后自动转 MP4（对齐上游 config.ini「录制完成后自动转为mp4格式」，默认否）。 */
    private val autoConvertMp4Key = booleanPreferencesKey("auto_convert_mp4")

    val autoConvertMp4: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[autoConvertMp4Key] ?: false
    }

    suspend fun setAutoConvertMp4(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[autoConvertMp4Key] = enabled }
    }

    /** HTTP 推送配置（2f）：类型 + 地址列表（中英文逗号分隔）+ 总开关。 */
    private val pushEnabledKey = booleanPreferencesKey("push_enabled")
    private val pushTypeKey = stringPreferencesKey("push_type")
    private val pushApiKey = stringPreferencesKey("push_api")

    /**
     * 代理设置（4a，对齐上游「是否使用代理ip / 代理地址 / 使用代理录制的平台」）。
     * 平台关键词持久化为逗号分隔串；未设置时回落上游默认列表。
     */
    private val proxyEnabledKey = booleanPreferencesKey("proxy_enabled")
    private val proxyAddrKey = stringPreferencesKey("proxy_addr")
    private val proxyPlatformsKey = stringPreferencesKey("proxy_platforms")

    val proxySettings: Flow<ProxySettings> = context.dataStore.data.map { prefs ->
        ProxySettings(
            enabled = prefs[proxyEnabledKey] ?: false,
            addr = (prefs[proxyAddrKey] ?: "").trim(),
            platforms = ProxySettings.parsePlatforms(
                prefs[proxyPlatformsKey] ?: ProxySettings.DEFAULT_PLATFORMS.joinToString(","),
                fallback = ProxySettings.DEFAULT_PLATFORMS,
            ),
        )
    }

    suspend fun setProxySettings(settings: ProxySettings) {
        context.dataStore.edit { prefs ->
            prefs[proxyEnabledKey] = settings.enabled
            prefs[proxyAddrKey] = settings.addr.trim()
            prefs[proxyPlatformsKey] = settings.platforms.joinToString(",")
        }
    }

    val pushConfig: Flow<PushConfig> = context.dataStore.data.map { prefs ->
        PushConfig(
            enabled = prefs[pushEnabledKey] ?: false,
            type = prefs[pushTypeKey] ?: PushConfig.TYPE_NTFY,
            apis = (prefs[pushApiKey] ?: "")
                .replace('，', ',')
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() },
        )
    }

    suspend fun setPushConfig(enabled: Boolean, type: String, api: String) {
        context.dataStore.edit { prefs ->
            prefs[pushEnabledKey] = enabled
            prefs[pushTypeKey] = type
            prefs[pushApiKey] = api
        }
    }
}
